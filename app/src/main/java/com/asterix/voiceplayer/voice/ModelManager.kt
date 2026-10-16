package com.asterix.voiceplayer.voice

import android.content.Context
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.IOException

/** Unpacks the bundled Vosk model from assets once and keeps the loaded model for the process lifetime. */
object ModelManager {
    private const val ASSET_DIR = "model-en-us"
    private const val VERSION_FILE = "MODEL_VERSION"

    @Volatile private var model: Model? = null

    @Synchronized
    fun load(context: Context): Model {
        model?.let { return it }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val dir = unpack(context.applicationContext)
        return Model(dir.absolutePath).also { model = it }
    }

    private fun unpack(context: Context): File {
        val target = File(context.noBackupFilesDir, ASSET_DIR)
        val bundled = context.assets.open("$ASSET_DIR/$VERSION_FILE").bufferedReader().use { it.readText().trim() }
        val marker = File(target, VERSION_FILE)
        if (marker.isFile && marker.readText().trim() == bundled) return target

        target.deleteRecursively()
        copyAssetDir(context, ASSET_DIR, target)
        if (!marker.isFile) throw IOException("Speech model unpack failed")
        return target
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        // Copy the version marker last so a partially copied model is never treated as complete.
        for (child in children.sortedBy { it == VERSION_FILE }) {
            copyAssetDir(context, "$assetPath/$child", File(dest, child))
        }
    }
}
