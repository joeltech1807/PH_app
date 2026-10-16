package com.asterix.voiceplayer

import android.app.Application
import com.asterix.voiceplayer.command.TextNormalizer
import com.asterix.voiceplayer.data.AudioDatabase
import com.asterix.voiceplayer.data.AudioLibrary
import com.asterix.voiceplayer.data.SettingsStore
import com.asterix.voiceplayer.playback.PlaybackController
import com.asterix.voiceplayer.update.UpdateManager
import com.asterix.voiceplayer.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class AsterixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Process-wide singletons. The UI and the foreground service share these instances. */
object AppGraph {
    lateinit var library: AudioLibrary; private set
    lateinit var settings: SettingsStore; private set
    lateinit var playback: PlaybackController; private set
    lateinit var processor: CommandProcessor; private set
    lateinit var voice: VoiceEngine; private set
    lateinit var updates: UpdateManager; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(app: Application) {
        val db = AudioDatabase.create(app)
        library = AudioLibrary(app, db.audioDao(), scope)
        settings = SettingsStore(app, scope)
        playback = PlaybackController(app)
        updates = UpdateManager(app)
        processor = CommandProcessor(app, library, playback, settings)
        voice = VoiceEngine(
            context = app,
            settings = { settings.settings.value },
            aliasWords = { library.entries.value.flatMap { TextNormalizer.spokenWords(it.alias) } },
            onUtterance = processor::onSpeech,
        )
        scope.launch { library.deleteOrphanFiles() }
        scope.launch { library.entries.collect { voice.invalidateGrammar() } }
        scope.launch {
            settings.settings
                .distinctUntilChangedBy { Triple(it.restrictedVocabulary, it.echoCancellation, it.silenceGate) }
                .collect { voice.invalidateGrammar() }
        }
    }
}
