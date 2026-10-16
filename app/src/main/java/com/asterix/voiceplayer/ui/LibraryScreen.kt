package com.asterix.voiceplayer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asterix.voiceplayer.AppGraph
import com.asterix.voiceplayer.CommandSource
import com.asterix.voiceplayer.R
import com.asterix.voiceplayer.command.PlayMode
import com.asterix.voiceplayer.command.TextNormalizer
import com.asterix.voiceplayer.command.VoiceCommand
import com.asterix.voiceplayer.data.AliasCheck
import com.asterix.voiceplayer.data.AudioEntry
import com.asterix.voiceplayer.data.ImportDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pending import/rename conflict waiting for the user's decision. */
data class Conflict(val alias: String, val existing: AudioEntry, val draft: ImportDraft?, val renaming: AudioEntry?)

class LibraryViewModel : ViewModel() {
    private val library = AppGraph.library
    private val playback = AppGraph.playback

    private val _drafts = MutableStateFlow<List<ImportDraft>>(emptyList())
    val drafts = _drafts.asStateFlow()
    private val _conflict = MutableStateFlow<Conflict?>(null)
    val conflict = _conflict.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    var onMessage: (String) -> Unit = {}

    fun import(uris: List<Uri>) = viewModelScope.launch {
        _busy.value = true
        for (uri in uris) {
            try {
                val draft = library.prepareImport(uri)
                _drafts.update { it + draft }
            } catch (e: Exception) {
                onMessage(e.message ?: "Import failed")
            }
        }
        _busy.value = false
    }

    /** @return an error to show under the name field, or null if accepted (or deferred to a conflict dialog). */
    fun confirmDraft(draft: ImportDraft, alias: String): String? {
        return when (val check = library.checkAlias(alias)) {
            is AliasCheck.Invalid -> check.reason
            is AliasCheck.Duplicate -> {
                _conflict.value = Conflict(alias.trim(), check.existing, draft, null)
                null
            }
            AliasCheck.Ok -> {
                popDraft(draft)
                viewModelScope.launch {
                    library.commitImport(draft, alias)
                    onMessage("Added \"${alias.trim()}\" — say “play ${alias.trim()}”")
                }
                null
            }
        }
    }

    fun cancelDraft(draft: ImportDraft) {
        popDraft(draft)
        library.discardDraft(draft)
    }

    private fun popDraft(draft: ImportDraft) = _drafts.update { list -> list.filterNot { it === draft } }

    /** Conflict resolution: point the existing alias at the newly imported file. */
    fun resolveReplaceExisting(c: Conflict) {
        _conflict.value = null
        val draft = c.draft ?: return
        popDraft(draft)
        viewModelScope.launch {
            playback.onEntryChanged(null, c.existing.id)
            library.replaceWithDraft(c.existing, draft)
            onMessage("\"${c.existing.alias}\" now plays ${draft.originalName}")
        }
    }

    /** Conflict resolution: go back and choose a different name (draft stays queued). */
    fun resolveChooseAnother() {
        _conflict.value = null
    }

    fun rename(entry: AudioEntry, alias: String): String? = when (val check = library.checkAlias(alias, entry.id)) {
        is AliasCheck.Invalid -> check.reason
        is AliasCheck.Duplicate -> "\"${check.existing.alias}\" already uses this name. Choose another."
        AliasCheck.Ok -> {
            viewModelScope.launch {
                val updated = library.rename(entry, alias)
                playback.onEntryChanged(updated, entry.id)
            }
            null
        }
    }

    fun replaceFile(entry: AudioEntry, uri: Uri) = viewModelScope.launch {
        _busy.value = true
        try {
            val draft = library.prepareImport(uri)
            playback.onEntryChanged(null, entry.id)
            library.replaceWithDraft(entry, draft)
            onMessage("\"${entry.alias}\" now plays ${draft.originalName}")
        } catch (e: Exception) {
            onMessage(e.message ?: "Replace failed")
        }
        _busy.value = false
    }

    fun delete(entry: AudioEntry) = viewModelScope.launch {
        playback.onEntryChanged(null, entry.id)
        library.delete(entry)
        onMessage("Deleted \"${entry.alias}\"")
    }
}

@Composable
fun LibraryScreen(modifier: Modifier, onMessage: (String) -> Unit, vm: LibraryViewModel = viewModel()) {
    vm.onMessage = onMessage
    val entries by AppGraph.library.entries.collectAsStateWithLifecycle()
    val playback by AppGraph.playback.state.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val conflict by vm.conflict.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<AudioEntry?>(null) }
    var deleting by remember { mutableStateOf<AudioEntry?>(null) }
    var replacing by remember { mutableStateOf<AudioEntry?>(null) }

    val audioTypes = arrayOf("audio/*")
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }
    val replaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replacing
        replacing = null
        if (uri != null && target != null) vm.replaceFile(target, uri)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Audio library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Each file gets a command name. Use plain words and numbers, e.g. “song1”, “alarm 2”, “morning news”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) Text("Importing…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (entries.isEmpty()) {
                item {
                    Text("No audio yet. Tap “Import audio” to add MP3, WAV, M4A, AAC or OGG files.",
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(entries, key = { it.id }) { e ->
                EntryCard(
                    entry = e,
                    status = if (playback.entryId == e.id) statusLabel(playback.status, playback.looping) else null,
                    onPlayOnce = { AppGraph.processor.execute(VoiceCommand.Play(e.aliasKey, e.alias, PlayMode.ONCE), "▶ ${e.alias}", CommandSource.TAP) },
                    onLoop = { AppGraph.processor.execute(VoiceCommand.Play(e.aliasKey, e.alias, PlayMode.LOOP), "🔁 ${e.alias}", CommandSource.TAP) },
                    onRename = { renaming = e },
                    onReplace = { replacing = e; replaceLauncher.launch(audioTypes) },
                    onDelete = { deleting = e },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { importLauncher.launch(audioTypes) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Import audio") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    // One draft at a time, in order.
    val draft = drafts.firstOrNull()
    if (draft != null && conflict == null) {
        NameDialog(
            title = "Name this audio",
            subtitle = "${draft.originalName} · ${formatDuration(draft.durationMs)}",
            initial = draft.suggestedAlias,
            confirmLabel = "Add",
            onConfirm = { vm.confirmDraft(draft, it) },
            onDismiss = { vm.cancelDraft(draft) },
        )
    }

    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = vm::resolveChooseAnother,
            title = { Text("Name already used") },
            text = {
                Text("“${c.alias}” is already assigned to ${c.existing.originalName}. " +
                    "Choose another name, or replace that entry's audio with ${c.draft?.originalName}.")
            },
            confirmButton = { TextButton(onClick = { vm.resolveReplaceExisting(c) }) { Text("Replace existing") } },
            dismissButton = { TextButton(onClick = vm::resolveChooseAnother) { Text("Choose another name") } },
        )
    }

    renaming?.let { e ->
        NameDialog(
            title = "Rename “${e.alias}”",
            subtitle = e.originalName,
            initial = e.alias,
            confirmLabel = "Save",
            onConfirm = { alias -> vm.rename(e, alias).also { if (it == null) renaming = null } },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${e.alias}”?") },
            text = { Text("The app's copy of ${e.originalName} will be removed. Your original file is not affected.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryCard(
    entry: AudioEntry,
    status: String?,
    onPlayOnce: () -> Unit,
    onLoop: () -> Unit,
    onRename: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val missing = !entry.fileExists
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Say: “play ${TextNormalizer.spokenWords(entry.alias).joinToString(" ")}”",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(entry.originalName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        missing -> "⚠ File missing or unreadable — use Replace file"
                        status != null -> "${formatDuration(entry.durationMs)} · $status"
                        else -> "${formatDuration(entry.durationMs)} · ${formatSize(entry.sizeBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlayOnce, enabled = !missing) { Icon(painterResource(R.drawable.ic_play), "Play once") }
            IconButton(onClick = onLoop, enabled = !missing) { Icon(painterResource(R.drawable.ic_repeat), "Play in loop") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Replace file") }, onClick = { menu = false; onReplace() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    subtitle: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> String?,
    onDismiss: () -> Unit,
) {
    var text by remember(initial, title) { mutableStateOf(initial) }
    var error by remember(initial, title) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Command name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "Say it as: “play ${TextNormalizer.spokenWords(text).joinToString(" ")}”")
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
