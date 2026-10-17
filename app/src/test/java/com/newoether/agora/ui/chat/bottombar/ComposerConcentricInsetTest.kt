package com.newoether.agora.ui.chat.bottombar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Non-expanded composer geometry: control size and inset, and fixed insets for the text and expand icon. */
class ComposerConcentricInsetTest {
    @Test fun controlsKeepTheirSizeAndInset() {
        assertEquals(28.dp, CHAT_BOTTOM_BAR_OUTER_RADIUS)
        assertEquals(48.dp, COMPOSER_CONTROL_HEIGHT)
        assertEquals(10.dp, COMPOSER_CONTROLS_INSET)
    }

    @Test fun textAndExpandIconSitAtTheCornerInset() {
        assertEquals(18.dp, COMPOSER_CORNER_CONTENT_INSET)
        assertEquals(18.dp, COMPOSER_TEXT_START_INSET)
        assertEquals(16.dp, COMPOSER_TEXT_TOP_INSET)
        assertEquals(22.dp, COMPOSER_TEXT_CONTROLS_GAP)
        val layout = source("ChatComposerLayout.kt")
        assertTrue(layout.contains("padding(start = COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_HOST_SIDE_PADDING, top = COMPOSER_HOST_TOP_PADDING, bottom = COMPOSER_CONTROLS_INSET)"))
        assertTrue(layout.contains("start = COMPOSER_TEXT_START_INSET - COMPOSER_HOST_SIDE_PADDING,"))
        assertTrue(layout.contains("top = COMPOSER_TEXT_TOP_INSET - COMPOSER_HOST_TOP_PADDING,"))
        // The text ends the controls gap above the controls, with no Material 56 dp minimum below it.
        assertTrue(layout.contains("bottom = COMPOSER_TEXT_CONTROLS_GAP - CONTROLS_ROW_TOP_PADDING,"))
        assertTrue(layout.contains("padding(top = CONTROLS_ROW_TOP_PADDING, start ="))
        assertTrue(layout.contains(".heightIn(min = TEXT_FIELD_MIN_HEIGHT)"))
        assertTrue(layout.contains("Modifier.offset(x = COMPOSER_HOST_SIDE_PADDING - EXPAND_BUTTON_EDGE_INSET, y = EXPAND_BUTTON_EDGE_INSET - COMPOSER_HOST_TOP_PADDING).size(COMPOSER_EXPAND_BUTTON_SIZE)"))
        assertTrue(layout.contains("EXPAND_BUTTON_EDGE_INSET = COMPOSER_CORNER_CONTENT_INSET - (COMPOSER_EXPAND_BUTTON_SIZE - COMPOSER_EXPAND_ICON_SIZE) / 2"))
        assertTrue(layout.contains("start = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING"))
        assertTrue(source("ComposerSendButton.kt").contains("Modifier.size(COMPOSER_CONTROL_HEIGHT)"))
        assertTrue(source("ChatBottomBarComponents.kt").contains(".height(COMPOSER_CONTROL_HEIGHT)"))
        // Capsule children: 32 dp buttons 8 dp from its ends, so 16 + 8 = 24 = capsule radius.
        assertTrue(source("ChatBottomBarComponents.kt").contains(".padding(horizontal = COMPOSER_CONTROL_HEIGHT / 2 - 16.dp)"))
    }

    private fun source(name: String): File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "com/newoether/agora/ui/chat/bottombar/$name") }
        .first { it.isFile }

    private fun File.contains(text: String): Boolean = readText().contains(text)
}
