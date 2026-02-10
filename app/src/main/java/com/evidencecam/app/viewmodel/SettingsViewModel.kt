package com.evidencecam.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evidencecam.app.model.*
import com.evidencecam.app.repository.SettingsRepository
import com.evidencecam.app.util.DropboxAuthHelper
import com.evidencecam.app.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CloudAuthState {
    data object Idle : CloudAuthState()
    data object Authenticating : CloudAuthState()
    data object Success : CloudAuthState()
    data class Error(val message: String) : CloudAuthState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val dropboxAuthHelper: DropboxAuthHelper
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val dropboxConfig: StateFlow<DropboxConfig?> = settingsRepository.dropboxConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _toastMessage = MutableSharedFlow<Event<String>>()
    val toastMessage: SharedFlow<Event<String>> = _toastMessage.asSharedFlow()

    private val _dropboxAuthIntent = MutableSharedFlow<Event<Intent>>()
    val dropboxAuthIntent: SharedFlow<Event<Intent>> = _dropboxAuthIntent.asSharedFlow()

    private val _dropboxAuthState = MutableStateFlow<CloudAuthState>(CloudAuthState.Idle)
    val dropboxAuthState: StateFlow<CloudAuthState> = _dropboxAuthState.asStateFlow()

    fun updateUploadDestination(destination: UploadDestination) {
        viewModelScope.launch { settingsRepository.updateUploadDestination(destination) }
    }

    fun updateVideoQuality(quality: VideoQuality) {
        viewModelScope.launch { settingsRepository.updateVideoQuality(quality) }
    }

    fun updateSegmentDuration(duration: SegmentDuration) {
        viewModelScope.launch { settingsRepository.updateSegmentDuration(duration) }
    }

    fun updateMaxStoragePercent(percent: Int) {
        viewModelScope.launch { settingsRepository.updateMaxStoragePercent(percent) }
    }

    fun updateUploadOnWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { settingsRepository.updateUploadOnWifiOnly(wifiOnly) }
    }

    fun updateEnableAudio(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateEnableAudio(enabled) }
    }

    fun updateAutoDeleteAfterUpload(autoDelete: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoDeleteAfterUpload(autoDelete) }
    }

    fun updateKeepScreenOn(keepOn: Boolean) {
        viewModelScope.launch { settingsRepository.updateKeepScreenOn(keepOn) }
    }

    fun updateShowPreview(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowPreview(show) }
    }

    // Dropbox Authentication
    fun signInToDropbox() {
        viewModelScope.launch {
            _dropboxAuthState.value = CloudAuthState.Authenticating
            val intent = dropboxAuthHelper.getAuthIntent()
            _dropboxAuthIntent.emit(Event(intent))
        }
    }

    fun resetDropboxAuthState() {
        _dropboxAuthState.value = CloudAuthState.Idle
    }

    fun handleDropboxAuthCallback(uri: android.net.Uri) {
        viewModelScope.launch {
            _dropboxAuthState.value = CloudAuthState.Authenticating
            dropboxAuthHelper.handleAuthCallback(uri)
                .onSuccess { config ->
                    settingsRepository.saveDropboxConfig(config)
                    _dropboxAuthState.value = CloudAuthState.Success
                    showToast("Dropbox connected")
                    // Trigger pending uploads now that Dropbox is connected
                    triggerPendingUploads()
                }
                .onFailure { error ->
                    _dropboxAuthState.value = CloudAuthState.Error(error.message ?: "Unknown error")
                    showToast("Dropbox auth failed: ${error.message}")
                }
        }
    }

    private fun triggerPendingUploads() {
        com.evidencecam.app.service.UploadWorker.enqueuePendingUploads(context)
    }

    fun disconnectDropbox() {
        viewModelScope.launch {
            settingsRepository.saveDropboxConfig(null)
            _dropboxAuthState.value = CloudAuthState.Idle
            showToast("Dropbox disconnected")
        }
    }

    private suspend fun showToast(message: String) {
        _toastMessage.emit(Event(message))
    }
}
