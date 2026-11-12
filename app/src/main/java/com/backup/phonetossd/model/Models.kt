package com.backup.phonetossd.model

import java.io.File

object CheckState {
    const val UNCHECKED = 0
    const val CHECKED = 1
    const val PARTIALLY_CHECKED = 2
}

data class TreeNode(
    val name: String,
    val relPath: String,
    val isFolder: Boolean,
    val isHidden: Boolean,
    var checkState: Int = CheckState.UNCHECKED,
    var isExpanded: Boolean = false,
    val children: MutableList<TreeNode> = mutableListOf(),
    var parent: TreeNode? = null,
    var isLoaded: Boolean = false
) {
    val level: Int
        get() {
            var lvl = 0
            var p = parent
            while (p != null) {
                lvl++
                p = p.parent
            }
            return lvl
        }
}

data class TargetSpec(
    val relPath: String,
    val recursive: Boolean
)

data class FileEntry(
    val sourceFile: File,
    val relPath: String,
    val size: Long,
    val mtime: Long
)

data class BackupStats(
    var totalFiles: Int = 0,
    var completedFiles: Int = 0,
    var skippedFiles: Int = 0,
    var totalBytes: Long = 0L,
    var completedBytes: Long = 0L,
    var speedMbps: Double = 0.0,
    var etaSeconds: Long = 0L,
    var currentFile: String = ""
)
