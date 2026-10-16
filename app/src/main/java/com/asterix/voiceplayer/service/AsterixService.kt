package com.asterix.voiceplayer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.asterix.voiceplayer.AppGraph
import com.asterix.voiceplayer.CommandSource
import com.asterix.voiceplayer.R
import com.asterix.voiceplayer.command.PlayerStatus
import com.asterix.voiceplayer.command.VoiceCommand
import com.asterix.voiceplayer.ui.MainActivity
import com.asterix.voiceplayer.voice.ListeningMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive for continuous listening and background playback.
 *
 * Android rules this follows:
 *  - Started only from a user action in the visible activity (Android 14+ forbids starting a
 *    microphone foreground service from the background, so there is no auto-start on boot).
 *  - Declares foregroundServiceType microphone|mediaPlayback; the microphone type is only requested
 *    while listening and only if RECORD_AUDIO is granted.
 *  - Shows an ongoing notification with Stop listening / Pause / Stop controls while running.
 *  - Stops itself when neither listening nor playing.
 */
class AsterixService : Service() {

    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTypes = 0
    private var handledStart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        scope.launch {
            combine(AppGraph.voice.state, AppGraph.playback.state) { v, p ->
                NotificationModel(v.mode, v.micSilenced, p.status, p.alias, p.looping, p.interrupted)
            }.distinctUntilChanged().collect { refresh(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_PLAYBACK -> goForeground(isListening())
            ACTION_STOP_PLAYBACK -> {
                goForeground(isListening())
                AppGraph.processor.execute(VoiceCommand.Stop(), null, CommandSource.TAP)
            }
            ACTION_TOGGLE_PAUSE -> {
                goForeground(isListening())
                val cmd = if (AppGraph.playback.state.value.status == PlayerStatus.PLAYING) VoiceCommand.Pause
                else VoiceCommand.Resume
                AppGraph.processor.execute(cmd, null, CommandSource.TAP)
            }
            else -> {
                // Restarted by the system without an intent: a microphone service may not be restarted
                // from the background, so just satisfy the foreground contract and stop.
                goForeground(false)
                stopSelfIfIdle(force = true)
                return START_NOT_STICKY
            }
        }
        handledStart = true
        refresh(null)
        return START_NOT_STICKY
    }

    private fun startListening() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || !goForeground(withMic = true)) {
            goForeground(withMic = false)
            AppGraph.settings.setWasListening(false)
            return
        }
        AppGraph.voice.start()
        AppGraph.settings.setWasListening(true)
        if (AppGraph.settings.settings.value.keepCpuAwake) acquireWakeLock()
    }

    private fun stopListening() {
        AppGraph.voice.stop()
        AppGraph.settings.setWasListening(false)
        releaseWakeLock()
        goForeground(false)
    }

    private fun isListening() = AppGraph.voice.state.value.mode != ListeningMode.OFF

    /** @return false if the system refused the requested foreground service type. */
    private fun goForeground(withMic: Boolean): Boolean {
        val types = if (withMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), types)
            currentTypes = types
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(types=$types) refused", e)
            false
        }
    }

    private fun refresh(model: NotificationModel?) {
        if (!handledStart) return
        val listening = isListening()
        if (stopSelfIfIdle(force = false)) return
        val wantedMic = listening
        val hasMic = currentTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        if (wantedMic != hasMic) goForeground(wantedMic)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(model))
    }

    private fun stopSelfIfIdle(force: Boolean): Boolean {
        val status = AppGraph.playback.state.value.status
        val playbackActive = status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED
        if (force || (!isListening() && !playbackActive)) {
            isRunning = false // so a play request arriving now starts a fresh instance
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return true
        }
        return false
    }

    override fun onDestroy() {
        isRunning = false
        AppGraph.voice.stop()
        releaseWakeLock()
        val status = AppGraph.playback.state.value.status
        // If the user started a track while this instance was shutting down, a new instance owns it.
        if (status != PlayerStatus.PLAYING && status != PlayerStatus.PAUSED) AppGraph.playback.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asterix:listening")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private data class NotificationModel(
        val mode: ListeningMode, val micSilenced: Boolean, val status: PlayerStatus,
        val alias: String?, val looping: Boolean, val interrupted: Boolean,
    )

    private fun buildNotification(model: NotificationModel?): Notification {
        val v = AppGraph.voice.state.value
        val p = AppGraph.playback.state.value
        val m = model ?: NotificationModel(v.mode, v.micSilenced, p.status, p.alias, p.looping, p.interrupted)
        val listening = m.mode != ListeningMode.OFF

        val title = if (listening) "🎤 Microphone active — listening for commands" else "Asterix playback"
        val micLine = when {
            !listening -> null
            m.micSilenced -> "Microphone in use by another app"
            m.mode == ListeningMode.PREPARING -> "Loading speech model…"
            m.mode == ListeningMode.ERROR -> "Recognizer error — retrying"
            else -> null
        }
        val playLine = when (m.status) {
            PlayerStatus.PLAYING -> "▶ ${m.alias}${if (m.looping) " (loop)" else ""}${if (m.interrupted) " — interrupted" else ""}"
            PlayerStatus.PAUSED -> "⏸ ${m.alias} paused"
            else -> "Nothing playing"
        }
        val text = listOfNotNull(micLine, playLine).joinToString(" · ")

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (listening) R.drawable.ic_mic else R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (m.status == PlayerStatus.PLAYING || m.status == PlayerStatus.PAUSED) {
                    addAction(0, if (m.status == PlayerStatus.PLAYING) "Pause" else "Resume", action(ACTION_TOGGLE_PAUSE, 2))
                    addAction(0, "Stop audio", action(ACTION_STOP_PLAYBACK, 3))
                }
                if (listening) addAction(0, "Stop listening", action(ACTION_STOP_LISTENING, 1))
            }
            .build()
    }

    private fun action(name: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, AsterixService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Listening & playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the microphone is listening or audio is playing"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AsterixService"
        private const val CHANNEL_ID = "asterix_service"
        private const val NOTIFICATION_ID = 42
        private const val PREFIX = "com.asterix.voiceplayer.action."
        const val ACTION_START_LISTENING = PREFIX + "START_LISTENING"
        const val ACTION_STOP_LISTENING = PREFIX + "STOP_LISTENING"
        const val ACTION_PLAYBACK = PREFIX + "PLAYBACK"
        const val ACTION_STOP_PLAYBACK = PREFIX + "STOP_PLAYBACK"
        const val ACTION_TOGGLE_PAUSE = PREFIX + "TOGGLE_PAUSE"

        @Volatile var isRunning = false
            private set

        private fun intent(ctx: Context, action: String) = Intent(ctx, AsterixService::class.java).setAction(action)

        /** Must be called from a user action while the activity is visible. */
        fun startListening(ctx: Context) = ContextCompat.startForegroundService(ctx, intent(ctx, ACTION_START_LISTENING))

        fun stopListening(ctx: Context) {
            if (isRunning) ctx.startService(intent(ctx, ACTION_STOP_LISTENING))
        }

        fun ensureRunningForPlayback(ctx: Context) {
            if (isRunning) return
            try {
                ContextCompat.startForegroundService(ctx, intent(ctx, ACTION_PLAYBACK))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start playback service", e)
            }
        }
    }
}
