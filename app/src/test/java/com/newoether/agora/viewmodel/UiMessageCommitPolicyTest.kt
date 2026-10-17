package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class UiMessageCommitPolicyTest {
    @Test
    fun roomFirstCommit_replacesRowsByIdWithoutAppendingDuplicates() {
        val existing = listOf(
            message("before", "before"),
            message("user", "room user", Participant.USER),
            message("model", "room placeholder", Participant.MODEL),
        )
        val committed = listOf(
            message("user", "controller user", Participant.USER),
            message("model", "controller placeholder", Participant.MODEL),
        )

        val merged = UiMessageCommitPolicy.upsert(existing, committed)

        assertEquals(listOf("before", "user", "model"), merged.map { it.id })
        assertEquals("controller user", merged.single { it.id == "user" }.text)
        assertEquals("controller placeholder", merged.single { it.id == "model" }.text)
    }

    @Test
    fun replacingOneExistingRowPreservesEverySuffixRow() {
        val before = message("before", "before")
        val target = message("target", "old text")
        val suffix = message("suffix", "must survive")
        val replacement = target.copy(text = "", status = com.newoether.agora.model.MessageStatus.SENDING)

        val merged = UiMessageCommitPolicy.upsert(
            existing = listOf(before, target, suffix),
            committed = listOf(replacement),
        )

        assertEquals(listOf(before, replacement, suffix), merged)
    }

    @Test
    fun preexistingDuplicateIds_areCollapsedEvenWithoutReplacement() {
        val duplicate = message("user", "same", Participant.USER)

        val merged = UiMessageCommitPolicy.upsert(
            existing = listOf(duplicate, duplicate, message("model", "answer", Participant.MODEL)),
            committed = emptyList(),
        )

        assertEquals(listOf("user", "model"), merged.map { it.id })
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant = Participant.MODEL,
    ) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
        timestamp = id.hashCode().toLong(),
        runId = "run",
    )
}
