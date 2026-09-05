package com.iykyk.facecollage.ui

import com.iykyk.facecollage.domain.ProcessingStage
import com.iykyk.facecollage.domain.model.CollageResult

/** The whole screen's state — exactly one of these at a time, driven by [CollageViewModel]. */
sealed interface UiState {
    data object Idle : UiState

    data class Processing(val stage: ProcessingStage, val current: Int, val total: Int) : UiState {
        val fraction: Float get() = if (total > 0) current.toFloat() / total else 0f
    }

    data class Done(val result: CollageResult) : UiState

    data class Error(val message: String) : UiState
}
