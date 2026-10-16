package com.asterix.voiceplayer.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.asterix.voiceplayer.command.CommandParser
import com.asterix.voiceplayer.command.TextNormalizer
import com.asterix.voiceplayer.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.IOException
import kotlin.math.sqrt

enum class ListeningMode { OFF, PREPARING, LISTENING, ERROR }

data class ListeningState(
    val mode: ListeningMode = ListeningMode.OFF,
    val detail: String? = null,
    /** Words heard so far in the current utterance. */
    val partial: String = "",
    /** Another app (e.g. a call or voice recorder) has taken the microphone; we receive silence. */
    val micSilenced: Boolean = false,
    val echoCancellerActive: Boolean = false,
)

/**
 * Continuous offline recognition: AudioRecord (16 kHz mono PCM) -> Vosk, on a dedicated thread.
 * Final utterances are delivered on the main thread via [onUtterance]. Errors are retried with backoff.
 */
class VoiceEngine(
    private val context: Context,
    private val settings: () -> AppSettings,
    private val aliasWords: () -> List<String>,
    private val onUtterance: (text: String, confidence: Float) -> Unit,
) {
    private val _state = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var grammarDirty = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        _state.value = ListeningState(ListeningMode.PREPARING, "Loading speech model…")
        thread = Thread(::runLoop, "asterix-voice").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        _state.value = ListeningState(ListeningMode.OFF)
    }

    /** Call when aliases or vocabulary settings change; applied between utterances. */
    fun invalidateGrammar() {
        grammarDirty = true
    }

    private fun runLoop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val model = ModelManager.load(context)
                listen(model)
                backoffMs = 1000L
            } catch (e: Throwable) {
                Log.w(TAG, "Recognition loop failed", e)
                if (!running) break
                _state.update { it.copy(mode = ListeningMode.ERROR, partial = "",
                    detail = "Microphone/recognizer error: ${e.message ?: e.javaClass.simpleName}. Retrying…") }
                sleepQuietly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the service before start().
    private fun listen(model: org.vosk.Model) {
        val cfg = settings()
        val source = if (cfg.echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("16 kHz recording not supported")
        val record = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE)) // ~0.5 s buffer
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Microphone unavailable")
        }

        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        if (cfg.echoCancellation && AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
        val silenceCallback = registerSilencedCallback(audioManager, record)

        var recognizer: Recognizer? = null
        try {
            recognizer = buildRecognizer(model, cfg)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Microphone is in use by another app")
            }
            _state.update {
                it.copy(mode = ListeningMode.LISTENING, partial = "", echoCancellerActive = aec?.enabled == true,
                    detail = if (cfg.restrictedVocabulary) "Listening for commands" else "Listening (open vocabulary)")
            }

            val buf = ShortArray(FRAME_SAMPLES)
            val previous = ShortArray(FRAME_SAMPLES)
            var previousLen = 0
            var inUtterance = false
            var quietFrames = 0
            var gated = false
            var noiseFloor = 200.0
            var lastPartial = ""
            val useGate = cfg.silenceGate

            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IOException("AudioRecord read error $n")
                if (n == 0) continue

                if (grammarDirty && !inUtterance) {
                    grammarDirty = false
                    val current = settings()
                    if (current.restrictedVocabulary != cfg.restrictedVocabulary ||
                        current.echoCancellation != cfg.echoCancellation || current.silenceGate != cfg.silenceGate) {
                        return // restart the loop with the new configuration
                    }
                    if (current.restrictedVocabulary) recognizer.setGrammar(grammarJson())
                }

                if (useGate) {
                    val rms = rms(buf, n)
                    val threshold = maxOf(MIN_GATE_RMS, noiseFloor * 2.5)
                    if (rms < threshold) {
                        noiseFloor = noiseFloor * 0.95 + rms * 0.05
                        quietFrames++
                    } else {
                        quietFrames = 0
                    }
                    if (!inUtterance && quietFrames > GATE_AFTER_QUIET_FRAMES) {
                        // Silence: skip the recognizer entirely but remember the frame for speech onset.
                        System.arraycopy(buf, 0, previous, 0, n)
                        previousLen = n
                        gated = true
                        continue
                    }
                    if (gated) {
                        gated = false
                        if (previousLen > 0) recognizer.acceptWaveForm(previous, previousLen)
                    }
                }

                if (recognizer.acceptWaveForm(buf, n)) {
                    inUtterance = false
                    lastPartial = ""
                    deliver(recognizer.result)
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                        .replace(TextNormalizer.UNKNOWN_TOKEN, "").trim()
                    inUtterance = partial.isNotEmpty()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        _state.update { it.copy(partial = partial) }
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
            recognizer?.close()
            if (silenceCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioManager.unregisterAudioRecordingCallback(silenceCallback)
            }
        }
    }

    private fun buildRecognizer(model: org.vosk.Model, cfg: AppSettings): Recognizer {
        grammarDirty = false
        val r = if (cfg.restrictedVocabulary) Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson())
        else Recognizer(model, SAMPLE_RATE.toFloat())
        r.setWords(true)
        // End utterances after ~0.5 s of silence for snappy command response.
        runCatching { r.setEndpointerDelays(5.0f, 0.5f, 10.0f) }
        return r
    }

    /** Grammar = command vocabulary + spoken forms of all aliases + [unk] for everything else. */
    fun grammarJson(): String {
        val words = (CommandParser.COMMAND_VOCABULARY + aliasWords()).filter { it.isNotBlank() }.toSortedSet()
        return JSONArray(words.toList() + TextNormalizer.UNKNOWN_TOKEN).toString()
    }

    private fun deliver(resultJson: String) {
        val json = JSONObject(resultJson)
        val text = json.optString("text").trim()
        _state.update { it.copy(partial = "") }
        if (text.isEmpty() || text.split(' ').all { it == TextNormalizer.UNKNOWN_TOKEN }) return
        val words = json.optJSONArray("result")
        var conf = 1f
        if (words != null && words.length() > 0) {
            var sum = 0.0
            for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 1.0)
            conf = (sum / words.length()).toFloat()
        }
        main.post { onUtterance(text, conf) }
    }

    private fun registerSilencedCallback(am: AudioManager, record: AudioRecord): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                val silenced = mine?.isClientSilenced == true
                _state.update { if (it.micSilenced != silenced) it.copy(micSilenced = silenced) else it }
            }
        }
        am.registerAudioRecordingCallback(cb, main)
        return cb
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n)
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    companion object {
        private const val TAG = "VoiceEngine"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600          // 100 ms
        private const val GATE_AFTER_QUIET_FRAMES = 15   // 1.5 s of quiet before gating
        private const val MIN_GATE_RMS = 120.0
    }
}
