package com.newoether.agora.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTypographyTest {
    @Test
    fun `user message body keeps its size and uses 1_1x line height`() {
        assertEquals(15.sp, ChatType.userBody.fontSize)
        assertEquals(24.2.sp, ChatType.userBody.lineHeight)
    }
}
