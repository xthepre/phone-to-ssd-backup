package com.backup.phonetossd.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LedgerDatabase(val destDir: File) {

    companion object {
        const val DB_FILENAME = ".backup_catalog.sqlite3"
        private const val TAG = "LedgerDatabase"
    }

    private val dbFile = File(destDir, DB_FILENAME)
    private var db: SQLiteDatabase? = null
    private val completedCache = ConcurrentHashMap<String, Long>()

    init {
        openOrCreate()
    }

    private fun openOrCreate() {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            initTables()
            loadCompletedCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ledger: ${e.message}", e)
        }
    }

    private fun initTables() {
        db?.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """.trimIndent()
        )

        db?.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rel_path TEXT UNIQUE NOT NULL,
                size_bytes INTEGER NOT NULL,
                mtime TEXT,
                status TEXT NOT NULL,
                completed_at TEXT NOT NULL,
                checksum TEXT
            );
            """.trimIndent()
        )

        db?.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_records_relpath ON backup_records(rel_path);"
        )

        // Set initial creation timestamp if not set
        val cursor = db?.rawQuery("SELECT value FROM backup_metadata WHERE key = 'created_at'", null)
        val exists = cursor?.moveToFirst() == true
        cursor?.close()
        if (!exists) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            db?.execSQL("INSERT OR REPLACE INTO backup_metadata (key, value) VALUES ('created_at', '$now');")
            db?.execSQL("INSERT OR REPLACE INTO backup_metadata (key, value) VALUES ('version', '2.0');")
        }
    }

    fun loadCompletedCache() {
        completedCache.clear()
        val cursor = db?.rawQuery(
            "SELECT rel_path, size_bytes FROM backup_records WHERE status = 'COMPLETED'",
            null
        )
        cursor?.use {
            val colPath = it.getColumnIndexOrThrow("rel_path")
            val colSize = it.getColumnIndexOrThrow("size_bytes")
            while (it.moveToNext()) {
                val path = it.getString(colPath)
                val size = it.getLong(colSize)
                completedCache[path] = size
            }
        }
        Log.i(TAG, "Ledger cache loaded: ${completedCache.size} completed records.")
    }

    fun isAlreadyCompleted(relPath: String, sizeBytes: Long): Boolean {
        val cachedSize = completedCache[relPath] ?: return false
        return cachedSize == sizeBytes
    }

    fun recordSuccess(relPath: String, sizeBytes: Long, mtime: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put("rel_path", relPath)
            put("size_bytes", sizeBytes)
            put("mtime", mtime)
            put("status", "COMPLETED")
            put("completed_at", now)
        }
        db?.insertWithOnConflict(
            "backup_records",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        completedCache[relPath] = sizeBytes
    }

    fun getStats(): Map<String, Any> {
        var count = 0
        var totalBytes = 0L
        val cursor = db?.rawQuery(
            "SELECT COUNT(*), SUM(size_bytes) FROM backup_records WHERE status = 'COMPLETED'",
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                count = it.getInt(0)
                totalBytes = it.getLong(1)
            }
        }
        return mapOf(
            "completed_count" to count,
            "completed_bytes" to totalBytes,
            "db_path" to dbFile.absolutePath
        )
    }

    fun mirrorToLocalDownloads(context: Context): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val mirrorDir = File(downloadsDir, "BackupLedger")
            if (!mirrorDir.exists()) mirrorDir.mkdirs()
            val mirrorFile = File(mirrorDir, "ledger_mirror_${destDir.name}.sqlite3")

            FileInputStream(dbFile).use { input ->
                FileOutputStream(mirrorFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Successfully mirrored ledger to ${mirrorFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mirror ledger to Downloads: ${e.message}", e)
            false
        }
    }

    fun importFromLocal(sourceFile: File): Boolean {
        return try {
            close()
            sourceFile.copyTo(dbFile, overwrite = true)
            openOrCreate()
            Log.i(TAG, "Imported ledger from ${sourceFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            false
        }
    }

    fun close() {
        try {
            db?.close()
            db = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
