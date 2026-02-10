package com.evidencecam.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.evidencecam.app.EvidenceCamApplication
import com.evidencecam.app.R
import com.evidencecam.app.model.*
import com.evidencecam.app.repository.SettingsRepository
import com.evidencecam.app.repository.VideoRepository
import com.evidencecam.app.ui.main.MainActivity
import com.evidencecam.app.util.Constants
import com.evidencecam.app.util.LocationData
import com.evidencecam.app.util.LocationProvider
import com.evidencecam.app.util.VideoOverlayProcessor
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.*
import java.util.concurrent.Executor
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class RecordingService : LifecycleService() {
    
    companion object {
        private const val TAG = "RecordingService"
        
        fun startRecording(context: Context) {
            Intent(context, RecordingService::class.java).also {
                it.action = Constants.ACTION_START_RECORDING
                ContextCompat.startForegroundService(context, it)
            }
        }
        
        fun stopRecording(context: Context) {
            Intent(context, RecordingService::class.java).also {
                it.action = Constants.ACTION_STOP_RECORDING
                context.startService(it)
            }
        }
    }
    
    @Inject lateinit var videoRepository: VideoRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var videoOverlayProcessor: VideoOverlayProcessor
    
    private val binder = LocalBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var currentRecording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    private val _currentDuration = MutableStateFlow(0L)
    val currentDuration: StateFlow<Long> = _currentDuration.asStateFlow()
    
    private var currentSettings: AppSettings = AppSettings()
    private var currentSegmentFile: File? = null
    private var segmentStartTime: Long = 0
    private var totalRecordingStartTime: Long = 0
    private var segmentCount = 0
    private var segmentStartLocation: LocationData? = null
    
    private var durationUpdateJob: Job? = null
    private var segmentTimerJob: Job? = null
    
    private val mainThreadExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            settingsRepository.settingsFlow.collect { currentSettings = it }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            Constants.ACTION_START_RECORDING -> {
                startForegroundNotification()
                lifecycleScope.launch { startRecordingInternal() }
            }
            Constants.ACTION_STOP_RECORDING -> {
                lifecycleScope.launch { stopRecordingInternal() }
            }
        }
        return START_STICKY
    }
    
    private fun startForegroundNotification() {
        val notification = createNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.NOTIFICATION_ID_RECORDING, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID_RECORDING, notification)
        }
    }
    
    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = Constants.ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, EvidenceCamApplication.RECORDING_CHANNEL_ID)
            .setContentTitle("Evidence Cam Recording")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_videocam)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Constants.NOTIFICATION_ID_RECORDING, createNotification(content))
    }
    
    private suspend fun startRecordingInternal() {
        if (_recordingState.value is RecordingState.Recording) return
        _recordingState.value = RecordingState.Starting

        try {
            acquireWakeLock()
            locationProvider.startLocationUpdates()
            videoRepository.checkAndCleanupStorage(currentSettings.maxStoragePercent)
            initializeCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Unknown error")
            releaseWakeLock()
            locationProvider.stopLocationUpdates()
            sendBroadcast(Intent(Constants.ACTION_ERROR).apply {
                setPackage(packageName)
                putExtra("error_message", e.message)
            })
        }
    }
    
    private fun initializeCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Camera initialization failed")
            }
        }, mainThreadExecutor)
    }
    
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        
        val preferredQuality = when (currentSettings.videoQuality) {
            VideoQuality.SD -> Quality.SD
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
        }
        // Use fallback strategy: try preferred quality, then fall back to lower qualities
        // This ensures recording works on devices where the preferred quality isn't supported
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(preferredQuality, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        videoCapture = VideoCapture.withOutput(recorder)
        preview = Preview.Builder().build()
        
        try {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
            startNewSegment()
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Camera binding failed")
        }
    }
    
    fun bindPreview(previewView: PreviewView) {
        preview?.setSurfaceProvider(previewView.surfaceProvider)
    }
    
    private fun startNewSegment() {
        lifecycleScope.launch {
            try {
                currentSegmentFile = videoRepository.getNewRecordingFile()
                segmentStartTime = System.currentTimeMillis()
                segmentStartLocation = locationProvider.currentLocation.value
                if (segmentCount == 0) totalRecordingStartTime = segmentStartTime

                val fileOutputOptions = FileOutputOptions.Builder(currentSegmentFile!!).build()
                
                val recordingRequest = videoCapture?.output?.prepareRecording(this@RecordingService, fileOutputOptions)
                if (currentSettings.enableAudio) {
                    if (ContextCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        recordingRequest?.withAudioEnabled()
                    } else {
                        Log.w(TAG, "Audio recording requested but permission is not granted.")
                    }
                }
                currentRecording = recordingRequest?.start(mainThreadExecutor) { event -> handleRecordingEvent(event) }

                segmentCount++
                _recordingState.value = RecordingState.Recording(
                    totalRecordingStartTime, segmentCount, segmentStartTime
                )
                
                startDurationUpdates()
                startSegmentTimer()
                sendBroadcast(Intent(Constants.ACTION_RECORDING_STARTED))
                updateNotification("Recording... Segment $segmentCount")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start segment", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
            }
        }
    }
    
    private fun handleRecordingEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.e(TAG, "Recording error: ${event.error}")
                    _recordingState.value = RecordingState.Error("Recording error")
                } else {
                    handleSegmentComplete()
                }
            }
            else -> { }
        }
    }
    
    private fun handleSegmentComplete() {
        lifecycleScope.launch {
            val file = currentSegmentFile ?: return@launch
            val timestamp = segmentStartTime
            val location = segmentStartLocation

            // Process video with overlay in background
            val processedResult = videoOverlayProcessor.processVideoWithTimestampOverlay(
                file, timestamp, location
            )

            val finalFile = processedResult.getOrElse {
                Log.e(TAG, "Failed to process overlay, using original file", it)
                file
            }

            val actualDurationMs = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(finalFile.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: (currentSettings.segmentDuration.seconds * 1000L)
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read video duration, using configured duration", e)
                currentSettings.segmentDuration.seconds * 1000L
            }

            val recording = VideoRecording(
                id = UUID.randomUUID().toString(),
                fileName = finalFile.name,
                filePath = finalFile.absolutePath,
                fileSize = finalFile.length(),
                durationMs = actualDurationMs,
                recordedAt = Date(timestamp),
                uploadStatus = if (currentSettings.uploadDestination == UploadDestination.LOCAL_ONLY)
                    UploadStatus.SKIPPED else UploadStatus.PENDING,
                uploadDestination = currentSettings.uploadDestination
            )

            videoRepository.insertRecording(recording)
            sendBroadcast(Intent(Constants.ACTION_SEGMENT_COMPLETED).apply {
                setPackage(packageName)
                putExtra("recording_id", recording.id)
            })

            // Trigger upload worker if upload destination is configured
            if (currentSettings.uploadDestination != UploadDestination.LOCAL_ONLY) {
                UploadWorker.enqueuePendingUploads(this@RecordingService)
            }

            if (_recordingState.value is RecordingState.Recording) {
                videoRepository.checkAndCleanupStorage(currentSettings.maxStoragePercent)
                startNewSegment()
            }
        }
    }
    
    private fun startDurationUpdates() {
        durationUpdateJob?.cancel()
        durationUpdateJob = lifecycleScope.launch {
            while (isActive && _recordingState.value is RecordingState.Recording) {
                _currentDuration.value = System.currentTimeMillis() - totalRecordingStartTime
                delay(1000)
            }
        }
    }
    
    private fun startSegmentTimer() {
        segmentTimerJob?.cancel()
        segmentTimerJob = lifecycleScope.launch {
            delay(currentSettings.segmentDuration.seconds * 1000L)
            if (_recordingState.value is RecordingState.Recording) currentRecording?.stop()
        }
    }
    
    private suspend fun stopRecordingInternal() {
        if (_recordingState.value !is RecordingState.Recording) {
            stopSelf()
            return
        }

        _recordingState.value = RecordingState.Stopping
        durationUpdateJob?.cancel()
        segmentTimerJob?.cancel()
        currentRecording?.stop()
        currentRecording = null
        cameraProvider?.unbindAll()
        releaseWakeLock()
        locationProvider.stopLocationUpdates()

        _recordingState.value = RecordingState.Idle
        _currentDuration.value = 0L
        segmentCount = 0

        sendBroadcast(Intent(Constants.ACTION_RECORDING_STOPPED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLockType = if (currentSettings.keepScreenOn) {
                @Suppress("DEPRECATION")
                PowerManager.SCREEN_DIM_WAKE_LOCK
            } else {
                PowerManager.PARTIAL_WAKE_LOCK
            }
            wakeLock = pm.newWakeLock(wakeLockType, "EvidenceCam::Recording")
                .apply { acquire(10 * 60 * 60 * 1000L) }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { stopRecordingInternal() }
    }
}
