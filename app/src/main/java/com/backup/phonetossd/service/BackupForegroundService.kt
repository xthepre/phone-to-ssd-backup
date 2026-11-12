package com.backup.phonetossd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.backup.phonetossd.MainActivity
import com.backup.phonetossd.engine.BackupEngine
import com.backup.phonetossd.ledger.LedgerDatabase
import com.backup.phonetossd.model.BackupStats
import com.backup.phonetossd.model.TargetSpec
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class BackupForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phone_to_ssd_backup_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.backup.phonetossd.START"
        const val ACTION_PAUSE = "com.backup.phonetossd.PAUSE"
        const val ACTION_RESUME = "com.backup.phonetossd.RESUME"
        const val ACTION_CANCEL = "com.backup.phonetossd.CANCEL"

        var isRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: BackupEngine? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BackupForegroundService = this@BackupForegroundService
    }

    var serviceListener: BackupEngine.BackupListener? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneToSsd:BackupWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                engine?.pause()
                updateNotification("⏸ Backup Paused", 0, 0, true)
            }
            ACTION_RESUME -> {
                engine?.resume()
                updateNotification("⚡ Backup Running", 0, 0, false)
            }
            ACTION_CANCEL -> {
                engine?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun startBackup(
        sourceRoot: File,
        destRoot: File,
        targets: List<TargetSpec>,
        ledger: LedgerDatabase,
        filterMode: String,
        customExtensions: Set<String>
    ) {
        if (isRunning) return
        isRunning = true
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard

        startForeground(NOTIFICATION_ID, createNotification("Starting Phone to SSD Backup...", 0, 0, false))

        thread(name = "BackupWorkerThread") {
            engine = BackupEngine(
                sourceRoot = sourceRoot,
                destRoot = destRoot,
                targets = targets,
                ledger = ledger,
                filterMode = filterMode,
                customExtensions = customExtensions,
                listener = object : BackupEngine.BackupListener {
                    override fun onStatus(message: String) {
                        serviceListener?.onStatus(message)
                    }

                    override fun onFileStarted(relPath: String, size: Long) {
                        serviceListener?.onFileStarted(relPath, size)
                    }

                    override fun onFileCompleted(relPath: String, size: Long, skipped: Boolean) {
                        serviceListener?.onFileCompleted(relPath, size, skipped)
                    }

                    override fun onProgress(stats: BackupStats) {
                        serviceListener?.onProgress(stats)
                        val title = String.format(
                            Locale.US,
                            "%.1f MB/s | %d/%d files",
                            stats.speedMbps,
                            stats.completedFiles,
                            stats.totalFiles
                        )
                        updateNotification(title, stats.completedBytes.toInt(), stats.totalBytes.toInt(), false)
                    }

                    override fun onFinished(stats: BackupStats) {
                        isRunning = false
                        ledger.mirrorToLocalDownloads(applicationContext)
                        serviceListener?.onFinished(stats)
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    override fun onError(error: String) {
                        isRunning = false
                        serviceListener?.onError(error)
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            )

            engine?.execute()
        }
    }

    fun pauseBackup() {
        engine?.pause()
    }

    fun resumeBackup() {
        engine?.resume()
    }

    fun cancelBackup() {
        engine?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone to SSD Backup Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time transfer progress and controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int, max: Int, isPaused: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, BackupForegroundService::class.java).apply { action = if (isPaused) ACTION_RESUME else ACTION_PAUSE }
        val pendingPause = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, BackupForegroundService::class.java).apply { action = ACTION_CANCEL }
        val pendingCancel = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ Phone to SSD Backup")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pendingPause
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(content: String, progress: Int, max: Int, isPaused: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content, progress, max, isPaused))
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        isRunning = false
        super.onDestroy()
    }
}
