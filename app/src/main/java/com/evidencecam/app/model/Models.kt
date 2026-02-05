package com.evidencecam.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "video_recordings")
data class VideoRecording(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val durationMs: Long,
    val recordedAt: Date,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val uploadedAt: Date? = null,
    val uploadDestination: UploadDestination? = null,
    val remoteUrl: String? = null,
    val retryCount: Int = 0,
    val lastError: String? = null
)

enum class UploadStatus {
    PENDING, UPLOADING, COMPLETED, FAILED, SKIPPED
}

enum class UploadDestination(val displayName: String) {
    LOCAL_ONLY("Local Only"),
    DROPBOX("Dropbox")
}

data class AppSettings(
    val uploadDestination: UploadDestination = UploadDestination.LOCAL_ONLY,
    val videoQuality: VideoQuality = VideoQuality.HD,
    val segmentDuration: SegmentDuration = SegmentDuration.MIN_2,
    val maxStoragePercent: Int = 90,
    val uploadOnWifiOnly: Boolean = false,
    val enableAudio: Boolean = true,
    val autoDeleteAfterUpload: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showPreview: Boolean = true,
    val autoStartRecording: Boolean = false
)

enum class SegmentDuration(val displayName: String, val seconds: Int) {
    SEC_15("15 seconds", 15),
    SEC_30("30 seconds", 30),
    MIN_1("1 minute", 60),
    MIN_2("2 minutes", 120),
    MIN_5("5 minutes", 300),
    MIN_10("10 minutes", 600)
}

enum class VideoQuality(val displayName: String, val width: Int, val height: Int, val bitrate: Int) {
    SD("SD (480p)", 854, 480, 2_000_000),
    HD("HD (720p)", 1280, 720, 5_000_000),
    FHD("Full HD (1080p)", 1920, 1080, 10_000_000)
}

data class DropboxConfig(
    val accessToken: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val accountEmail: String? = null,
    val folderPath: String = "",
    val tokenExpiresAt: Long = 0L
)


sealed class RecordingState {
    data object Idle : RecordingState()
    data object Starting : RecordingState()
    data class Recording(
        val startTime: Long,
        val currentSegment: Int,
        val currentSegmentStart: Long
    ) : RecordingState()
    data object Stopping : RecordingState()
    data class Error(val message: String) : RecordingState()
}

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val recordingsCount: Int,
    val recordingsSize: Long
) {
    val usedPercent: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) * 100f else 0f
    
    val isNearFull: Boolean
        get() = usedPercent >= 90f
}
