package io.github.uposum.romstore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.uposum.romstore.core.store.JobStatus
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * A foreground service that runs while downloads are active and shows their
 * progress. The downloads themselves live in the app's DownloadManager; this
 * only keeps the process alive and the notification current.
 */
class DownloadService : LifecycleService() {
    private val channel = "downloads"
    private val notificationId = 1
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
        }
        goForeground("Starting download…")
        val store = (application as RomStoreApp).store
        lifecycleScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            store.downloads.state.sample(800).collect { jobs ->
                val active = jobs.filter { it.active }
                if (active.isEmpty()) {
                    // Only when no start came in since the last one seen: a download that is
                    // starting right now keeps the service (its onStartCommand is on its way).
                    if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    return@collect
                }
                val received = active.sumOf { it.received }
                val total = active.sumOf { it.total }
                val extracting = active.any { it.status == JobStatus.EXTRACTING }
                val title = if (active.size == 1) active.first().title else "${active.size} downloads"
                val text = when {
                    extracting -> "Unpacking…"
                    total > 0 -> "${formatBytes(received)} of ${formatBytes(total)}"
                    else -> "Waiting…"
                }
                manager.notify(notificationId, build(title, received, total, total <= 0 || extracting, text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        // Every startForegroundService() needs a startForeground() answer within seconds,
        // also when the service is already running or has just put its notification away
        // (a download finishing as the next one starts); without it Android ends the app.
        goForeground("Downloading…")
        return START_NOT_STICKY
    }

    /**
     * Android 15+ limits dataSync foreground services (about six hours a day) and
     * ends the app if the service is still foreground when the time is up. The
     * service stops at once; the downloads themselves carry on in the app's process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopNow()
    }

    @Deprecated("Android 14's variant; Android 15+ calls the one with the service type.")
    override fun onTimeout(startId: Int) {
        stopNow()
    }

    private fun stopNow() {
        android.util.Log.w("RomStore", "download service: time limit reached, stopping it")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        runCatching {
            ServiceCompat.startForeground(
                this, notificationId, build(text, 0, 0, true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { android.util.Log.w("RomStore", "foreground service", it) }
    }

    private fun build(title: String, received: Long, total: Long, indeterminate: Boolean, text: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "downloads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val max = 1000
        val progress = if (total > 0) ((received * max) / total).toInt().coerceIn(0, max) else 0
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(max, progress, indeterminate)
            .build()
    }
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toInt()}B" else String.format("%.1f%s", value, units[index])
}
