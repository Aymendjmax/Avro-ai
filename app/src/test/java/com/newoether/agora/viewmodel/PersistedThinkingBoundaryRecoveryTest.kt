package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PersistedThinkingBoundaryRecoveryTest {
    @Test
    fun `observed thought tool thought answer error row is recovered without losing metadata`() {
        val segments = listOf(
            MessageSegment(type = "answer"),
            MessageSegment(
                type = "thought",
                content = "first thought",
                signature = "sig-1",
                signatureProvider = "LMU Claude",
            ),
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = "{}",
                toolResult = "result",
            ),
            MessageSegment(
                type = "thought",
                content = "second thought</thinking>没有理他。",
                signature = "sig-2",
                signatureProvider = "LMU Claude",
            ),
            MessageSegment(
                type = "error",
                content = "The response reached the output token limit.",
            ),
        )

        val recovered = recoverPersistedThinkingBoundary(
            participant = Participant.MODEL,
            text = "",
            thoughts = "first thoughtsecond thought</thinking>没有理他。",
            segments = segments,
        )

        assertEquals("没有理他。", recovered.text)
        assertEquals("first thoughtsecond thought", recovered.thoughts)
        assertEquals(
            listOf("answer", "thought", "tool", "thought", "answer", "error"),
            recovered.segments?.map { it.type },
        )
        assertEquals("second thought", recovered.segments?.get(3)?.content)
        assertEquals("sig-2", recovered.segments?.get(3)?.signature)
        assertEquals("LMU Claude", recovered.segments?.get(3)?.signatureProvider)
        assertEquals("没有理他。", recovered.segments?.get(4)?.content)
    }

    @Test
    fun `real durable answer prevents compatibility rewrite`() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>wrong"),
            MessageSegment(type = "answer", content = "answer"),
        )

        val recovered = recoverPersistedThinkingBoundary(
            participant = Participant.MODEL,
            text = "answer",
            thoughts = "reason</thinking>wrong",
            segments = segments,
        )

        assertEquals("answer", recovered.text)
        assertEquals("reason</thinking>wrong", recovered.thoughts)
        assertSame(segments, recovered.segments)
    }

    @Test
    fun `code literal and close without answer suffix are not rewritten`() {
        val codeLiteral = listOf(
            MessageSegment(
                type = "thought",
                content = "`</thinking>`\n```\n</thinking>\n```",
            )
        )
        val noSuffix = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>"),
        )

        assertSame(
            codeLiteral,
            recoverPersistedThinkingBoundary(Participant.MODEL, "", codeLiteral.single().content, codeLiteral).segments,
        )
        assertSame(
            noSuffix,
            recoverPersistedThinkingBoundary(Participant.MODEL, "", noSuffix.single().content, noSuffix).segments,
        )
    }
}
