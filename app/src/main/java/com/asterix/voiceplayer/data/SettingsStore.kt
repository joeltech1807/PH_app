package com.asterix.voiceplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSettings(
    /** Speak "not found" and other errors with offline text-to-speech. */
    val speakErrors: Boolean = true,
    /** Use the VOICE_COMMUNICATION mic source plus AcousticEchoCanceler to suppress the app's own playback. */
    val echoCancellation: Boolean = true,
    /** Restrict the recognizer to command words + aliases. Much more accurate and fewer false triggers. */
    val restrictedVocabulary: Boolean = true,
    /** Skip recognition on silent audio frames to save CPU/battery. */
    val silenceGate: Boolean = true,
    /** Hold a partial wake lock while listening so recognition continues with the screen off. */
    val keepCpuAwake: Boolean = true,
    /** While audio plays, ignore commands below this average word confidence (0 disables). */
    val minConfidenceWhilePlaying: Float = 0.6f,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context, private val scope: CoroutineScope) {

    private object Keys {
        val speakErrors = booleanPreferencesKey("speak_errors")
        val echoCancellation = booleanPreferencesKey("echo_cancellation")
        val restrictedVocabulary = booleanPreferencesKey("restricted_vocabulary")
        val silenceGate = booleanPreferencesKey("silence_gate")
        val keepCpuAwake = booleanPreferencesKey("keep_cpu_awake")
        val minConfidence = floatPreferencesKey("min_confidence_playing")
        val wasListening = booleanPreferencesKey("was_listening")
    }

    private val defaults = AppSettings()

    val settings: StateFlow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            speakErrors = p[Keys.speakErrors] ?: defaults.speakErrors,
            echoCancellation = p[Keys.echoCancellation] ?: defaults.echoCancellation,
            restrictedVocabulary = p[Keys.restrictedVocabulary] ?: defaults.restrictedVocabulary,
            silenceGate = p[Keys.silenceGate] ?: defaults.silenceGate,
            keepCpuAwake = p[Keys.keepCpuAwake] ?: defaults.keepCpuAwake,
            minConfidenceWhilePlaying = p[Keys.minConfidence] ?: defaults.minConfidenceWhilePlaying,
        )
    }.stateIn(scope, SharingStarted.Eagerly, defaults)

    /** Whether the user left listening on; used to offer a restart after the system kills the service. */
    val wasListening: StateFlow<Boolean> = context.dataStore.data.map { it[Keys.wasListening] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.value)
        scope.launch {
            context.dataStore.edit { p ->
                p[Keys.speakErrors] = next.speakErrors
                p[Keys.echoCancellation] = next.echoCancellation
                p[Keys.restrictedVocabulary] = next.restrictedVocabulary
                p[Keys.silenceGate] = next.silenceGate
                p[Keys.keepCpuAwake] = next.keepCpuAwake
                p[Keys.minConfidence] = next.minConfidenceWhilePlaying
            }
        }
    }

    fun setWasListening(value: Boolean) {
        scope.launch { context.dataStore.edit { it[Keys.wasListening] = value } }
    }
}
