package com.newoether.agora.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerDraftEffectTest {
    @Test
    fun acceptedProjectionClearsOnlyTheTextThatWasActuallySent() {
        assertFalse(
            preservesDisplayedTextAfterAcceptedProjection(
                displayedText = "sent",
                acceptedText = "sent",
            ),
        )
        assertTrue(
            preservesDisplayedTextAfterAcceptedProjection(
                displayedText = "typed after send",
                acceptedText = "sent",
            ),
        )
        assertFalse(
            preservesDisplayedTextAfterAcceptedProjection(
                displayedText = "ordinary authoritative reload",
                acceptedText = null,
            ),
        )
    }
}
