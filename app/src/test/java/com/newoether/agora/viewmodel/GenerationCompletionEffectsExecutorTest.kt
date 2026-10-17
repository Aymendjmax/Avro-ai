package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationCompletionEffectsExecutorTest {
    @Test
    fun `headless success preserves cleanup and background notification order`() {
        val events = mutableListOf<String>()
        val executor = executor(events, appInForeground = false)

        executor.execute(
            request(foregroundLeaseAcquired = true),
            callbacks(events, hasQueuedSends = { events += "queue"; false }),
        )

        assertEquals(
            listOf(
                "index:model:answer",
                "clear",
                "loading:false",
                "release:model",
                "queue",
                "foreground",
                "notify:conversation:SUCCESS:answer",
            ),
            events,
        )
    }

    @Test
    fun `foreground policy covers visibility errors compact and pending guidance`() {
        val events = mutableListOf<String>()
        val executor = executor(events, appInForeground = true)
        val callbacks = callbacks(events)
        listOf(
            request(conversationVisible = true),
            request(MessageStatus.ERROR, "Error: visible", conversationVisible = true),
            request(conversationVisible = false),
            request(MessageStatus.ERROR, "Error: provider detail", conversationVisible = false),
            request(isContextCompact = true, conversationVisible = false),
            request(
                MessageStatus.ERROR,
                "Error: compact detail",
                isContextCompact = true,
                conversationVisible = false,
            ),
            request(conversationVisible = false, hasPendingContinuation = true),
        ).forEach { executor.execute(it, callbacks) }
        executor.execute(
            request(conversationVisible = false),
            callbacks(events, hasQueuedSends = { true }),
        )

        assertEquals(
            listOf(
                "notify:conversation:SUCCESS:answer",
                "notify:conversation:ERROR:Error: provider detail",
                "notify:conversation:ERROR:Error: compact detail",
            ),
            events.filter { it.startsWith("notify:") },
        )
        assertEquals(0, events.count { it == "foreground" })
    }

    @Test
    fun `index failure cannot prevent terminal cleanup`() {
        val events = mutableListOf<String>()
        val executor = executor(events, appInForeground = false)
        val callbacks = GenerationCompletionEffectsCallbacks(
            onMessagePersisted = { _, _ ->
                events += "index"
                throw IllegalStateException("index failure")
            },
            onStreamClear = { events += "clear" },
            onLoadingChange = { events += "loading:$it" },
            hasQueuedSends = { events += "queue"; true },
        )

        executor.execute(
            request(foregroundLeaseAcquired = true, conversationVisible = false),
            callbacks,
        )

        assertEquals(listOf("index", "clear", "loading:false", "release:model", "queue"), events)
    }

    private fun executor(
        events: MutableList<String>,
        appInForeground: Boolean,
    ) = GenerationCompletionEffectsExecutor(
        isAppInForeground = { events += "foreground"; appInForeground },
        releaseForegroundLease = { events += "release:$it" },
        notify = { text, conversationId, status ->
            events += "notify:$conversationId:$status:$text"
        },
    )

    private fun request(
        status: MessageStatus = MessageStatus.SUCCESS,
        notificationText: String = "answer",
        foregroundLeaseAcquired: Boolean = false,
        isContextCompact: Boolean = false,
        conversationVisible: Boolean? = null,
        hasPendingContinuation: Boolean = false,
    ) = GenerationCompletionEffectsRequest(
        terminalPersisted = true,
        status = status,
        text = "answer",
        notificationText = notificationText,
        conversationId = "conversation",
        modelMessageId = "model",
        foregroundLeaseAcquired = foregroundLeaseAcquired,
        isContextCompact = isContextCompact,
        conversationVisible = conversationVisible,
        hasPendingContinuation = hasPendingContinuation,
    )

    private fun callbacks(
        events: MutableList<String>,
        hasQueuedSends: () -> Boolean = { false },
    ) = GenerationCompletionEffectsCallbacks(
        onMessagePersisted = { id, text -> events += "index:$id:$text" },
        onStreamClear = { events += "clear" },
        onLoadingChange = { events += "loading:$it" },
        hasQueuedSends = hasQueuedSends,
    )
}
