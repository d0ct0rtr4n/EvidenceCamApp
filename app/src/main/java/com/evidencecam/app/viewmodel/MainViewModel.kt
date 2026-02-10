package com.evidencecam.app.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.evidencecam.app.model.*
import com.evidencecam.app.repository.SettingsRepository
import com.evidencecam.app.repository.VideoRepository
import com.evidencecam.app.service.RecordingService
import com.evidencecam.app.service.UploadWorker
import com.evidencecam.app.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@UnstableApi
class MainViewModel @Inject constructor(
    application: Application,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {
    
    private val context: Context get() = getApplication()
    
    private var recordingService: RecordingService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recordingService = (service as RecordingService.LocalBinder).getService()
            serviceBound = true
            
            viewModelScope.launch {
                recordingService?.recordingState?.collect { _recordingState.value = it }
            }
            viewModelScope.launch {
                recordingService?.currentDuration?.collect { _currentDuration.value = it }
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            serviceBound = false
        }
    }
    
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    private val _currentDuration = MutableStateFlow(0L)
    val currentDuration: StateFlow<Long> = _currentDuration.asStateFlow()
    
    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()
    
    private val _toastMessage = MutableSharedFlow<Event<String>>()
    val toastMessage: SharedFlow<Event<String>> = _toastMessage.asSharedFlow()
    
    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    
    val privacyPolicyAccepted: StateFlow<Boolean> = settingsRepository.privacyPolicyAcceptedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    
    val recordings: StateFlow<List<VideoRecording>> = videoRepository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    init {
        bindService()
        refreshStorageInfo()
        // Trigger any pending uploads when app starts
        triggerPendingUploads()
    }

    private fun triggerPendingUploads() {
        viewModelScope.launch {
            // Wait for actual settings to load from DataStore, then trigger if configured
            val actualSettings = settingsRepository.settingsFlow.first()
            if (actualSettings.uploadDestination != UploadDestination.LOCAL_ONLY) {
                UploadWorker.enqueuePendingUploads(context)
            }
        }
    }
    
    private fun bindService() {
        Intent(context, RecordingService::class.java).also {
            context.bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    fun refreshStorageInfo() {
        viewModelScope.launch {
            _storageInfo.value = videoRepository.getStorageInfo()
        }
    }
    
    fun startRecording() {
        viewModelScope.launch {
            RecordingService.startRecording(context)
            showToast("Recording started")
        }
    }
    
    fun stopRecording() {
        viewModelScope.launch {
            RecordingService.stopRecording(context)
            showToast("Recording stopped")
            refreshStorageInfo()
            UploadWorker.enqueuePendingUploads(context)
        }
    }
    
    fun bindPreviewToService(previewView: PreviewView) {
        recordingService?.bindPreview(previewView)
    }
    
    fun acceptPrivacyPolicy() {
        viewModelScope.launch { settingsRepository.acceptPrivacyPolicy() }
    }
    
    private suspend fun showToast(message: String) {
        _toastMessage.emit(Event(message))
    }
    
    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
