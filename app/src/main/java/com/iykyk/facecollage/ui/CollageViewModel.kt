package com.iykyk.facecollage.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iykyk.facecollage.data.collage.MediaStoreSaver
import com.iykyk.facecollage.domain.ProcessingStage
import com.iykyk.facecollage.domain.VideoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the processing job and result across configuration changes — rotation must never
 * restart processing or lose the rendered collage.
 */
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val videoProcessor = VideoProcessor(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** One-off messages (save/share confirmations) — a SharedFlow so they fire once, not on
     *  every recomposition/rotation the way a replayed StateFlow value would. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var processingJob: Job? = null

    fun processVideo(uri: Uri, label: String) {
        if (_uiState.value is UiState.Processing) return
        _uiState.value = UiState.Processing(ProcessingStage.EXTRACTING_FRAMES, 0, 1)
        processingJob = viewModelScope.launch {
            try {
                videoProcessor.process(uri, label).collect { (progress, result) ->
                    _uiState.value = if (result != null) {
                        UiState.Done(result)
                    } else {
                        UiState.Processing(progress.stage, progress.current, progress.total)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Processing failed")
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        _uiState.value = UiState.Idle
    }

    fun reset() {
        processingJob?.cancel()
        _uiState.value = UiState.Idle
    }

    fun saveToGallery(context: Context) {
        val state = _uiState.value as? UiState.Done ?: return
        viewModelScope.launch {
            val uri = MediaStoreSaver.saveToGallery(context.applicationContext, state.result.bitmap)
            _messages.emit(if (uri != null) "Saved to gallery" else "Could not save collage")
        }
    }

    /** [context] must be an Activity context so the share chooser attaches to the right task. */
    fun share(context: Context) {
        val state = _uiState.value as? UiState.Done ?: return
        MediaStoreSaver.share(context, state.result.bitmap)
    }
}
