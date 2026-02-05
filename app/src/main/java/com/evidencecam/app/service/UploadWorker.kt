package com.evidencecam.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.evidencecam.app.R
import com.evidencecam.app.model.*
import com.evidencecam.app.repository.SettingsRepository
import com.evidencecam.app.repository.VideoRepository
import com.evidencecam.app.util.Constants
import com.evidencecam.app.util.DropboxAuthHelper
import com.evidencecam.app.util.NetworkUtils
import com.dropbox.core.DbxException
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.RateLimitException
import com.dropbox.core.v2.files.WriteMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Sealed class representing different categories of upload errors
 * to enable appropriate error handling and retry strategies.
 */
sealed class UploadError : Exception() {
    /** Authentication errors - user needs to re-authenticate */
    data class AuthError(override val message: String) : UploadError()

    /** Network errors - transient, should retry */
    data class NetworkError(override val message: String, override val cause: Throwable? = null) : UploadError()

    /** Quota/rate limit errors - should delay retry */
    data class QuotaError(override val message: String) : UploadError()

    /** File errors - should not retry (file missing, corrupt, etc.) */
    data class FileError(override val message: String) : UploadError()

    /** Server errors - transient, should retry */
    data class ServerError(override val message: String, val code: Int) : UploadError()

    /** Configuration errors - should not retry */
    data class ConfigError(override val message: String) : UploadError()

    /** Folder not found - cached folder ID is invalid */
    data class FolderNotFoundError(override val message: String) : UploadError()
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository,
    private val dropboxAuthHelper: DropboxAuthHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_RECORDING_ID = "recording_id"
        private const val MAX_RETRY_COUNT = 5
        private const val NOTIFICATION_CHANNEL_ID = "upload_notifications"
        private const val NOTIFICATION_ID_FAILURE = 2001

        fun enqueuePendingUploads(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(Constants.UPLOAD_WORK_TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            Log.d(TAG, "Enqueueing upload work")
            WorkManager.getInstance(context)
                .enqueueUniqueWork("pending_uploads", ExistingWorkPolicy.KEEP, uploadRequest)
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Upload Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for upload status"
                }
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            }
        }

        private const val NOTIFICATION_ID_UPLOADING = 2000
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("Uploading recordings")
            .setContentText("Uploading to cloud storage...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID_UPLOADING, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "UploadWorker started")
        createNotificationChannel(context)

        val settings = settingsRepository.settingsFlow.first()
        Log.d(TAG, "Upload destination: ${settings.uploadDestination}, WiFi only: ${settings.uploadOnWifiOnly}")

        if (settings.uploadOnWifiOnly && !NetworkUtils.isWifiConnected(context)) {
            Log.d(TAG, "Skipping upload - WiFi required but not connected")
            return@withContext Result.retry()
        }

        if (settings.uploadDestination == UploadDestination.LOCAL_ONLY) {
            Log.d(TAG, "Upload destination is LOCAL_ONLY, skipping")
            return@withContext Result.success()
        }

        // Reset any stuck UPLOADING recordings back to PENDING
        videoRepository.resetStuckUploads()

        val recordings = videoRepository.getPendingUploads(5)
        Log.d(TAG, "Found ${recordings.size} pending uploads")
        if (recordings.isEmpty()) return@withContext Result.success()

        var allSuccess = true
        var permanentFailures = 0

        for (recording in recordings) {
            if (recording.uploadStatus != UploadStatus.PENDING) continue

            // Check retry count limit
            if (recording.retryCount >= MAX_RETRY_COUNT) {
                Log.w(TAG, "Skipping ${recording.fileName} - exceeded max retry count (${recording.retryCount})")
                videoRepository.updateUploadStatus(recording.id, UploadStatus.FAILED, "Exceeded maximum retry attempts")
                showFailureNotification(recording.fileName, "Exceeded maximum retry attempts after ${recording.retryCount} tries")
                permanentFailures++
                continue
            }

            // Verify local file exists before attempting upload
            val localFile = File(recording.filePath)
            if (!localFile.exists()) {
                Log.e(TAG, "Local file not found: ${recording.filePath}")
                videoRepository.updateUploadStatus(recording.id, UploadStatus.FAILED, "Local file not found")
                permanentFailures++
                continue
            }

            try {
                videoRepository.updateUploadStatus(recording.id, UploadStatus.UPLOADING)

                // Perform upload based on destination
                val remoteUrl: String? = when (settings.uploadDestination) {
                    UploadDestination.DROPBOX -> uploadToDropbox(localFile, recording.fileName)
                    UploadDestination.LOCAL_ONLY -> null
                }

                // Verify upload succeeded by checking we got a valid URL
                if (remoteUrl != null) {
                    videoRepository.markAsUploaded(recording.id, remoteUrl)
                    Log.i(TAG, "Uploaded ${recording.fileName} -> $remoteUrl")

                    // Only delete after successful upload AND verification
                    if (settings.autoDeleteAfterUpload) {
                        // Double-check the upload status before deleting
                        val verifiedRecording = videoRepository.getRecordingById(recording.id)
                        if (verifiedRecording?.uploadStatus == UploadStatus.COMPLETED && verifiedRecording.remoteUrl != null) {
                            videoRepository.deleteRecording(recording)
                            Log.i(TAG, "Auto-deleted local file after verified upload: ${recording.fileName}")
                        } else {
                            Log.w(TAG, "Skipping auto-delete - upload verification failed for ${recording.fileName}")
                        }
                    }
                } else {
                    throw UploadError.ServerError("Upload completed but no URL returned", 0)
                }

            } catch (e: Exception) {
                val uploadError = categorizeError(e)
                Log.e(TAG, "Upload failed: ${recording.fileName} - ${uploadError.message}", e)

                when (uploadError) {
                    is UploadError.AuthError -> {
                        // Auth errors - don't retry, user needs to re-authenticate
                        videoRepository.updateUploadStatus(recording.id, UploadStatus.FAILED, uploadError.message)
                        showFailureNotification(recording.fileName, "Authentication failed - please re-login to ${settings.uploadDestination.name}")
                        permanentFailures++
                    }
                    is UploadError.FileError -> {
                        // File errors - don't retry
                        videoRepository.updateUploadStatus(recording.id, UploadStatus.FAILED, uploadError.message)
                        permanentFailures++
                    }
                    is UploadError.ConfigError -> {
                        // Config errors - don't retry
                        videoRepository.updateUploadStatus(recording.id, UploadStatus.FAILED, uploadError.message)
                        showFailureNotification(recording.fileName, "Configuration error - please check settings")
                        permanentFailures++
                    }
                    is UploadError.FolderNotFoundError -> {
                        // Folder not found - retry with count increment
                        Log.w(TAG, "Folder not found")
                        videoRepository.updateUploadStatusWithRetry(recording.id, UploadStatus.PENDING, uploadError.message)
                        allSuccess = false
                    }
                    is UploadError.QuotaError -> {
                        // Quota errors - retry with longer delay, increment count
                        videoRepository.updateUploadStatusWithRetry(recording.id, UploadStatus.PENDING, uploadError.message)
                        showFailureNotification(recording.fileName, "Storage quota exceeded - will retry later")
                        allSuccess = false
                    }
                    is UploadError.NetworkError, is UploadError.ServerError -> {
                        // Transient errors - retry with count increment
                        videoRepository.updateUploadStatusWithRetry(recording.id, UploadStatus.PENDING, uploadError.message)
                        allSuccess = false
                    }
                }
            }
        }

        when {
            allSuccess && permanentFailures == 0 -> Result.success()
            permanentFailures == recordings.size -> Result.failure() // All failed permanently
            else -> Result.retry()
        }
    }

    /**
     * Categorize exceptions into specific error types for appropriate handling
     */
    private fun categorizeError(e: Exception): UploadError {
        return when (e) {
            is UploadError -> e

            // Network errors
            is UnknownHostException -> UploadError.NetworkError("No internet connection", e)
            is SocketTimeoutException -> UploadError.NetworkError("Connection timed out", e)
            is IOException -> {
                if (e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("connection", ignoreCase = true) == true) {
                    UploadError.NetworkError("Network error: ${e.message}", e)
                } else {
                    UploadError.FileError("File I/O error: ${e.message}")
                }
            }

            // Configuration errors
            is IllegalStateException -> UploadError.ConfigError(e.message ?: "Configuration error")

            // Default
            else -> UploadError.ServerError("Unexpected error: ${e.message}", 0)
        }
    }

    private fun showFailureNotification(fileName: String, reason: String) {
        try {
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Upload Failed")
                .setContentText("$fileName: $reason")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$fileName\n$reason"))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FAILURE + fileName.hashCode() % 100, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot show notification - permission not granted", e)
        }
    }

    /**
     * Upload a file to Dropbox and return the shared link URL
     */
    private suspend fun uploadToDropbox(file: File, fileName: String): String {
        val dropboxConfig = settingsRepository.dropboxConfigFlow.first()
            ?: throw UploadError.ConfigError("Dropbox not configured - please connect your account in Settings")

        try {
            val client = dropboxAuthHelper.getClient(dropboxConfig)

            // Ensure the folder exists
            dropboxAuthHelper.ensureFolderExists(dropboxConfig)

            // Upload the file
            val remotePath = "${dropboxConfig.folderPath}/$fileName"
            Log.d(TAG, "Uploading to Dropbox: $remotePath")

            FileInputStream(file).use { inputStream ->
                client.files().uploadBuilder(remotePath)
                    .withMode(WriteMode.OVERWRITE)
                    .uploadAndFinish(inputStream)
            }

            Log.d(TAG, "Upload complete, creating shared link for: $remotePath")

            // Create or get existing shared link
            val sharedLink = try {
                client.sharing().createSharedLinkWithSettings(remotePath)
            } catch (e: DbxException) {
                // If link already exists, get the existing one
                if (e.message?.contains("shared_link_already_exists") == true) {
                    val links = client.sharing().listSharedLinksBuilder()
                        .withPath(remotePath)
                        .withDirectOnly(true)
                        .start()
                    links.links.firstOrNull()
                        ?: throw UploadError.ServerError("Failed to get existing shared link", 0)
                } else {
                    throw e
                }
            }

            return sharedLink.url

        } catch (e: InvalidAccessTokenException) {
            throw UploadError.AuthError("Dropbox access token expired or invalid")
        } catch (e: RateLimitException) {
            throw UploadError.QuotaError("Dropbox rate limit exceeded - please try again later")
        } catch (e: DbxException) {
            val message = e.message ?: "Unknown Dropbox error"
            when {
                message.contains("insufficient_space", ignoreCase = true) ->
                    throw UploadError.QuotaError("Dropbox storage is full")
                message.contains("path/not_found", ignoreCase = true) ->
                    throw UploadError.FolderNotFoundError("Dropbox folder not found")
                message.contains("invalid_access_token", ignoreCase = true) ->
                    throw UploadError.AuthError("Dropbox authentication expired")
                else ->
                    throw UploadError.ServerError("Dropbox error: $message", 0)
            }
        }
    }
}
