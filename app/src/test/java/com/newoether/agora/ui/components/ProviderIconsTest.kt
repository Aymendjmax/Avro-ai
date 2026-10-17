package com.newoether.agora.ui.components

import com.newoether.agora.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderIconsTest {
    @Test
    fun `OpenCode Go uses its monochrome provider icon`() {
        assertEquals(R.drawable.provider_opencode, providerIcon("OpenCode Go"))
    }
}
