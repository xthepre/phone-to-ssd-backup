package com.backup.phonetossd.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

data class UsbVolume(
    val name: String,
    val path: File,
    val totalBytes: Long,
    val freeBytes: Long,
    val isRemovable: Boolean
)

object StorageHelper {
    private const val TAG = "StorageHelper"

    fun getInternalStorageRoot(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun detectUsbStorage(context: Context): List<UsbVolume> {
        val list = mutableListOf<UsbVolume>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    if (vol.isRemovable) {
                        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            vol.directory
                        } else {
                            findStorageVolumePath(vol)
                        }

                        if (dir != null && dir.exists() && dir.canWrite()) {
                            val userLabel = vol.getDescription(context) ?: "USB Drive"
                            list.add(
                                UsbVolume(
                                    name = userLabel,
                                    path = dir,
                                    totalBytes = dir.totalSpace,
                                    freeBytes = dir.usableSpace,
                                    isRemovable = true
                                )
                            )
                        }
                    }
                }
            }

            // Fallback: Scan /storage directly for removable drives (e.g. /storage/XXXX-XXXX)
            if (list.isEmpty()) {
                val storageRoot = File("/storage")
                if (storageRoot.exists() && storageRoot.isDirectory) {
                    val entries = storageRoot.listFiles() ?: emptyArray()
                    for (entry in entries) {
                        val name = entry.name
                        if (name != "emulated" && name != "self" && entry.isDirectory && entry.canWrite()) {
                            list.add(
                                UsbVolume(
                                    name = "USB Drive ($name)",
                                    path = entry,
                                    totalBytes = entry.totalSpace,
                                    freeBytes = entry.usableSpace,
                                    isRemovable = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting USB storage: ${e.message}", e)
        }
        return list
    }

    private fun findStorageVolumePath(volume: Any): File? {
        return try {
            val getPathMethod = volume.javaClass.getMethod("getPath")
            val pathStr = getPathMethod.invoke(volume) as? String
            if (pathStr != null) File(pathStr) else null
        } catch (e: Exception) {
            null
        }
    }

    fun listFolders(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isDirectory }
            .sortedWith(compareBy({ !it.name.startsWith(".") }, { it.name.lowercase() }))
    }

    fun listDirectFiles(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }
    }
}
