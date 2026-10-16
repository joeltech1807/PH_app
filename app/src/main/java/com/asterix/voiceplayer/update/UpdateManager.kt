package com.asterix.voiceplayer.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.asterix.voiceplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Manual, user-triggered updates from the GitHub Releases of [BuildConfig.UPDATE_REPO].
 * This is the app's only network access; voice recognition and playback never use the network.
 * Android itself rejects an APK that is not signed with the same key as the installed app.
 */
class UpdateManager(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = fetchLatest()
            if (compareVersions(release.version, currentVersion.substringBefore('-')) > 0) UpdateState.Available(release)
            else UpdateState.UpToDate(release.version)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Downloads and hands the APK to the system installer. Caller must ensure install permission first. */
    suspend fun downloadAndInstall(release: ReleaseInfo) {
        try {
            val apk = download(release)
            _state.value = UpdateState.Installing
            install(apk)
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun reportInstallResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("Update cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Update was signed with a different key or is incompatible ($message)")
            else -> UpdateState.Failed("Install failed: ${message ?: "status $status"}")
        }
    }

    private suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("no releases published yet")
                else -> throw IOException("GitHub returned HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") && !it.getString("name").contains("debug") }
                ?: throw IOException("the latest release has no APK attached")
            ReleaseInfo(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "update.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection // follows GitHub's redirect
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("download returned HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastReported) {
                            lastReported = pct
                            _state.value = UpdateState.Downloading(release, pct / 100f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // After the app has installed itself once, Android 12+ can apply later updates without a prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"

        /** Numeric comparison of dotted versions: "1.10.0" > "1.9.2". */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/** Receives PackageInstaller status; launches the system confirmation screen when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        com.asterix.voiceplayer.AppGraph.updates.reportInstallResult(
            status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
