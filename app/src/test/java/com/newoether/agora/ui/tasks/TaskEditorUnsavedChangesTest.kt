package com.newoether.agora.ui.tasks

import com.newoether.agora.data.local.TaskEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditorUnsavedChangesTest {
    private fun task() = TaskEntity(id = "t", name = "Name", prompt = "Prompt", cronExpr = "0 9 * * *", nextRunAt = 0L)

    @Test
    fun freshlyOpenedTaskHasNoUnsavedChanges() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun editingAFieldMarksTheDraftDirtyAndRevertingClearsIt() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)

        session.updatePrompt("Changed")
        assertTrue(session.hasUnsavedChanges)

        session.updatePrompt("Prompt")
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun trailingWhitespaceInTheNameIsNotAChangeBecauseSaveTrimsIt() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.updateName("Name  ")
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun scheduleAndEnabledChangesCount() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.updateEnabled(!task().enabled)
        assertTrue(session.hasUnsavedChanges)

        session.updateEnabled(task().enabled)
        session.updateSchedule("0 10 * * *", null)
        assertTrue(session.hasUnsavedChanges)
    }

    @Test
    fun clearedSessionReportsNoUnsavedChanges() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.updatePrompt("Changed")
        session.clear()
        assertFalse(session.hasUnsavedChanges)
    }
}
