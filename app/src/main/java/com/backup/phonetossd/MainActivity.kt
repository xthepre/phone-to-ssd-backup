package com.backup.phonetossd

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.backup.phonetossd.databinding.ActivityMainBinding
import com.backup.phonetossd.engine.BackupEngine
import com.backup.phonetossd.ledger.LedgerDatabase
import com.backup.phonetossd.model.BackupStats
import com.backup.phonetossd.model.CheckState
import com.backup.phonetossd.model.TreeNode
import com.backup.phonetossd.service.BackupForegroundService
import com.backup.phonetossd.storage.StorageHelper
import com.backup.phonetossd.storage.UsbVolume
import com.backup.phonetossd.ui.TreeAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentUsbVolume: UsbVolume? = null
    private var currentLedger: LedgerDatabase? = null
    private var isFreshMode = false

    private val rootNodes = mutableListOf<TreeNode>()
    private lateinit var treeAdapter: TreeAdapter

    private var backupService: BackupForegroundService? = null
    private var isBound = false
    private var isPaused = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BackupForegroundService.LocalBinder
            backupService = binder.getService()
            isBound = true

            backupService?.serviceListener = object : BackupEngine.BackupListener {
                override fun onStatus(message: String) {
                    runOnUiThread { binding.tvCurrentFile.text = message }
                }

                override fun onFileStarted(relPath: String, size: Long) {
                    runOnUiThread { binding.tvCurrentFile.text = "Copying: $relPath" }
                }

                override fun onFileCompleted(relPath: String, size: Long, skipped: Boolean) {
                    // Update counters
                }

                override fun onProgress(stats: BackupStats) {
                    runOnUiThread { updateTelemetry(stats) }
                }

                override fun onFinished(stats: BackupStats) {
                    runOnUiThread {
                        updateTelemetry(stats)
                        binding.tvCurrentFile.text = "✅ Backup finished! Mirrored to Downloads."
                        setUiState(isRunning = false)
                        refreshSsdStatus()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        binding.tvCurrentFile.text = "⚠ $error"
                        setUiState(isRunning = false)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            backupService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, BackupForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Please grant All Files Access to backup phone data", Toast.LENGTH_LONG).show()
            } else {
                initStorageAndTree()
            }
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (readPerm != PackageManager.PERMISSION_GRANTED || writePerm != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    101
                )
            } else {
                initStorageAndTree()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            if (rootNodes.isEmpty()) {
                initStorageAndTree()
            } else {
                refreshSsdStatus()
            }
        }
    }

    private fun setupRecyclerView() {
        treeAdapter = TreeAdapter(rootNodes) { node ->
            loadSubfolders(node)
        }
        binding.rvFolderTree.layoutManager = LinearLayoutManager(this)
        binding.rvFolderTree.adapter = treeAdapter
    }

    private fun initStorageAndTree() {
        refreshSsdStatus()
        loadRootFolders()
    }

    private fun refreshSsdStatus() {
        val usbList = StorageHelper.detectUsbStorage(this)
        if (usbList.isNotEmpty()) {
            val usb = usbList.first()
            currentUsbVolume = usb
            val freeGb = usb.freeBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = usb.totalBytes / (1024.0 * 1024.0 * 1024.0)

            binding.tvSsdStatus.text = "🟢 Connected: ${usb.name}"
            binding.tvSsdStatus.setTextColor(getColor(R.color.accent_green))
            binding.tvSsdSpace.text = String.format(Locale.US, "Free: %.1f GB / %.1f GB Total | Path: %s", freeGb, totalGb, usb.path.name)

            // Check Ledger on SSD
            val backupDest = File(usb.path, "Phone_Backup")
            currentLedger = LedgerDatabase(backupDest)
            val stats = currentLedger!!.getStats()
            val completedCount = stats["completed_count"] as Int
            val completedMb = (stats["completed_bytes"] as Long) / (1024.0 * 1024.0)

            if (completedCount > 0 && !isFreshMode) {
                binding.tvLedgerBanner.text = String.format(
                    Locale.US,
                    "🟢 Smart Ledger: %d files cataloged (%.1f MB) - Zero scan skip ready",
                    completedCount, completedMb
                )
                binding.btnFreshToggle.text = "Switch to Fresh"
            } else {
                binding.tvLedgerBanner.text = "✨ Mode: Fresh Full Backup (Ledger will record newly copied files)"
                binding.btnFreshToggle.text = "Switch to Sync"
            }
            binding.btnStart.isEnabled = true
        } else {
            currentUsbVolume = null
            currentLedger = null
            binding.tvSsdStatus.text = "⚪ No USB-C SSD Detected"
            binding.tvSsdStatus.setTextColor(getColor(R.color.accent_amber))
            binding.tvSsdSpace.text = "Connect your external SSD via Type-C and tap 🔄 Refresh"
            binding.tvLedgerBanner.text = "⚪ Connect SSD to inspect Smart Ledger"
            binding.btnStart.isEnabled = false
        }
    }

    private fun loadRootFolders() {
        CoroutineScope(Dispatchers.IO).launch {
            val internalRoot = StorageHelper.getInternalStorageRoot()
            val folders = StorageHelper.listFolders(internalRoot)

            withContext(Dispatchers.Main) {
                rootNodes.clear()
                val priority = setOf("DCIM", "Pictures", "Documents", "Download", "Downloads", "WhatsApp", "Movies", "Music")

                for (folder in folders) {
                    val isHidden = folder.name.startsWith(".")
                    val defaultCheck = if (folder.name in setOf("DCIM", "Pictures")) CheckState.CHECKED else CheckState.UNCHECKED
                    val node = TreeNode(
                        name = folder.name,
                        relPath = folder.name,
                        isFolder = true,
                        isHidden = isHidden,
                        checkState = defaultCheck
                    )
                    rootNodes.add(node)
                }

                // Sort: priority first, visible folders, then hidden dot-folders
                rootNodes.sortWith(compareBy(
                    { it.name !in priority },
                    { it.isHidden },
                    { it.name.lowercase() }
                ))

                treeAdapter.rebuildVisibleList()
            }
        }
    }

    private fun loadSubfolders(parentNode: TreeNode) {
        CoroutineScope(Dispatchers.IO).launch {
            val internalRoot = StorageHelper.getInternalStorageRoot()
            val parentFile = File(internalRoot, parentNode.relPath)
            val subfolders = StorageHelper.listFolders(parentFile)

            withContext(Dispatchers.Main) {
                parentNode.children.clear()
                for (sub in subfolders) {
                    val childRelPath = "${parentNode.relPath}/${sub.name}"
                    val isHidden = sub.name.startsWith(".")
                    val childState = if (parentNode.checkState == CheckState.CHECKED) CheckState.CHECKED else CheckState.UNCHECKED
                    val childNode = TreeNode(
                        name = sub.name,
                        relPath = childRelPath,
                        isFolder = true,
                        isHidden = isHidden,
                        checkState = childState,
                        parent = parentNode
                    )
                    parentNode.children.add(childNode)
                }
                parentNode.isLoaded = true
                treeAdapter.rebuildVisibleList()
            }
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            refreshSsdStatus()
            if (rootNodes.isEmpty()) loadRootFolders()
        }

        binding.btnFreshToggle.setOnClickListener {
            isFreshMode = !isFreshMode
            refreshSsdStatus()
        }

        binding.btnPresetDcim.setOnClickListener { treeAdapter.selectPreset(setOf("DCIM")) }
        binding.btnPresetPictures.setOnClickListener { treeAdapter.selectPreset(setOf("Pictures")) }
        binding.btnPresetDocs.setOnClickListener { treeAdapter.selectPreset(setOf("Documents")) }
        binding.btnPresetAll.setOnClickListener { treeAdapter.selectAll() }
        binding.btnPresetClear.setOnClickListener { treeAdapter.clearAll() }
        binding.btnPresetExpand.setOnClickListener { treeAdapter.expandAll() }
        binding.btnPresetCollapse.setOnClickListener { treeAdapter.collapseAll() }

        binding.btnStart.setOnClickListener { startBackupProcess() }

        binding.btnPause.setOnClickListener {
            if (!isPaused) {
                backupService?.pauseBackup()
                isPaused = true
                binding.btnPause.text = "▶ RESUME"
            } else {
                backupService?.resumeBackup()
                isPaused = false
                binding.btnPause.text = "⏸ PAUSE"
            }
        }

        binding.btnCancel.setOnClickListener {
            backupService?.cancelBackup()
            setUiState(isRunning = false)
            binding.tvCurrentFile.text = "⏹ Backup Cancelled"
        }
    }

    private fun startBackupProcess() {
        val usb = currentUsbVolume
        if (usb == null) {
            Toast.makeText(this, "Please plug in an external USB SSD first", Toast.LENGTH_SHORT).show()
            return
        }

        val targets = treeAdapter.getSelectedTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, "Please select at least one folder from the tree", Toast.LENGTH_SHORT).show()
            return
        }

        val backupDest = File(usb.path, "Phone_Backup")
        if (currentLedger == null) {
            currentLedger = LedgerDatabase(backupDest)
        }

        setUiState(isRunning = true)
        binding.tvCurrentFile.text = "Starting backup session..."

        backupService?.startBackup(
            sourceRoot = StorageHelper.getInternalStorageRoot(),
            destRoot = backupDest,
            targets = targets,
            ledger = currentLedger!!,
            filterMode = "all",
            customExtensions = emptySet()
        )
    }

    private fun setUiState(isRunning: Boolean) {
        binding.btnStart.isEnabled = !isRunning
        binding.btnPause.isEnabled = isRunning
        binding.btnCancel.isEnabled = isRunning
        binding.btnRefresh.isEnabled = !isRunning
        binding.rvFolderTree.isEnabled = !isRunning
        if (!isRunning) {
            isPaused = false
            binding.btnPause.text = "⏸ PAUSE"
        }
    }

    private fun updateTelemetry(stats: BackupStats) {
        binding.tvKpiSpeed.text = String.format(Locale.US, "%.1f MB/s", stats.speedMbps)
        binding.tvKpiEta.text = if (stats.etaSeconds > 0) {
            val mins = stats.etaSeconds / 60
            val secs = stats.etaSeconds % 60
            String.format(Locale.US, "%02d:%02d", mins, secs)
        } else {
            "--:--"
        }

        val mb = stats.completedBytes / (1024.0 * 1024.0)
        binding.tvKpiTransferred.text = String.format(Locale.US, "%d files (%.1f MB)", stats.completedFiles, mb)
        binding.tvKpiSkipped.text = String.format(Locale.US, "%d files", stats.skippedFiles)

        if (stats.totalBytes > 0) {
            val progress = ((stats.completedBytes.toDouble() / stats.totalBytes) * 100).toInt()
            binding.progressBar.progress = progress
        }
    }
}
