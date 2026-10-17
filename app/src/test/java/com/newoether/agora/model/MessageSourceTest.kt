package com.newoether.agora.model

import com.newoether.agora.data.NativeBackupFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSourceTest {
    private val askUser = MessageSource(
        kind = MessageSource.Kind.ASK_USER,
        askUser = listOf(
            MessageSource.AskUserItem(question = "Which port?", answer = "8080"),
            MessageSource.AskUserItem(question = "Restart now?", answer = null),
        ),
    )

    @Test
    fun `codec round trips every kind`() {
        listOf(MessageSource.TASK, MessageSource.LOOP, askUser).forEach { source ->
            assertEquals(source, MessageSource.decode(MessageSource.encode(source)))
        }
        assertEquals("""{"kind":"task"}""", MessageSource.encode(MessageSource.TASK))
        assertNull(MessageSource.encode(null))
    }

    @Test
    fun `unreadable data decodes to no source`() {
        assertNull(MessageSource.decode(null))
        assertNull(MessageSource.decode(""))
        assertNull(MessageSource.decode("not json"))
        assertNull(MessageSource.decode("""{"kind":"future_kind"}"""))
        // Only ask_user may carry question items.
        assertNull(MessageSource.decode("""{"kind":"task","askUser":[{"question":"q"}]}"""))
    }

    @Test
    fun `import keeps a valid source only on user rows`() {
        val raw = MessageSource.encode(askUser)
        assertEquals(raw, MessageSource.sanitizeImported(raw, Participant.USER))
        assertNull(MessageSource.sanitizeImported(raw, Participant.MODEL))
        assertNull(MessageSource.sanitizeImported("garbage", Participant.USER))
        assertNull(MessageSource.sanitizeImported(null, Participant.USER))
        // Unknown extra keys are dropped by re-encoding.
        assertEquals(
            """{"kind":"loop"}""",
            MessageSource.sanitizeImported("""{"kind":"loop","extra":1}""", Participant.USER),
        )
    }

    @Test
    fun `backup v6 keeps the v5 secrets boundary`() {
        assertEquals(6, NativeBackupFormat.CURRENT_VERSION)
        assertEquals(5, NativeBackupFormat.SEPARATE_SECRETS_SINCE_VERSION)
        assertTrue(NativeBackupFormat.isSupported(5))
    }
}
