package com.ghostcapture.app.recorder

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Recording state shared between the foreground service and the UI.
 * The service is the only writer; screens only read.
 */
object RecorderState {
    val isRecording = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val currentFileName = MutableStateFlow<String?>(null)
    val lastSavedUri = MutableStateFlow<Uri?>(null)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)

    fun clearError() {
        error.value = null
    }

    fun clearNotice() {
        notice.value = null
    }
}
