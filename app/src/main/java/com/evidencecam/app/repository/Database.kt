package com.evidencecam.app.repository

import android.content.Context
import androidx.room.*
import com.evidencecam.app.model.UploadDestination
import com.evidencecam.app.model.UploadStatus
import com.evidencecam.app.model.VideoRecording
import kotlinx.coroutines.flow.Flow
import java.util.Date

class Converters {
    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time

    @TypeConverter
    fun toDate(timestamp: Long?): Date? = timestamp?.let { Date(it) }

    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toUploadStatus(status: String): UploadStatus = UploadStatus.valueOf(status)

    @TypeConverter
    fun fromUploadDestination(destination: UploadDestination?): String? = destination?.name

    @TypeConverter
    fun toUploadDestination(destination: String?): UploadDestination? =
        destination?.let {
            try {
                UploadDestination.valueOf(it)
            } catch (e: IllegalArgumentException) {
                // Handle unknown enum values (e.g., removed GOOGLE_DRIVE, BOX) by defaulting to LOCAL_ONLY
                UploadDestination.LOCAL_ONLY
            }
        }
}

@Dao
interface VideoRecordingDao {
    
    @Query("SELECT * FROM video_recordings ORDER BY recordedAt DESC")
    fun getAllRecordings(): Flow<List<VideoRecording>>
    
    @Query("SELECT * FROM video_recordings WHERE uploadStatus = :status ORDER BY recordedAt ASC")
    fun getRecordingsByStatus(status: UploadStatus): Flow<List<VideoRecording>>
    
    @Query("SELECT * FROM video_recordings WHERE uploadStatus = :status ORDER BY recordedAt ASC LIMIT :limit")
    suspend fun getPendingRecordings(status: UploadStatus = UploadStatus.PENDING, limit: Int = 10): List<VideoRecording>
    
    @Query("SELECT * FROM video_recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): VideoRecording?
    
    @Query("SELECT * FROM video_recordings ORDER BY recordedAt ASC LIMIT 1")
    suspend fun getOldestRecording(): VideoRecording?
    
    @Query("SELECT SUM(fileSize) FROM video_recordings")
    suspend fun getTotalRecordingsSize(): Long?
    
    @Query("SELECT COUNT(*) FROM video_recordings")
    suspend fun getRecordingsCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: VideoRecording)
    
    @Update
    suspend fun updateRecording(recording: VideoRecording)
    
    @Delete
    suspend fun deleteRecording(recording: VideoRecording)
    
    @Query("DELETE FROM video_recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: String)
    
    @Query("UPDATE video_recordings SET uploadStatus = :status, lastError = :error WHERE id = :id")
    suspend fun updateUploadStatus(id: String, status: UploadStatus, error: String? = null)

    @Query("UPDATE video_recordings SET uploadStatus = :status, lastError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun updateUploadStatusWithRetry(id: String, status: UploadStatus, error: String? = null)
    
    @Query("UPDATE video_recordings SET uploadStatus = :status, uploadedAt = :uploadedAt, remoteUrl = :remoteUrl WHERE id = :id")
    suspend fun markAsUploaded(id: String, status: UploadStatus = UploadStatus.COMPLETED, uploadedAt: Date = Date(), remoteUrl: String? = null)

    @Query("SELECT * FROM video_recordings")
    suspend fun getAllRecordingsList(): List<VideoRecording>

    @Query("DELETE FROM video_recordings")
    suspend fun deleteAllRecordings()

    @Query("UPDATE video_recordings SET uploadStatus = 'PENDING' WHERE uploadStatus = 'UPLOADING'")
    suspend fun resetStuckUploads(): Int
}

@Database(
    entities = [VideoRecording::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoRecordingDao(): VideoRecordingDao
    
    companion object {
        const val DATABASE_NAME = "evidencecam_database"
    }
}
