package com.asterix.voiceplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import com.asterix.voiceplayer.R
import com.asterix.voiceplayer.service.AsterixService

class MainActivity : ComponentActivity() {

    private var onMicPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()
        onMicPermissionResult?.invoke(granted)
        onMicPermissionResult = null
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening from this (visible, user-initiated) context, requesting permissions first if needed. */
    fun requestStartListening(onDenied: (permanently: Boolean) -> Unit) {
        val needed = buildList {
            if (!hasMicPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (hasMicPermission() && needed.isEmpty()) {
            AsterixService.startListening(this)
            return
        }
        onMicPermissionResult = { granted ->
            if (granted) AsterixService.startListening(this)
            else onDenied(!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AsterixTheme { AppRoot(this) } }
    }
}

@Composable
fun AsterixTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFFFC34D), secondary = Color(0xFF7CC4FF))
        else -> lightColorScheme(primary = Color(0xFF8A5A00), secondary = Color(0xFF00629D))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) { HOME("Home"), LIBRARY("Library"), SETTINGS("Settings") }

@Composable
private fun AppRoot(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingMessage) {
        pendingMessage?.let { snackbar.showSnackbar(it); pendingMessage = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(t.label) },
                        icon = {
                            when (t) {
                                Tab.HOME -> Icon(Icons.Filled.Home, null)
                                Tab.LIBRARY -> Icon(painterResource(R.drawable.ic_music), null)
                                Tab.SETTINGS -> Icon(Icons.Filled.Settings, null)
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.HOME -> HomeScreen(
                modifier = modifier,
                onStartListening = {
                    activity.requestStartListening { permanently ->
                        pendingMessage = if (permanently) "Microphone permission is blocked. Enable it in Settings → Apps → Master Voice → Permissions."
                        else "Microphone permission is needed to listen for commands."
                        if (permanently) activity.openAppSettings()
                    }
                },
                onStopListening = { AsterixService.stopListening(activity) },
                onOpenLibrary = { tab = Tab.LIBRARY.ordinal },
            )
            Tab.LIBRARY -> LibraryScreen(modifier, onMessage = { pendingMessage = it })
            Tab.SETTINGS -> SettingsScreen(modifier, onOpenAppSettings = activity::openAppSettings)
        }
    }
}
