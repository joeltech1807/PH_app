package com.asterix.voiceplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.asterix.voiceplayer.AppGraph
import com.asterix.voiceplayer.BuildConfig
import com.asterix.voiceplayer.update.UpdateState
import kotlinx.coroutines.launch

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(modifier: Modifier, onOpenAppSettings: () -> Unit) {
    val s by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("Recognition") {
            Toggle("Restricted vocabulary", "Only listen for command words and your audio names. More accurate, fewer false triggers.",
                s.restrictedVocabulary) { v -> AppGraph.settings.update { it.copy(restrictedVocabulary = v) } }
            Toggle("Echo cancellation", "Use the phone's echo canceller so playing audio is less likely to be heard as a command.",
                s.echoCancellation) { v -> AppGraph.settings.update { it.copy(echoCancellation = v) } }
            Toggle("Battery saver (silence gate)", "Skip speech processing while the room is quiet.",
                s.silenceGate) { v -> AppGraph.settings.update { it.copy(silenceGate = v) } }
            Toggle("Keep listening with screen off", "Hold a CPU wake lock while listening. Recommended; uses more battery.",
                s.keepCpuAwake) { v -> AppGraph.settings.update { it.copy(keepCpuAwake = v) } }
            Text("Minimum confidence while audio plays: ${"%.2f".format(s.minConfidenceWhilePlaying)}",
                style = MaterialTheme.typography.bodyMedium)
            Slider(value = s.minConfidenceWhilePlaying, valueRange = 0f..0.95f, steps = 18,
                onValueChange = { v -> AppGraph.settings.update { it.copy(minConfidenceWhilePlaying = v) } })
            Text("Higher values ignore more unclear speech (and lyrics) during playback.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Changes apply within a second while listening.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Feedback") {
            Toggle("Speak errors", "Say messages such as “song3 was not found” using offline text-to-speech.",
                s.speakErrors) { v -> AppGraph.settings.update { it.copy(speakErrors = v) } }
        }

        Section("Background reliability") {
            Text(if (unrestricted) "✓ Battery optimisation is off for Master Voice." else "Battery optimisation is on. Android may stop listening in the background.",
                style = MaterialTheme.typography.bodyMedium)
            if (!unrestricted) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Allow unrestricted battery use") }
            }
            OutlinedButton(onClick = onOpenAppSettings) { Text("Open app permissions & battery settings") }
            Text(MANUFACTURER_TIPS, style = MaterialTheme.typography.bodySmall)
            Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        UpdateSection()

        Section("Commands") {
            Text(COMMAND_HELP, style = MaterialTheme.typography.bodySmall)
        }

        Section("About") {
            Text("Master Voice ${BuildConfig.VERSION_NAME}. Speech recognition runs entirely on the phone (Vosk, small English model). " +
                "No audio or text is sent to the internet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateSection() {
    val updates = AppGraph.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Section("App updates") {
        Text("Installed version: ${updates.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        when (val st = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall)
            is UpdateState.UpToDate -> Text("✓ Up to date (latest release: ${st.latest})", style = MaterialTheme.typography.bodySmall)
            is UpdateState.Available -> {
                Text("Version ${st.release.version} is available (${st.release.apkSize / (1024 * 1024)} MB)",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (st.release.notes.isNotBlank()) Text(st.release.notes.take(500), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    if (!updates.canInstall()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")))
                    } else {
                        scope.launch { updates.downloadAndInstall(st.release) }
                    }
                }) { Text(if (updates.canInstall()) "Download & install" else "Allow installing updates") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${st.release.version}… ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            UpdateState.Installing -> Text("Installing… the app will restart.", style = MaterialTheme.typography.bodySmall)
            is UpdateState.Failed -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing) {
            OutlinedButton(onClick = { scope.launch { updates.check() } }) { Text("Check for updates") }
        }
        Text("Downloads new versions from github.com/${BuildConfig.UPDATE_REPO}/releases. This is the only time the app uses the internet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val MANUFACTURER_TIPS = """
Android can still stop background listening. To reduce that:
• All phones: keep the Master Voice notification visible; don't swipe the app away from recents on phones that kill on swipe.
• Samsung: Settings → Battery → Background usage limits → remove Master Voice from “Sleeping/Deep sleeping apps”; set App battery to “Unrestricted”.
• Xiaomi / Redmi / POCO (MIUI/HyperOS): enable Autostart; Battery saver → “No restrictions”; lock the app in recents.
• OnePlus / OPPO / realme (ColorOS/OxygenOS): Battery → “Allow background activity”; disable “Optimise battery use”.
• Huawei / Honor: App launch → Master Voice → Manage manually, allow all three switches.
• Vivo: Background power consumption → allow high background power usage.
More: dontkillmyapp.com
""".trimIndent()

private val COMMAND_HELP = """
play song1 · play song1 once · play song1 one time — play once (default)
play song1 in loop · loop song1 · repeat alarm1 · play song2 continuously — loop
stop · stop playback · stop song1 — stop now
pause · resume · continue playing — pause / continue a paused track
play again — resume if paused, otherwise restart the last track
loop this song · stop looping · play only once — change loop mode of the current track
A new play command always interrupts the current audio immediately.
Names can be said with words or digits: “song one” = “song1”.
""".trimIndent()
