package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLoadPresentationTest {
    @Test
    fun semanticMediaStateIsTotalAndFailureWins() {
        assertEquals(
            MediaLoadPresentation.LOADING,
            mediaLoadPresentation(loaded = false, failed = false),
        )
        assertEquals(
            MediaLoadPresentation.LOADED,
            mediaLoadPresentation(loaded = true, failed = false),
        )
        assertEquals(
            MediaLoadPresentation.FAILED,
            mediaLoadPresentation(loaded = false, failed = true),
        )
        assertEquals(
            MediaLoadPresentation.FAILED,
            mediaLoadPresentation(loaded = true, failed = true),
        )
    }
}
