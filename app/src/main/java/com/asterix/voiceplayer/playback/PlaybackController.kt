package com.asterix.voiceplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.asterix.voiceplayer.command.PlayerSnapshot
import com.asterix.voiceplayer.command.PlayerStatus
import com.asterix.voiceplayer.data.AudioEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val entryId: Long? = null,
    val key: String? = null,
    val alias: String? = null,
    val looping: Boolean = false,
    /** True when playback is wanted but suppressed, e.g. during a phone call (transient audio focus loss). */
    val interrupted: Boolean = false,
    val error: String? = null,
    val lastEntryKey: String? = null,
    val lastAlias: String? = null,
    val lastLooping: Boolean = false,
) {
    fun snapshot() = PlayerSnapshot(
        status = status,
        currentKey = key.takeIf { status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED },
        currentAlias = alias,
        looping = looping,
        lastKey = lastEntryKey,
        lastAlias = lastAlias,
        lastLooping = lastLooping,
    )
}

/**
 * Single ExoPlayer instance; one track at a time. Must be used from the main thread.
 * Starting a track always stops the previous one first, so audio never overlaps.
 */
class PlaybackController(private val context: Context) {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: ExoPlayer? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            val alias = _state.value.alias
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                error = "Could not play ${alias ?: "audio"}: ${error.errorCodeName}",
            )
            // Leave the player reusable: the next start() calls setMediaItem + prepare again.
            player?.stop()
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also {
            it.addListener(listener)
            player = it
        }

    fun start(entry: AudioEntry, loop: Boolean) {
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(entry.filePath))))
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(
            entryId = entry.id, key = entry.aliasKey, alias = entry.alias, looping = loop, error = null,
            lastEntryKey = entry.aliasKey, lastAlias = entry.alias, lastLooping = loop,
        )
        p.prepare()
        p.seekTo(0)
        p.playWhenReady = true
        publish()
    }

    fun setRepeat(loop: Boolean) {
        val p = player ?: return
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(looping = loop, lastLooping = loop)
        publish()
    }

    fun pause() {
        player?.pause()
        publish()
    }

    fun resume() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        publish()
    }

    fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _state.value = _state.value.copy(status = PlayerStatus.IDLE, key = null, alias = null, entryId = null, looping = false)
    }

    /** Called if the currently loaded entry is deleted or renamed in the library. */
    fun onEntryChanged(entry: AudioEntry?, id: Long) {
        if (_state.value.entryId != id) return
        if (entry == null) stop()
        else _state.value = _state.value.copy(key = entry.aliasKey, alias = entry.alias,
            lastEntryKey = entry.aliasKey, lastAlias = entry.alias)
    }

    fun release() {
        player?.removeListener(listener)
        player?.release()
        player = null
        _state.value = _state.value.copy(status = PlayerStatus.IDLE, key = null, alias = null, entryId = null)
    }

    private fun publish() {
        val p = player ?: return
        if (_state.value.status == PlayerStatus.ERROR && p.playbackState == Player.STATE_IDLE) return
        val status = when {
            p.mediaItemCount == 0 -> PlayerStatus.IDLE
            p.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            p.playbackState == Player.STATE_IDLE -> PlayerStatus.IDLE
            p.playWhenReady -> PlayerStatus.PLAYING
            else -> PlayerStatus.PAUSED
        }
        val interrupted = p.playWhenReady && p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val s = _state.value
        _state.value = if (status == PlayerStatus.ENDED || status == PlayerStatus.IDLE) {
            s.copy(status = status, key = null, alias = null, entryId = null, interrupted = false)
        } else {
            s.copy(status = status, interrupted = interrupted, looping = p.repeatMode == Player.REPEAT_MODE_ONE)
        }
    }
}
