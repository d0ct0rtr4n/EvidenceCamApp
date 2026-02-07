package com.evidencecam.app.ui.gallery

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.evidencecam.app.R
import com.evidencecam.app.databinding.ActivityGalleryBinding
import com.evidencecam.app.model.VideoRecording
import com.evidencecam.app.viewmodel.GalleryViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

@AndroidEntryPoint
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: VideoRecordingAdapter

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (adapter.isSelectionMode) {
                adapter.clearSelection()
                updateSelectionUI(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Recordings"

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        setupRecyclerView()
        setupBulkActionBar()
        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (adapter.isSelectionMode) {
            adapter.clearSelection()
            updateSelectionUI(0)
            return false
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gallery, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_all -> {
                exportAll()
                true
            }
            R.id.action_share_all -> {
                shareAll()
                true
            }
            R.id.action_delete_all -> {
                confirmDeleteAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = VideoRecordingAdapter(
            onItemClick = { _ -> /* clicks now enter selection mode in adapter */ },
            onItemLongClick = { _ -> updateSelectionUI(1) },
            onSelectionChanged = { count -> updateSelectionUI(count) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@GalleryActivity)
            adapter = this@GalleryActivity.adapter
        }
    }

    private fun setupBulkActionBar() {
        binding.btnCancelSelection.setOnClickListener {
            adapter.clearSelection()
            updateSelectionUI(0)
        }

        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        binding.btnBulkExport.setOnClickListener {
            bulkExport(adapter.getSelectedRecordings())
        }

        binding.btnBulkShare.setOnClickListener {
            bulkShare(adapter.getSelectedRecordings())
        }

        binding.btnBulkDelete.setOnClickListener {
            confirmBulkDelete(adapter.getSelectedRecordings())
        }
    }

    private fun updateSelectionUI(count: Int) {
        val isSelecting = count > 0
        binding.selectionActionBar.visibility = if (isSelecting) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text = "$count selected"
        backPressedCallback.isEnabled = isSelecting

        supportActionBar?.title = if (isSelecting) "$count selected" else "Recordings"
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recordings.collectLatest { recordings ->
                        adapter.submitList(recordings)
                        binding.emptyState.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerView.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.toastMessage.collectLatest { event ->
                        event.getContentIfNotHandled()?.let {
                            Toast.makeText(this@GalleryActivity, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun exportToPublicStorage(recording: VideoRecording) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val success = withContext(Dispatchers.IO) {
                    exportVideoToGallery(recording)
                }
                binding.progressBar.visibility = View.GONE

                if (success) {
                    Toast.makeText(
                        this@GalleryActivity,
                        "Exported to Movies/EvidenceCam - visible in Files and Google Photos",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@GalleryActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@GalleryActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bulkExport(recordings: List<VideoRecording>) {
        if (recordings.isEmpty()) return

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            var successCount = 0
            var failCount = 0

            withContext(Dispatchers.IO) {
                for (recording in recordings) {
                    if (exportVideoToGallery(recording)) {
                        successCount++
                    } else {
                        failCount++
                    }
                }
            }

            binding.progressBar.visibility = View.GONE
            adapter.clearSelection()
            updateSelectionUI(0)

            val message = if (failCount == 0) {
                "Exported $successCount files to Movies/EvidenceCam"
            } else {
                "Exported $successCount files, $failCount failed"
            }
            Toast.makeText(this@GalleryActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun exportVideoToGallery(recording: VideoRecording): Boolean {
        val sourceFile = File(recording.filePath)
        if (!sourceFile.exists()) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, recording.fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EvidenceCam")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DURATION, recording.durationMs)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        } else {
            // Android 9 and below - Copy to public Movies folder
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "EvidenceCam"
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()

            val destFile = File(moviesDir, recording.fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            // Notify media scanner using modern API
            MediaScannerConnection.scanFile(
                this@GalleryActivity,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )

            true
        }
    }

    private fun shareVideo(recording: VideoRecording) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private fun bulkShare(recordings: List<VideoRecording>) {
        if (recordings.isEmpty()) return

        val uris = ArrayList<Uri>()
        for (recording in recordings) {
            val file = File(recording.filePath)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                uris.add(uri)
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No files found", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        adapter.clearSelection()
        updateSelectionUI(0)
        startActivity(Intent.createChooser(intent, "Share ${uris.size} recordings"))
    }

    private fun confirmDelete(recording: VideoRecording) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording?")
            .setMessage("This will permanently delete \"${recording.fileName}\"")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteRecording(recording)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmBulkDelete(recordings: List<VideoRecording>) {
        if (recordings.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Delete ${recordings.size} Recordings?")
            .setMessage("This will permanently delete all selected recordings. This action cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch {
                    for (recording in recordings) {
                        viewModel.deleteRecording(recording)
                    }
                    adapter.clearSelection()
                    updateSelectionUI(0)
                    Toast.makeText(
                        this@GalleryActivity,
                        "Deleted ${recordings.size} recordings",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportAll() {
        val recordings = viewModel.recordings.value
        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings to export", Toast.LENGTH_SHORT).show()
            return
        }
        bulkExport(recordings)
    }

    private fun shareAll() {
        val recordings = viewModel.recordings.value
        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings to share", Toast.LENGTH_SHORT).show()
            return
        }
        bulkShare(recordings)
    }

    private fun confirmDeleteAll() {
        val recordingCount = viewModel.recordings.value.size
        if (recordingCount == 0) {
            Toast.makeText(this, "No recordings to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete All Recordings?")
            .setMessage("This will permanently delete all $recordingCount recordings. This action cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                binding.progressBar.visibility = View.VISIBLE
                viewModel.deleteAllRecordings { count ->
                    binding.progressBar.visibility = View.GONE
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
