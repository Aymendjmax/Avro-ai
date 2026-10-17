package com.newoether.agora.ui.tasks

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.model.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryPreviewStateTest {
    @Test
    fun failedRestoreWaitsForCoverageThenResumesLiveHistoryInEitherOrder() {
        for (coverFirst in listOf(false, true)) {
            val session = TaskEditorSessionViewModel()
            session.open(task(), isNew = false)
            val retained = listOf(execution("history"))
            val live = retained + execution("new-result")
            session.retainExecutionHistory("task-1", retained)
            session.openHistory("history", "deleted-origin", false)
            session.observeHistoryDestination("history", false, false)
            session.requestHistoryReturn()
            var onFailure: () -> Unit = { error("Restoration was not requested") }
            val restore = requireNotNull(session.beginHistoryReturnRestore { _, failure ->
                onFailure = failure
            })
            if (coverFirst) session.markHistoryReturnOverlayCovered(restore.generation)
            else onFailure()
            assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
            assertEquals(
                retained,
                taskExecutionHistoryForPresentation(session.historyPreview.phase, retained, live),
            )

            if (coverFirst) onFailure()
            else session.markHistoryReturnOverlayCovered(restore.generation)
            assertEquals(TaskHistoryPreviewPhase.IDLE, session.historyPreview.phase)
            assertEquals(
                live,
                taskExecutionHistoryForPresentation(session.historyPreview.phase, retained, live),
            )
        }
    }

    @Test
    fun staleRestoreFailureCannotReleaseANewerPreviewOrReturn() {
        var state = TaskHistoryPreviewState.Idle.open("task", "history", "origin", false)
            .requestReturn().beginReturnRestore()
        val staleGeneration = state.generation
        state = state.open("task", "new-history", "history", false)
        assertEquals(state, state.markReturnRestoreFailed(staleGeneration))
        state = state.requestReturn().beginReturnRestore()
            .markReturnOverlayCovered(state.generation + 1)
        assertEquals(state, state.markReturnRestoreFailed(staleGeneration))
        assertEquals("origin", state.originConversationId)
    }

    @Test
    fun previousTaskFailureCannotSettleReturnAfterSessionReset() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.openHistory("history-1", "origin-1", false)
        session.requestHistoryReturn()
        var oldFailure: () -> Unit = { error("Restoration was not requested") }
        session.beginHistoryReturnRestore { _, failure -> oldFailure = failure }
        session.clear()
        session.open(task("task-2"), isNew = false)
        session.openHistory("history-2", "origin-2", false)
        session.requestHistoryReturn()
        val restoring = requireNotNull(session.beginHistoryReturnRestore())
        session.markHistoryReturnOverlayCovered(restoring.generation)
        val expected = session.historyPreview

        oldFailure()

        assertEquals(expected, session.historyPreview)
        assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
        assertEquals("task-2", session.historyPreview.taskId)
    }

    @Test
    fun historyRoundTripRetainsConfigurationScrollAndOriginalConversation() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Edited name")
        session.updatePrompt("Edited prompt")
        session.updateModelId("provider:model")
        session.updateSchedule("30 9 * * *", null)
        session.updateScheduleEditorMode(ScheduleEditorMode.DAILY)
        session.updateEnabled(false)
        session.updateScroll(7, 42)

        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "conversation-1",
            isNewChatMode = false,
        )
        assertEquals(TaskHistoryPreviewPhase.VIEWING, session.historyPreview.phase)
        assertEquals("conversation-1", session.historyPreview.originConversationId)
        assertEquals("history-1", session.historyPreview.previewConversationId)
        assertFalse(session.historyPreview.originWasNewChat)

        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = false,
            isSwitching = false,
        )
        session.requestHistoryReturn()
        val restore = requireNotNull(session.beginHistoryReturnRestore())
        assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
        session.markHistoryReturnOverlayCovered(restore.generation)
        session.observeHistoryDestination(
            "conversation-1",
            isNewChatMode = false,
            isSwitching = true,
        )
        assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
        session.observeHistoryDestination(
            "conversation-1",
            isNewChatMode = false,
            isSwitching = false,
        )

        val current = requireNotNull(session.current(original.copy(nextRunAt = 999L)))
        assertEquals("Edited name", current.name)
        assertEquals("Edited prompt", current.prompt)
        assertEquals("provider:model", current.modelId)
        assertEquals("30 9 * * *", current.cronExpr)
        assertFalse(current.enabled)
        assertEquals(999L, current.nextRunAt)
        assertEquals(7, session.detailListIndex)
        assertEquals(42, session.detailListOffset)
        assertEquals(TaskHistoryPreviewPhase.IDLE, session.historyPreview.phase)
    }

    @Test
    fun previewIgnoresOriginUntilTargetAndSuccessfulForkSettlesIt() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "origin",
            isNewChatMode = false,
        )

        session.observeHistoryDestination("origin", isNewChatMode = false, isSwitching = false)
        assertFalse(session.historyPreview.destinationObserved)
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = false,
            isSwitching = false,
        )
        assertTrue(session.historyPreview.destinationObserved)
        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = false,
            isSwitching = false,
        )
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination("fork-1", isNewChatMode = false, isSwitching = false)
        assertEquals(TaskHistoryPreviewPhase.IDLE, session.historyPreview.phase)
    }

    @Test
    fun newChatSettlesOnlyAfterPreviewDestinationWasObserved() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = null,
            isNewChatMode = true,
        )

        session.observeHistoryDestination(null, isNewChatMode = true, isSwitching = false)
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = false,
            isSwitching = false,
        )
        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = true,
            isSwitching = false,
        )
        assertEquals(TaskHistoryPreviewPhase.IDLE, session.historyPreview.phase)
    }

    @Test
    fun returnNeedsBothOverlayCoverageAndSettledRestoration() {
        var state = TaskHistoryPreviewState.Idle.open(
            taskId = "task",
            previewConversationId = "history",
            currentConversationId = "origin",
            isNewChatMode = false,
        )
        state = state.observeDestination("history", isNewChatMode = false, isSwitching = false)
        state = state.requestReturn().beginReturnRestore()
        val generation = state.generation

        state = state.markReturnOverlayCovered(generation)
        assertEquals(TaskHistoryPreviewPhase.RETURNING, state.phase)
        state = state.observeDestination("origin", isNewChatMode = false, isSwitching = true)
        assertEquals(TaskHistoryPreviewPhase.RETURNING, state.phase)
        state = state.observeDestination("origin", isNewChatMode = false, isSwitching = false)

        assertEquals(TaskHistoryPreviewPhase.IDLE, state.phase)
    }

    @Test
    fun oneHundredAdversarialHistoryReturnCyclesNeverReleaseToHistory() {
        var state = TaskHistoryPreviewState.Idle
        repeat(100) { index ->
            val originWasNewChat = index % 2 == 0
            val originId = if (originWasNewChat) null else "origin-$index"
            val historyId = "history-$index"
            state = state.open(
                taskId = "task",
                previewConversationId = historyId,
                currentConversationId = originId,
                isNewChatMode = originWasNewChat,
            )
            state = state.observeDestination(
                historyId,
                isNewChatMode = false,
                isSwitching = false,
            )
            state = state.requestReturn().beginReturnRestore()
            val returnGeneration = state.generation
            state = state.markReturnOverlayCovered(returnGeneration)
            state = state.observeDestination(
                historyId,
                isNewChatMode = false,
                isSwitching = false,
            )
            assertEquals(TaskHistoryPreviewPhase.RETURNING, state.phase)
            state = state.observeDestination(
                currentConversationId = originId,
                isNewChatMode = originWasNewChat,
                isSwitching = true,
            )
            assertEquals(TaskHistoryPreviewPhase.RETURNING, state.phase)
            state = state.observeDestination(
                currentConversationId = originId,
                isNewChatMode = originWasNewChat,
                isSwitching = false,
            )
            assertEquals(TaskHistoryPreviewPhase.IDLE, state.phase)
        }
    }

    @Test
    fun newHistorySupersedesPriorReturnWithoutReplacingCapturedOrigin() {
        var state = TaskHistoryPreviewState.Idle.open(
            taskId = "task",
            previewConversationId = "history-1",
            currentConversationId = "origin",
            isNewChatMode = false,
        )
        state = state.requestReturn().beginReturnRestore()
        val staleReturnGeneration = state.generation
        state = state.open(
            taskId = "task",
            previewConversationId = "history-2",
            currentConversationId = "history-1",
            isNewChatMode = false,
        )

        assertEquals(TaskHistoryPreviewPhase.VIEWING, state.phase)
        assertEquals("origin", state.originConversationId)
        assertEquals("history-2", state.previewConversationId)
        assertFalse(state.restoreRequested)
        assertEquals(state, state.markReturnOverlayCovered(staleReturnGeneration))
    }

    @Test
    fun executionSnapshotIsCopiedRetainedForRoundTripAndBoundToActiveTask() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        val mutableExecutions = mutableListOf(execution("history-1"))

        session.retainExecutionHistory("task-1", mutableExecutions)
        mutableExecutions += execution("history-2")
        assertEquals(listOf(execution("history-1")), session.executionHistoryFor("task-1"))

        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "origin",
            isNewChatMode = false,
        )
        session.observeHistoryDestination(
            "history-1",
            isNewChatMode = false,
            isSwitching = false,
        )
        session.requestHistoryReturn()
        val restore = requireNotNull(session.beginHistoryReturnRestore())
        session.markHistoryReturnOverlayCovered(restore.generation)
        session.observeHistoryDestination(
            "origin",
            isNewChatMode = false,
            isSwitching = false,
        )
        assertEquals(1, session.executionHistoryFor("task-1")?.size)

        session.open(task(id = "task-2"), isNew = false)
        assertNull(session.executionHistoryFor("task-1"))
        assertNull(session.executionHistoryFor("task-2"))
    }

    @Test
    fun reopeningTheSameActiveTaskDoesNotOverwriteActivityRecreatedDraft() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Unsaved edit")

        session.open(original.copy(name = "Room update"), isNew = false)

        assertEquals("Unsaved edit", session.name)
        assertEquals(original.id, session.activeTaskId)
    }

    @Test
    fun clearMakesTheSessionInactiveAndNormalReopenStartsFresh() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Discarded edit")
        session.updateScroll(5, 24)
        session.retainExecutionHistory(original.id, listOf(execution("history-1")))
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = null,
            isNewChatMode = true,
        )

        session.clear()

        assertNull(session.activeTaskId)
        assertNull(session.current(original))
        assertNull(session.executionHistoryFor(original.id))
        assertEquals(0, session.detailListIndex)
        assertEquals(0, session.detailListOffset)
        assertEquals(TaskHistoryPreviewPhase.IDLE, session.historyPreview.phase)

        session.open(original, isNew = false)
        assertEquals(original.name, session.name)
        assertEquals(0, session.detailListIndex)
        assertEquals(0, session.detailListOffset)
    }

    @Test
    fun freshViewModelDoesNotRestoreProcessState() {
        val previousProcess = TaskEditorSessionViewModel().apply {
            open(task(), isNew = false)
            updateName("Unsaved edit")
            updateScroll(9, 81)
            retainExecutionHistory("task-1", listOf(execution("history-1")))
        }
        val newProcess = TaskEditorSessionViewModel()

        assertEquals("Unsaved edit", previousProcess.name)
        assertNull(newProcess.activeTaskId)
        assertNull(newProcess.executionHistoryFor("task-1"))
        assertEquals(0, newProcess.detailListIndex)
        assertEquals(0, newProcess.detailListOffset)
        assertEquals(TaskHistoryPreviewPhase.IDLE, newProcess.historyPreview.phase)
    }

    private fun task(id: String = "task-1") = TaskEntity(
        id = id,
        name = "Task",
        prompt = "Prompt",
        cronExpr = "0 9 * * *",
        nextRunAt = 123L,
        enabled = true,
    )

    private fun execution(id: String) = TaskManager.ExecutionSummary(
        conversation = ChatConversation(id = id, title = id),
        preview = id,
        status = null,
        timestamp = 123L,
    )
}
