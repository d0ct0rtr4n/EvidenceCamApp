package com.evidencecam.app.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.evidencecam.app.model.StorageInfo
import com.evidencecam.app.model.UploadStatus
import com.evidencecam.app.model.VideoRecording
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRecordingDao: VideoRecordingDao
) {
    
    private val recordingsDir: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "EvidenceCam").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun getRecordingsDirectory(): File = recordingsDir
    
    fun getAllRecordings(): Flow<List<VideoRecording>> = videoRecordingDao.getAllRecordings()
    
    fun getRecordingsByStatus(status: UploadStatus): Flow<List<VideoRecording>> = 
        videoRecordingDao.getRecordingsByStatus(status)
    
    suspend fun getPendingUploads(limit: Int = 10): List<VideoRecording> = 
        videoRecordingDao.getPendingRecordings(UploadStatus.PENDING, limit)
    
    suspend fun getRecordingById(id: String): VideoRecording? = 
        videoRecordingDao.getRecordingById(id)
    
    suspend fun insertRecording(recording: VideoRecording) = 
        videoRecordingDao.insertRecording(recording)
    
    suspend fun updateRecording(recording: VideoRecording) = 
        videoRecordingDao.updateRecording(recording)
    
    suspend fun updateUploadStatus(id: String, status: UploadStatus, error: String? = null) =
        videoRecordingDao.updateUploadStatus(id, status, error)

    suspend fun updateUploadStatusWithRetry(id: String, status: UploadStatus, error: String? = null) =
        videoRecordingDao.updateUploadStatusWithRetry(id, status, error)

    suspend fun resetStuckUploads(): Int =
        videoRecordingDao.resetStuckUploads()

    suspend fun markAsUploaded(id: String, remoteUrl: String? = null) =
        videoRecordingDao.markAsUploaded(id, UploadStatus.COMPLETED, Date(), remoteUrl)
    
    suspend fun deleteRecording(recording: VideoRecording) {
        withContext(Dispatchers.IO) {
            File(recording.filePath).delete()
        }
        videoRecordingDao.deleteRecording(recording)
    }
    
    suspend fun deleteOldestRecording(): Boolean {
        val oldest = videoRecordingDao.getOldestRecording() ?: return false
        deleteRecording(oldest)
        return true
    }

    suspend fun deleteAllRecordings(): Int = withContext(Dispatchers.IO) {
        val recordings = videoRecordingDao.getAllRecordingsList()
        var deletedCount = 0
        for (recording in recordings) {
            File(recording.filePath).delete()
            deletedCount++
        }
        videoRecordingDao.deleteAllRecordings()
        deletedCount
    }
    
    suspend fun getStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val statFs = StatFs(recordingsDir.path)
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val usedBytes = totalBytes - availableBytes
        
        StorageInfo(
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            availableBytes = availableBytes,
            recordingsCount = videoRecordingDao.getRecordingsCount(),
            recordingsSize = videoRecordingDao.getTotalRecordingsSize() ?: 0L
        )
    }
    
    suspend fun checkAndCleanupStorage(maxStoragePercent: Int): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        var storageInfo = getStorageInfo()
        
        while (storageInfo.usedPercent >= maxStoragePercent) {
            if (!deleteOldestRecording()) break
            deletedCount++
            storageInfo = getStorageInfo()
        }
        deletedCount
    }
    
    fun generateFileName(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)
        return "EvidenceCam_$timestamp.mp4"
    }
    
    fun getNewRecordingFile(): File = File(recordingsDir, generateFileName())
}
