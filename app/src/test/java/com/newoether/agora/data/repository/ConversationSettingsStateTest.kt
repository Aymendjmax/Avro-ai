package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationSettingsStateTest {
    @Test
    fun `legacy tier overrides normalize on import and optimistic write`() {
        val state = ConversationSettingsState()
        val imported = state.applyImport(
            mapOf("old" to ConversationSettings(openAiServiceTier = "scale")),
            replace = true,
        )
        assertEquals("default", imported.getValue("old").openAiServiceTier)
        val write = state.update("old") { it.copy(openAiServiceTier = "priority") }
        assertEquals("fast", write.settings?.openAiServiceTier)
        assertEquals("fast", state.state.value.getValue("old").openAiServiceTier)
    }

    @Test
    fun `merge supersedes imported identities but preserves unrelated pending writes`() {
        val state = ConversationSettingsState()
        val importedWrite = state.update("imported") { it.copy(temperature = 0.1f) }
        val localWrite = state.update("local") { it.copy(temperature = 0.2f) }

        val updated = state.applyImport(
            imported = mapOf("imported" to ConversationSettings(temperature = 0.8f)),
            replace = false,
        )

        assertEquals(0.8f, updated.getValue("imported").temperature)
        assertEquals(0.2f, updated.getValue("local").temperature)
        assertFalse(state.isLatest(importedWrite))
        assertEquals(true, state.isLatest(localWrite))
    }

    @Test
    fun `merge clears an imported identity whose archived settings are empty`() {
        val state = ConversationSettingsState()
        state.acceptPersisted(
            mapOf("imported" to ConversationSettings(temperature = 0.1f)),
        )

        val updated = state.applyImport(
            imported = mapOf("imported" to ConversationSettings()),
            replace = false,
        )

        assertFalse(updated.containsKey("imported"))
        assertFalse(state.state.value.containsKey("imported"))
    }

    @Test
    fun `replace invalidates every pending pre-import write`() {
        val state = ConversationSettingsState()
        val oldWrite = state.update("old") { it.copy(temperature = 0.1f) }

        val updated = state.applyImport(
            imported = mapOf("imported" to ConversationSettings(temperature = 0.8f)),
            replace = true,
        )

        assertEquals(setOf("imported"), updated.keys)
        assertFalse(state.isLatest(oldWrite))
    }

    @Test
    fun `empty replace clears the complete settings map`() {
        val state = ConversationSettingsState()
        state.acceptPersisted(
            mapOf("old" to ConversationSettings(temperature = 0.1f)),
        )

        assertEquals(emptyMap<String, ConversationSettings>(), state.applyImport(emptyMap(), true))
        assertEquals(emptyMap<String, ConversationSettings>(), state.state.value)
    }

    @Test
    fun `consecutive updates are immediately visible and preserve sibling toggles`() {
        val state = ConversationSettingsState()

        state.update("conversation") { it.copy(webSearchEnabled = false) }
        state.update("conversation") { it.copy(shellEnabled = false) }

        val settings = state.state.value.getValue("conversation")
        assertFalse(settings.webSearchEnabled ?: true)
        assertFalse(settings.shellEnabled ?: true)
    }

    @Test
    fun `stale persisted emission cannot overwrite pending optimistic settings`() {
        val state = ConversationSettingsState()
        state.acceptPersisted(
            mapOf("conversation" to ConversationSettings(webSearchEnabled = true)),
        )

        val write = state.update("conversation") {
            it.copy(webSearchEnabled = false, openAiWebSearchEnabled = false)
        }
        state.acceptPersisted(
            mapOf("conversation" to ConversationSettings(webSearchEnabled = true)),
        )

        assertEquals(
            ConversationSettings(
                webSearchEnabled = false,
                openAiWebSearchEnabled = false,
            ),
            state.state.value.getValue("conversation"),
        )

        state.complete(
            write,
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    openAiWebSearchEnabled = false,
                ),
            ),
        )
        assertEquals(false, state.state.value.getValue("conversation").webSearchEnabled)
    }

    @Test
    fun `older write completion keeps a newer update pending`() {
        val state = ConversationSettingsState()
        val webWrite = state.update("conversation") { it.copy(webSearchEnabled = false) }
        val shellWrite = state.update("conversation") { it.copy(shellEnabled = false) }

        state.complete(
            webWrite,
            mapOf("conversation" to ConversationSettings(webSearchEnabled = false)),
        )

        assertEquals(
            ConversationSettings(webSearchEnabled = false, shellEnabled = false),
            state.state.value.getValue("conversation"),
        )

        state.complete(
            shellWrite,
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    shellEnabled = false,
                ),
            ),
        )
        assertEquals(false, state.state.value.getValue("conversation").shellEnabled)
    }

    @Test
    fun `only the newest pending write remains eligible for persistence`() {
        val state = ConversationSettingsState()
        val first = state.update("conversation") { it.copy(webSearchEnabled = false) }
        val second = state.update("conversation") { it.copy(shellEnabled = false) }

        assertFalse(state.isLatest(first))
        assertEquals(true, state.isLatest(second))
    }
}
