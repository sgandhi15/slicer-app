package com.example.slicer.batch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VideoItemProgress(val itemId: String, val percent: Int)

/** How far the video being encoded right now has got. Lives in memory only; the worker and UI share the process. */
internal object VideoProgress {
    private val state = MutableStateFlow<VideoItemProgress?>(null)
    val current: StateFlow<VideoItemProgress?> = state.asStateFlow()

    fun report(itemId: String, percent: Int) {
        state.value = VideoItemProgress(itemId, percent)
    }

    fun clear() {
        state.value = null
    }
}
