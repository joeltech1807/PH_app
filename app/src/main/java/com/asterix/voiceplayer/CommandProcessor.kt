package com.asterix.voiceplayer

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.SystemClock
import android.util.Log
import com.asterix.voiceplayer.command.CommandParser
import com.asterix.voiceplayer.command.CommandPolicy
import com.asterix.voiceplayer.command.PlayerAction
import com.asterix.voiceplayer.command.PlayerStatus
import com.asterix.voiceplayer.command.VoiceCommand
import com.asterix.voiceplayer.command.describe
import com.asterix.voiceplayer.data.AudioLibrary
import com.asterix.voiceplayer.data.SettingsStore
import com.asterix.voiceplayer.playback.PlaybackController
import com.asterix.voiceplayer.service.AsterixService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class CommandSource { VOICE, TAP }

data class CommandLog(
    val heard: String? = null,
    val interpreted: String? = null,
    val result: String? = null,
    val success: Boolean = true,
    val source: CommandSource = CommandSource.VOICE,
    val atMillis: Long = 0,
    /** Last utterance that was heard but was not a command (shown dimmed in the UI). */
    val ignoredHeard: String? = null,
)

/** Main-thread pipeline: recognised text -> parse -> policy -> player, with result logging and spoken errors. */
class CommandProcessor(
    private val context: Context,
    private val library: AudioLibrary,
    private val playback: PlaybackController,
    private val settings: SettingsStore,
) {
    private val parser = CommandParser()
    private val _log = MutableStateFlow(CommandLog())
    val log: StateFlow<CommandLog> = _log.asStateFlow()

    private val speaker = Speaker(context)

    fun onSpeech(text: String, confidence: Float) {
        if (speaker.recentlySpoke()) return // our own TTS feedback, not the user
        val aliases = library.entries.value.associate { it.aliasKey to it.alias }
        val command = parser.parse(text, aliases)
        if (command == VoiceCommand.NotACommand) {
            _log.value = _log.value.copy(ignoredHeard = text)
            Log.i(TAG, "ignored non-command speech: \"$text\"")
            return
        }
        val minConf = settings.settings.value.minConfidenceWhilePlaying
        if (playback.state.value.status == PlayerStatus.PLAYING && confidence < minConf) {
            _log.value = _log.value.copy(ignoredHeard = "$text  (ignored: low confidence ${"%.2f".format(confidence)})")
            return
        }
        execute(command, text, CommandSource.VOICE)
    }

    fun execute(command: VoiceCommand, heard: String?, source: CommandSource = CommandSource.TAP) {
        val decision = CommandPolicy.decide(command, playback.state.value.snapshot())
        var message = decision.message
        var success = decision.success

        when (val action = decision.action) {
            is PlayerAction.Start -> {
                val entry = library.findByKey(action.key)
                when {
                    entry == null -> {
                        message = "${action.alias} was not found in your audio library."
                        success = false
                    }
                    !entry.fileExists -> {
                        message = "The file for ${entry.alias} is missing. Replace it in the library."
                        success = false
                    }
                    else -> {
                        playback.start(entry, action.loop)
                        AsterixService.ensureRunningForPlayback(context)
                    }
                }
            }
            is PlayerAction.SetRepeat -> playback.setRepeat(action.loop)
            PlayerAction.Pause -> playback.pause()
            PlayerAction.Resume -> playback.resume()
            PlayerAction.Stop -> playback.stop()
            null -> Unit
        }

        _log.value = CommandLog(
            heard = heard, interpreted = command.describe(), result = message, success = success,
            source = source, atMillis = System.currentTimeMillis(), ignoredHeard = _log.value.ignoredHeard,
        )
        Log.i(TAG, "[$source] heard=\"$heard\" command=${command.describe()} -> ${if (success) "OK" else "FAIL"}: $message")
        if (!success && source == CommandSource.VOICE && settings.settings.value.speakErrors) speaker.say(message)
    }

    fun shutdown() = speaker.shutdown()

    private companion object { const val TAG = "AsterixCommand" }
}

/** Offline TTS (uses whatever engine/voices are installed). Tracks when it spoke so its echo can be ignored. */
private class Speaker(context: Context) {
    private var ready = false
    @Volatile private var speakingUntil = 0L
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) runCatching { tts.language = Locale.getDefault() }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speakingUntil = SystemClock.elapsedRealtime() + MAX_SPEECH_MS }
            override fun onDone(utteranceId: String?) { speakingUntil = SystemClock.elapsedRealtime() + ECHO_GRACE_MS }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speakingUntil = SystemClock.elapsedRealtime() }
        })
    }

    fun say(text: String) {
        if (!ready) return
        speakingUntil = SystemClock.elapsedRealtime() + MAX_SPEECH_MS
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "asterix-${SystemClock.elapsedRealtime()}")
    }

    fun recentlySpoke() = SystemClock.elapsedRealtime() < speakingUntil

    fun shutdown() = tts.shutdown()

    companion object {
        const val ECHO_GRACE_MS = 1200L
        /** Upper bound in case the engine never reports completion. */
        const val MAX_SPEECH_MS = 10_000L
    }
}
