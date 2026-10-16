package com.asterix.voiceplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.asterix.voiceplayer.AppGraph
import com.asterix.voiceplayer.CommandSource
import com.asterix.voiceplayer.R
import com.asterix.voiceplayer.command.PlayMode
import com.asterix.voiceplayer.command.PlayerStatus
import com.asterix.voiceplayer.command.VoiceCommand
import com.asterix.voiceplayer.voice.ListeningMode

@Composable
fun HomeScreen(
    modifier: Modifier,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val voice by AppGraph.voice.state.collectAsStateWithLifecycle()
    val playback by AppGraph.playback.state.collectAsStateWithLifecycle()
    val log by AppGraph.processor.log.collectAsStateWithLifecycle()
    val entries by AppGraph.library.entries.collectAsStateWithLifecycle()
    val wasListening by AppGraph.settings.wasListening.collectAsStateWithLifecycle()
    val listening = voice.mode != ListeningMode.OFF

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Master Voice", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Offline voice-controlled audio player", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (wasListening && !listening) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Listening was stopped by the system", fontWeight = FontWeight.SemiBold)
                        Text("Android closed the listening service (battery saving or memory). " +
                            "See Settings for battery tips.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onStartListening) { Text("Restart listening") }
                    }
                }
            }
        }

        item { ListeningCard(voice, listening, onStartListening, onStopListening) }
        item { NowPlayingCard(playback) }
        item { LastCommandCard(log) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quick library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenLibrary) { Text("Manage") }
            }
        }
        if (entries.isEmpty()) {
            item {
                OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text("Import your first audio file")
                }
            }
        }
        items(entries, key = { it.id }) { e ->
            val isCurrent = playback.entryId == e.id
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.alias, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!e.fileExists) "File missing" else if (isCurrent) statusLabel(playback.status, playback.looping) else formatDuration(e.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!e.fileExists) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        AppGraph.processor.execute(VoiceCommand.Play(e.aliasKey, e.alias, PlayMode.ONCE), "▶ ${e.alias}", CommandSource.TAP)
                    }) { Icon(painterResource(R.drawable.ic_play), "Play ${e.alias} once") }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalIconButton(onClick = {
                        AppGraph.processor.execute(VoiceCommand.Play(e.aliasKey, e.alias, PlayMode.LOOP), "🔁 ${e.alias}", CommandSource.TAP)
                    }) { Icon(painterResource(R.drawable.ic_repeat), "Loop ${e.alias}") }
                }
            }
        }
    }
}

@Composable
private fun ListeningCard(
    voice: com.asterix.voiceplayer.voice.ListeningState,
    listening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.error
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = { if (listening) onStop() else onStart() },
                modifier = Modifier.size(120.dp).semantics {
                    contentDescription = if (listening) "Stop listening" else "Start listening"
                },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) active else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(painterResource(if (listening) R.drawable.ic_mic else R.drawable.ic_mic_off), null, Modifier.size(56.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    when {
                        voice.micSilenced -> Color(0xFFFFA000)
                        voice.mode == ListeningMode.LISTENING -> active
                        voice.mode == ListeningMode.ERROR -> Color(0xFFFFA000)
                        voice.mode == ListeningMode.PREPARING -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    }
                ))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (voice.mode) {
                        ListeningMode.OFF -> "Not listening — tap to start"
                        ListeningMode.PREPARING -> "Preparing…"
                        ListeningMode.LISTENING -> "Microphone active — listening"
                        ListeningMode.ERROR -> "Recovering from an error"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            voice.detail?.takeIf { listening }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (voice.micSilenced) {
                Text("Another app is using the microphone. Commands can't be heard until it stops.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (listening && voice.partial.isNotBlank()) {
                Text("Hearing: “${voice.partial}”", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp))
            }
            if (!listening) {
                Text("Say: “play song1”, “play song1 in loop”, “pause”, “stop”",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun NowPlayingCard(p: com.asterix.voiceplayer.playback.PlaybackState) {
    val active = p.status == PlayerStatus.PLAYING || p.status == PlayerStatus.PAUSED
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (active) p.alias.orEmpty() else "Nothing", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                AssistChip(onClick = {}, label = { Text(statusLabel(p.status, false)) }, leadingIcon = {
                    Icon(painterResource(when (p.status) {
                        PlayerStatus.PLAYING -> R.drawable.ic_play
                        PlayerStatus.PAUSED -> R.drawable.ic_pause
                        else -> R.drawable.ic_stop
                    }), null, Modifier.size(18.dp))
                })
                if (active) {
                    AssistChip(onClick = {}, label = { Text(if (p.looping) "Looping" else "Once") }, leadingIcon = {
                        Icon(painterResource(if (p.looping) R.drawable.ic_repeat_one else R.drawable.ic_play), null, Modifier.size(18.dp))
                    })
                }
                if (p.interrupted) AssistChip(onClick = {}, label = { Text("Interrupted") })
            }
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = {
                        AppGraph.processor.execute(
                            if (p.status == PlayerStatus.PLAYING) VoiceCommand.Pause else VoiceCommand.Resume, null, CommandSource.TAP)
                    }) {
                        Icon(painterResource(if (p.status == PlayerStatus.PLAYING) R.drawable.ic_pause else R.drawable.ic_play),
                            if (p.status == PlayerStatus.PLAYING) "Pause" else "Resume")
                    }
                    FilledTonalIconButton(onClick = {
                        AppGraph.processor.execute(VoiceCommand.Stop(), null, CommandSource.TAP)
                    }) { Icon(painterResource(R.drawable.ic_stop), "Stop") }
                    FilledTonalIconButton(onClick = {
                        AppGraph.processor.execute(VoiceCommand.SetLooping(!p.looping), null, CommandSource.TAP)
                    }) {
                        Icon(painterResource(if (p.looping) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                            if (p.looping) "Turn loop off" else "Turn loop on")
                    }
                }
            }
        }
    }
}

@Composable
private fun LastCommandCard(log: com.asterix.voiceplayer.CommandLog) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Last command", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (log.result == null) {
                Text("No commands yet", style = MaterialTheme.typography.bodyMedium)
            } else {
                log.heard?.let {
                    Text(if (log.source == CommandSource.VOICE) "Heard: “$it”" else "Tapped: $it",
                        style = MaterialTheme.typography.bodyLarge)
                }
                Text("Command: ${log.interpreted}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (log.success) "✓ " else "✗ ") + log.result,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (log.success) Color(0xFF2E9D57) else MaterialTheme.colorScheme.error,
                )
            }
            log.ignoredHeard?.let {
                Text("Last ignored speech: “$it”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

fun statusLabel(status: PlayerStatus, looping: Boolean): String = when (status) {
    PlayerStatus.PLAYING -> if (looping) "Playing (loop)" else "Playing"
    PlayerStatus.PAUSED -> "Paused"
    PlayerStatus.ENDED -> "Finished"
    PlayerStatus.ERROR -> "Error"
    PlayerStatus.IDLE -> "Stopped"
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
