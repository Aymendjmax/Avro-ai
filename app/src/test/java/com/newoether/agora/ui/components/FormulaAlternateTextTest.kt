package com.newoether.agora.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Formula line breaks survive the single-line placeholder and come back on copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FormulaAlternateTextTest {
    private val source = "$$\n(a-d)^2 + b^2\r\n\\;\\Rightarrow\\;\nL\n$$"

    @Test fun alternateTextHasNoLineBreaksAndRoundTrips() {
        val alt = formulaAlternateText(source)
        assertFalse(alt.any { it == '\n' || it == '\r' })
        assertEquals(source, restoreFormulaLineBreaks(alt))
        assertEquals("plain text", restoreFormulaLineBreaks("plain text"))
    }

    @Test fun multiLineAlternateTextResolvesToSameRequestAsSource() {
        val request = latexImageRequest(formulaAlternateText(source))
        assertNotNull(request)
        assertEquals(latexImageRequest(source), request)
    }

    @Test fun clipboardRestoresLineBreaksOnCopy() = runBlocking {
        val base = RecordingClipboard()
        val copied = "Before ${formulaAlternateText(source)} after"
        FormulaSourceClipboard(base).setClipEntry(ClipEntry(ClipData.newPlainText("", copied)))
        assertEquals("Before $source after", base.last!!.clipData.getItemAt(0).text.toString())
    }

    private class RecordingClipboard : Clipboard {
        var last: ClipEntry? = null
        override val nativeClipboard: NativeClipboard get() = throw UnsupportedOperationException()
        override suspend fun getClipEntry(): ClipEntry? = last
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { last = clipEntry }
    }
}
