package com.asterix.voiceplayer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.asterix.voiceplayer.AppGraph
import com.asterix.voiceplayer.voice.ModelManager
import com.asterix.voiceplayer.voice.VoiceEngine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File

/**
 * Debug-build test hooks driven from adb, e.g.
 *   adb shell am broadcast -a com.asterix.voiceplayer.debug.SPEECH --es text "play song1 in loop" -n com.asterix.voiceplayer.debug/com.asterix.voiceplayer.debug.DebugCommandReceiver
 *   adb shell am broadcast -a com.asterix.voiceplayer.debug.RECOGNIZE_WAV --es path /sdcard/Android/data/com.asterix.voiceplayer/files/cmd.wav -n ...
 *   adb shell am broadcast -a com.asterix.voiceplayer.debug.IMPORT --es path <file> --es alias song1 -n ...
 * SPEECH skips the microphone; RECOGNIZE_WAV runs the real Vosk model + grammar on a 16 kHz mono PCM WAV.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val main = Handler(Looper.getMainLooper())
        when (intent.action) {
            "$PREFIX.SPEECH" -> {
                val text = intent.getStringExtra("text") ?: return
                AppGraph.processor.onSpeech(text, intent.getFloatExtra("conf", 1f))
            }
            "$PREFIX.RECOGNIZE_WAV" -> {
                val path = intent.getStringExtra("path") ?: return
                val pending = goAsync()
                Thread {
                    try {
                        val model = ModelManager.load(context)
                        val rec = Recognizer(model, VoiceEngine.SAMPLE_RATE.toFloat(), AppGraph.voice.grammarJson())
                        val bytes = File(path).readBytes()
                        val dataAt = (12 until bytes.size - 8).first {
                            bytes[it] == 'd'.code.toByte() && bytes[it + 1] == 'a'.code.toByte() &&
                                bytes[it + 2] == 't'.code.toByte() && bytes[it + 3] == 'a'.code.toByte()
                        }
                        val pcm = bytes.copyOfRange(dataAt + 8, bytes.size)
                        val texts = mutableListOf<String>()
                        var i = 0
                        while (i < pcm.size) {
                            val n = minOf(3200, pcm.size - i)
                            if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) {
                                JSONObject(rec.result).optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                            }
                            i += n
                        }
                        JSONObject(rec.finalResult).optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                        rec.close()
                        Log.i(TAG, "RECOGNIZE_WAV $path -> $texts")
                        main.post { texts.forEach { AppGraph.processor.onSpeech(it, 1f) } }
                    } catch (e: Throwable) {
                        Log.e(TAG, "RECOGNIZE_WAV failed", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            "$PREFIX.IMPORT" -> {
                val path = intent.getStringExtra("path") ?: return
                val alias = intent.getStringExtra("alias") ?: return
                val pending = goAsync()
                AppGraph.scope.launch {
                    try {
                        val draft = AppGraph.library.prepareImport(Uri.fromFile(File(path)))
                        val check = AppGraph.library.checkAlias(alias)
                        Log.i(TAG, "IMPORT $path as '$alias': check=$check duration=${draft.durationMs}")
                        if (check == com.asterix.voiceplayer.data.AliasCheck.Ok) AppGraph.library.commitImport(draft, alias)
                        else AppGraph.library.discardDraft(draft)
                    } catch (e: Throwable) {
                        Log.e(TAG, "IMPORT failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            "$PREFIX.STATE" -> {
                Log.i(TAG, "STATE playback=${AppGraph.playback.state.value} voice=${AppGraph.voice.state.value.mode} " +
                    "library=${AppGraph.library.entries.value.map { it.alias }}")
            }
        }
    }

    private companion object {
        const val PREFIX = "com.asterix.voiceplayer.debug"
        const val TAG = "AsterixDebug"
    }
}
