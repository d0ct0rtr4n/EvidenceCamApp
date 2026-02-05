package com.evidencecam.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evidencecam.app.model.VideoRecording
import com.evidencecam.app.repository.VideoRepository
import com.evidencecam.app.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    val recordings: StateFlow<List<VideoRecording>> = videoRepository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toastMessage = MutableSharedFlow<Event<String>>()
    val toastMessage = _toastMessage.asSharedFlow()

    fun deleteRecording(recording: VideoRecording) {
        viewModelScope.launch {
            try {
                videoRepository.deleteRecording(recording)
                _toastMessage.emit(Event("Recording deleted"))
            } catch (e: Exception) {
                _toastMessage.emit(Event("Failed to delete: ${e.message}"))
            }
        }
    }

    fun deleteAllRecordings(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = videoRepository.deleteAllRecordings()
                _toastMessage.emit(Event("Deleted $count recordings"))
                onComplete(count)
            } catch (e: Exception) {
                _toastMessage.emit(Event("Failed to delete: ${e.message}"))
                onComplete(0)
            }
        }
    }
}
