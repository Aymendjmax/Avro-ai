package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay

/** Stable semantic state shared by every fixed-geometry media viewport. */
internal enum class MediaLoadPresentation {
    LOADING,
    LOADED,
    FAILED,
}

internal const val MEDIA_STATE_CROSSFADE_MILLIS = 200
internal const val MEDIA_LOADING_INDICATOR_DELAY_MILLIS = 200L
internal val MEDIA_LOADING_INDICATOR_STROKE_WIDTH = 3.dp

@Composable
internal fun rememberMediaLoadingVisible(loadingKey: Any?, isLoading: Boolean): Boolean {
    var elapsed by remember(loadingKey, isLoading) { mutableStateOf(false) }
    LaunchedEffect(loadingKey, isLoading) {
        if (isLoading) {
            delay(MEDIA_LOADING_INDICATOR_DELAY_MILLIS)
            elapsed = true
        }
    }
    return isLoading && elapsed
}

internal fun mediaLoadPresentation(
    loaded: Boolean,
    failed: Boolean,
): MediaLoadPresentation = when {
    failed -> MediaLoadPresentation.FAILED
    loaded -> MediaLoadPresentation.LOADED
    else -> MediaLoadPresentation.LOADING
}

internal fun AsyncImagePainter.State.toMediaLoadPresentation(): MediaLoadPresentation =
    mediaLoadPresentation(
        loaded = this is AsyncImagePainter.State.Success,
        failed = this is AsyncImagePainter.State.Error,
    )
