package com.backup.phonetossd.engine

import android.util.Log
import com.backup.phonetossd.ledger.LedgerDatabase
import com.backup.phonetossd.model.BackupStats
import com.backup.phonetossd.model.FileEntry
import com.backup.phonetossd.model.TargetSpec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BackupEngine(
    private val sourceRoot: File,
    private val destRoot: File,
    private val targets: List<TargetSpec>,
    private val ledger: LedgerDatabase,
    private val filterMode: String = "all", // "all", "media", "custom"
    private val customExtensions: Set<String> = emptySet(),
    private val listener: BackupListener
) {

    interface BackupListener {
        fun onStatus(message: String)
        fun onFileStarted(relPath: String, size: Long)
        fun onFileCompleted(relPath: String, size: Long, skipped: Boolean)
        fun onProgress(stats: BackupStats)
        fun onFinished(stats: BackupStats)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "BackupEngine"
        private const val BUFFER_SIZE = 1024 * 1024 // 1 MB high-throughput buffer for USB 3.2

        val MEDIA_EXTENSIONS = setOf(
            ".jpg", ".jpeg", ".png", ".heic", ".heif", ".dng", ".webp",
            ".mp4", ".mov", ".mkv", ".avi", ".3gp", ".m4v", ".wmv",
            ".gif", ".raw", ".cr2", ".nef", ".arw", ".bmp", ".tif"
        )
    }

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        synchronized(isPaused) {
            (isPaused as Object).notifyAll()
        }
    }

    fun cancel() {
        isCancelled.set(true)
        resume()
    }

    private fun matchesFilter(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase().let { if (it.isNotEmpty()) ".$it" else "" }
        return when (filterMode) {
            "media" -> ext in MEDIA_EXTENSIONS
            "custom" -> ext in customExtensions
            else -> true
        }
    }

    fun execute() {
        try {
            val stats = BackupStats()
            listener.onStatus("Scanning source directories...")

            val pendingQueue = mutableListOf<FileEntry>()
            var scannedFiles = 0
            var scannedBytes = 0L

            // 1. Scan Phase
            for (target in targets) {
                if (isCancelled.get()) break

                val targetDir = if (target.relPath.isEmpty()) sourceRoot else File(sourceRoot, target.relPath)
                if (!targetDir.exists()) continue

                listener.onStatus("Scanning ${target.relPath} (recursive=${target.recursive})...")

                if (!target.recursive) {
                    // Option 2: Direct loose files only
                    val directFiles = targetDir.listFiles { f -> f.isFile } ?: emptyArray()
                    for (file in directFiles) {
                        if (!matchesFilter(file.name)) continue
                        val relPath = file.relativeTo(sourceRoot).path.replace('\\', '/')
                        val size = file.length()
                        scannedFiles++
                        scannedBytes += size

                        if (ledger.isAlreadyCompleted(relPath, size)) {
                            stats.skippedFiles++
                            listener.onFileCompleted(relPath, size, true)
                        } else {
                            pendingQueue.add(FileEntry(file, relPath, size, file.lastModified()))
                            stats.totalBytes += size
                        }
                    }
                } else {
                    // Recursive scan
                    targetDir.walkTopDown().forEach { file ->
                        if (isCancelled.get()) return@forEach
                        if (file.isFile) {
                            if (!matchesFilter(file.name)) return@forEach
                            val relPath = file.relativeTo(sourceRoot).path.replace('\\', '/')
                            val size = file.length()
                            scannedFiles++
                            scannedBytes += size

                            if (ledger.isAlreadyCompleted(relPath, size)) {
                                stats.skippedFiles++
                                listener.onFileCompleted(relPath, size, true)
                            } else {
                                pendingQueue.add(FileEntry(file, relPath, size, file.lastModified()))
                                stats.totalBytes += size
                            }
                        }
                    }
                }
            }

            stats.totalFiles = pendingQueue.size + stats.skippedFiles
            listener.onStatus("Scan complete. ${pendingQueue.size} files to transfer, ${stats.skippedFiles} skipped via Smart Ledger.")

            if (pendingQueue.isEmpty()) {
                listener.onFinished(stats)
                return
            }

            // 2. Transfer Phase
            val startTime = System.currentTimeMillis()
            var bytesTransferredSinceStart = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            for (entry in pendingQueue) {
                if (isCancelled.get()) {
                    listener.onStatus("Backup cancelled by user.")
                    return
                }

                while (isPaused.get()) {
                    listener.onStatus("Backup paused.")
                    synchronized(isPaused) {
                        try {
                            (isPaused as Object).wait()
                        } catch (e: InterruptedException) {
                            // break
                        }
                    }
                }

                stats.currentFile = entry.relPath
                listener.onFileStarted(entry.relPath, entry.size)

                val destFile = File(destRoot, entry.relPath)
                val parentDir = destFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                val tempFile = File(destFile.parentFile, "${destFile.name}.tmp_${System.currentTimeMillis()}")

                var fileCopiedBytes = 0L
                try {
                    FileInputStream(entry.sourceFile).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (isCancelled.get()) {
                                    tempFile.delete()
                                    return
                                }
                                while (isPaused.get()) {
                                    synchronized(isPaused) { (isPaused as Object).wait() }
                                }
                                output.write(buffer, 0, read)
                                fileCopiedBytes += read
                                bytesTransferredSinceStart += read
                                stats.completedBytes += read

                                // Calculate Telemetry
                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                if (elapsedSec > 0.5) {
                                    val mbps = (bytesTransferredSinceStart / (1024.0 * 1024.0)) / elapsedSec
                                    stats.speedMbps = mbps
                                    val remainingBytes = stats.totalBytes - stats.completedBytes
                                    stats.etaSeconds = if (mbps > 0.05) ((remainingBytes / (1024.0 * 1024.0)) / mbps).toLong() else 0L
                                }
                                listener.onProgress(stats)
                            }
                            output.flush()
                        }
                    }

                    // Atomic rename
                    if (destFile.exists()) destFile.delete()
                    if (tempFile.renameTo(destFile)) {
                        val mtimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.mtime))
                        ledger.recordSuccess(entry.relPath, entry.size, mtimeStr)
                        stats.completedFiles++
                        listener.onFileCompleted(entry.relPath, entry.size, false)
                    } else {
                        tempFile.delete()
                        Log.e(TAG, "Failed to rename temp file to ${destFile.name}")
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    Log.e(TAG, "Error copying ${entry.relPath}: ${e.message}", e)
                }

                listener.onProgress(stats)
            }

            // Sync ledger copy to Downloads
            listener.onStatus("Backup completed successfully! Mirroring ledger...")
            listener.onFinished(stats)

        } catch (e: Exception) {
            Log.e(TAG, "Fatal backup engine error: ${e.message}", e)
            listener.onError("Error: ${e.message}")
        }
    }
}
