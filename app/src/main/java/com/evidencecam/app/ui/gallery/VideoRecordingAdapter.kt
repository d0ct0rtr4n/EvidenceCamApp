package com.evidencecam.app.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.evidencecam.app.R
import com.evidencecam.app.databinding.ItemRecordingBinding
import com.evidencecam.app.model.UploadStatus
import com.evidencecam.app.model.VideoRecording
import com.evidencecam.app.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Locale

class VideoRecordingAdapter(
    private val onItemClick: (VideoRecording) -> Unit,
    private val onItemLongClick: (VideoRecording) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<VideoRecording, VideoRecordingAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val selectedIds = mutableSetOf<String>()
    var isSelectionMode = false
        private set

    fun getSelectedRecordings(): List<VideoRecording> {
        return currentList.filter { it.id in selectedIds }
    }

    fun getSelectedCount(): Int = selectedIds.size

    fun clearSelection() {
        selectedIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    private fun toggleSelection(recording: VideoRecording) {
        if (recording.id in selectedIds) {
            selectedIds.remove(recording.id)
        } else {
            selectedIds.add(recording.id)
        }

        if (selectedIds.isEmpty()) {
            isSelectionMode = false
        }

        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun startSelectionMode(recording: VideoRecording) {
        isSelectionMode = true
        selectedIds.add(recording.id)
        notifyDataSetChanged()
        onSelectionChanged(1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecordingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(recording: VideoRecording) {
            val isSelected = recording.id in selectedIds

            binding.apply {
                tvFileName.text = recording.fileName
                tvDateTime.text = dateFormat.format(recording.recordedAt)
                tvFileSize.text = FormatUtils.formatFileSize(recording.fileSize)
                tvDuration.text = FormatUtils.formatDuration(recording.durationMs)

                // Upload status indicator
                val statusText = when (recording.uploadStatus) {
                    UploadStatus.PENDING -> "Pending upload"
                    UploadStatus.UPLOADING -> "Uploading..."
                    UploadStatus.COMPLETED -> "Uploaded"
                    UploadStatus.FAILED -> "Upload failed"
                    UploadStatus.SKIPPED -> "Local only"
                }
                tvUploadStatus.text = statusText

                val statusColor = when (recording.uploadStatus) {
                    UploadStatus.COMPLETED -> R.color.upload_completed
                    UploadStatus.FAILED -> R.color.upload_failed
                    UploadStatus.UPLOADING -> R.color.upload_pending
                    else -> R.color.upload_local
                }
                tvUploadStatus.setTextColor(root.context.getColor(statusColor))

                // Selection mode UI
                checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                checkbox.isChecked = isSelected

                // Card selection visual feedback
                root.isChecked = isSelected

                // Click listeners
                root.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(recording)
                    } else {
                        onItemClick(recording)
                    }
                }

                root.setOnLongClickListener {
                    if (!isSelectionMode) {
                        startSelectionMode(recording)
                        onItemLongClick(recording)
                    }
                    true
                }

                checkbox.setOnClickListener {
                    toggleSelection(recording)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<VideoRecording>() {
        override fun areItemsTheSame(oldItem: VideoRecording, newItem: VideoRecording): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoRecording, newItem: VideoRecording): Boolean {
            return oldItem == newItem
        }
    }
}
