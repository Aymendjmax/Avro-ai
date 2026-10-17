package com.newoether.agora.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskHistoryBackOwnershipTest {
    private val opening = TaskHistoryPreviewState.Idle.open("task", "preview", "home", false)

    @Test fun pendingOrFailedHistoryLoadCannotRedirectHomeBack() {
        assertNull(opening.backTaskId("home", false))
        assertNull(opening.backTaskId(null, true))
    }

    @Test fun onlyTheCurrentlyDisplayedHistoryOwnsReturnToTasks() {
        assertEquals("task", opening.backTaskId("preview", false))
        val viewing = opening.observeDestination("preview", false, false)
        assertNull(viewing.backTaskId("other", false))
        assertNull(viewing.backTaskId("preview", true))
        assertNull(viewing.backTaskId(null, true))
    }

    @Test fun returningOrSettledPreviewCannotOpenAnotherPageFromHome() {
        val returning = opening.requestReturn().beginReturnRestore()
        assertNull(returning.backTaskId("home", false))
        assertNull(returning.backTaskId("preview", false))
        assertNull(TaskHistoryPreviewState.Idle.backTaskId("home", false))
    }
}
