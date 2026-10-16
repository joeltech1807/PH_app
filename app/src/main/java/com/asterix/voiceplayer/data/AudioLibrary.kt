package com.asterix.voiceplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.asterix.voiceplayer.command.TextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file copied into app storage that is waiting for the user to pick an alias. */
data class ImportDraft(
    val tempFile: File,
    val originalName: String,
    val mimeType: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val suggestedAlias: String,
)

sealed interface AliasCheck {
    data object Ok : AliasCheck
    data class Invalid(val reason: String) : AliasCheck
    data class Duplicate(val existing: AudioEntry) : AliasCheck
}

/**
 * Audio library: copies picked files into app-private storage (so they stay readable after the
 * original is moved/deleted and no persisted URI permission is needed) and stores alias metadata in Room.
 */
class AudioLibrary(private val context: Context, private val dao: AudioDao, scope: CoroutineScope) {

    private val audioDir = File(context.filesDir, "audio").apply { mkdirs() }

    val entries: StateFlow<List<AudioEntry>> =
        dao.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun findByKey(key: String): AudioEntry? = entries.value.firstOrNull { it.aliasKey == key }

    fun checkAlias(alias: String, excludingId: Long? = null): AliasCheck {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return AliasCheck.Invalid("Enter a name")
        if (trimmed.length > 40) return AliasCheck.Invalid("Keep the name under 40 characters")
        val key = TextNormalizer.aliasKey(trimmed)
        if (key.isEmpty()) return AliasCheck.Invalid("Use letters or numbers")
        if (TextNormalizer.tokens(trimmed).firstOrNull() in RESERVED_FIRST_WORDS) {
            return AliasCheck.Invalid("Names can't start with a command word like \"play\" or \"stop\"")
        }
        val existing = entries.value.firstOrNull { it.aliasKey == key && it.id != excludingId }
        return if (existing != null) AliasCheck.Duplicate(existing) else AliasCheck.Ok
    }

    /** Copies [uri] into app storage and reads its metadata. Throws IOException if unreadable. */
    suspend fun prepareImport(uri: Uri): ImportDraft = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "audio"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri)
        val ext = name.substringAfterLast('.', "").ifEmpty {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "audio"
        }
        val target = File(audioDir, "${UUID.randomUUID()}.$ext")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
            input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw IOException("Could not read \"$name\": ${e.message}", e)
        }
        val duration = readDuration(target)
        if (duration <= 0 && mime?.startsWith("audio/") != true) {
            target.delete()
            throw IOException("\"$name\" does not look like a playable audio file")
        }
        ImportDraft(target, name, mime, duration, target.length().takeIf { it > 0 } ?: size, suggestAlias(name))
    }

    suspend fun commitImport(draft: ImportDraft, alias: String): AudioEntry {
        val trimmed = alias.trim()
        val entry = AudioEntry(
            alias = trimmed, aliasKey = TextNormalizer.aliasKey(trimmed), originalName = draft.originalName,
            filePath = draft.tempFile.absolutePath, mimeType = draft.mimeType, durationMs = draft.durationMs,
            sizeBytes = draft.sizeBytes, addedAt = System.currentTimeMillis(),
        )
        return entry.copy(id = dao.insert(entry))
    }

    /** Resolves a duplicate by pointing the existing alias at the new file. */
    suspend fun replaceWithDraft(existing: AudioEntry, draft: ImportDraft): AudioEntry {
        val updated = existing.copy(
            originalName = draft.originalName, filePath = draft.tempFile.absolutePath, mimeType = draft.mimeType,
            durationMs = draft.durationMs, sizeBytes = draft.sizeBytes,
        )
        dao.update(updated)
        deleteFile(existing.filePath)
        return updated
    }

    /** Deletes copies left behind by imports that never finished (e.g. the process was killed mid-dialog). */
    suspend fun deleteOrphanFiles() = withContext(Dispatchers.IO) {
        val known = dao.allFilePaths().toSet()
        audioDir.listFiles()?.filter { it.absolutePath !in known }?.forEach { it.delete() }
    }

    fun discardDraft(draft: ImportDraft) {
        draft.tempFile.delete()
    }

    suspend fun rename(entry: AudioEntry, newAlias: String): AudioEntry {
        val trimmed = newAlias.trim()
        val updated = entry.copy(alias = trimmed, aliasKey = TextNormalizer.aliasKey(trimmed))
        dao.update(updated)
        return updated
    }

    suspend fun delete(entry: AudioEntry) {
        dao.delete(entry)
        deleteFile(entry.filePath)
    }

    private suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) { File(path).delete() }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun suggestAlias(fileName: String): String {
        val base = fileName.substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()
            .split(' ').take(3).joinToString(" ")
        var candidate = base.ifEmpty { "audio" }.take(30)
        if (checkAlias(candidate) is AliasCheck.Invalid) candidate = "audio"
        if (checkAlias(candidate) == AliasCheck.Ok) return candidate
        var i = 1
        while (i < 1000 && checkAlias("$candidate $i") != AliasCheck.Ok) i++
        return "$candidate $i"
    }

    companion object {
        private val RESERVED_FIRST_WORDS = setOf("play", "start", "loop", "repeat", "stop", "pause", "resume", "continue")
    }
}
