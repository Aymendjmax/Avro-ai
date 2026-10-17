package com.newoether.agora.viewmodel

import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts for the shell confirmation handshake: only an explicit answer decides a request, the
 * app never answers one on the user's behalf, and an answer can only reach the prompt it was made
 * for (the notification action and the dialog share this entry point).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellConfirmationControllerTest {
    @Test
    fun `cancelled prompt cannot grant session trust through a late answer`() = runTest {
        val controller = controller()
        val first = launch { controller.confirm("Quantum", "first") }
        runCurrent()
        val old = requireNotNull(controller.pendingShellCommand.value)
        first.cancel()
        first.join()
        controller.resolve(old.id, allow = true, alwaysAllowServer = true)
        val second = launch { controller.confirm("Quantum", "second") }
        runCurrent()
        val pending = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(pending.id, allow = false)
        second.join()
    }

    @Test
    fun `expired prompt cannot grant session trust through a late answer`() = runTest {
        val controller = controller()
        val first = launch { controller.confirm("Quantum", "first") }
        runCurrent()
        val old = requireNotNull(controller.pendingShellCommand.value)
        advanceTimeBy(Constants.SHELL_CONFIRM_TIMEOUT_MS)
        runCurrent()
        first.join()
        controller.resolve(old.id, allow = true, alwaysAllowServer = true)
        val second = launch { controller.confirm("Quantum", "second") }
        runCurrent()
        val pending = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(pending.id, allow = false)
        second.join()
    }

    @Test
    fun `notification identities differ between controller lifetimes`() {
        assertTrue(controller().notificationSessionId != controller().notificationSessionId)
    }

    @Test
    fun `completion callback cannot clear the next pending prompt`() = runTest {
        val controller = controller()
        val first = launch { controller.confirm("Quantum", "first") }
        runCurrent()
        val oldPrompt = requireNotNull(controller.pendingShellCommand.value)
        val second = launch { controller.confirm("tinybox", "second") }
        runCurrent()
        oldPrompt.deferred.invokeOnCompletion { runCurrent() }
        controller.resolve(oldPrompt.id, allow = false)
        runCurrent()
        val next = requireNotNull(controller.pendingShellCommand.value)
        assertTrue(next.id != oldPrompt.id)
        controller.resolve(next.id, allow = false)
        first.join()
        second.join()
    }


    private fun controller(enabled: Boolean = true) = ShellConfirmationController(
        confirmEnabled = MutableStateFlow(enabled),
        persistEnabled = {},
    )

    @Test
    fun `unanswered request stays pending and an explicit allow runs it`() = runTest {
        val controller = controller()
        var allowed: Boolean? = null
        val worker = launch { allowed = controller.confirm("Quantum", "ls -la") }

        runCurrent()

        assertNull(allowed)
        val pending = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(pending.id, allow = true)

        worker.join()
        assertEquals(true, allowed)
        assertNull(controller.pendingShellCommand.value)
    }

    @Test
    fun `backgrounding the app leaves the request pending instead of refusing it`() = runTest {
        val observedForeground = AppForegroundTracker.isInForeground
        val controller = controller()
        var allowed: Boolean? = null
        val worker = launch { allowed = controller.confirm("Quantum", "whoami") }
        try {
            runCurrent()

            AppForegroundTracker.setInForeground(false)
            runCurrent()
            assertNull(allowed)
            assertNotNull(controller.pendingShellCommand.value)

            AppForegroundTracker.setInForeground(true)
            runCurrent()
            assertNull(allowed)

            val pending = requireNotNull(controller.pendingShellCommand.value)
            controller.resolve(pending.id, allow = false)
            worker.join()
            assertEquals(false, allowed)
        } finally {
            AppForegroundTracker.setInForeground(observedForeground)
        }
    }

    @Test
    fun `an answer carrying a stale prompt id is ignored`() = runTest {
        val controller = controller()
        var allowed: Boolean? = null
        val worker = launch { allowed = controller.confirm("Quantum", "uptime") }

        runCurrent()
        val pending = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(pending.id + 991L, allow = true)
        runCurrent()

        assertNull(allowed)
        assertNotNull(controller.pendingShellCommand.value)

        controller.resolve(pending.id, allow = false)
        worker.join()
        assertEquals(false, allowed)
    }

    @Test
    fun `an answered prompt cannot decide the request that replaced it`() = runTest {
        val controller = controller()
        var first: Boolean? = null
        var second: Boolean? = null

        val firstWorker = launch { first = controller.confirm("Quantum", "echo one") }
        runCurrent()
        val firstPrompt = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(firstPrompt.id, allow = false)
        firstWorker.join()
        assertEquals(false, first)

        val secondWorker = launch { second = controller.confirm("Quantum", "echo two") }
        runCurrent()
        controller.resolve(firstPrompt.id, allow = true)
        runCurrent()

        assertNull(second)
        val secondPrompt = requireNotNull(controller.pendingShellCommand.value)
        assertTrue(secondPrompt.id != firstPrompt.id)

        controller.resolve(secondPrompt.id, allow = true)
        secondWorker.join()
        assertEquals(true, second)
    }

    @Test
    fun `an unanswered request is refused only when the safety timeout expires`() = runTest {
        val controller = controller()
        var allowed: Boolean? = null
        val worker = launch { allowed = controller.confirm("Quantum", "long job") }

        runCurrent()
        assertNotNull(controller.pendingShellCommand.value)

        advanceTimeBy(Constants.SHELL_CONFIRM_TIMEOUT_MS - 1_000L)
        runCurrent()
        assertNotNull(controller.pendingShellCommand.value)

        advanceTimeBy(2_000L)
        runCurrent()
        worker.join()
        assertEquals(false, allowed)
        assertNull(controller.pendingShellCommand.value)
    }

    @Test
    fun `always allow trusts that server for the rest of the session`() = runTest {
        val controller = controller()
        val firstWorker = launch { controller.confirm("Quantum", "ls") }
        runCurrent()
        val prompt = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(prompt.id, allow = true, alwaysAllowServer = true)
        firstWorker.join()

        assertEquals(true, controller.confirm("Quantum", "pwd"))
        assertNull(controller.pendingShellCommand.value)

        val otherWorker = launch { controller.confirm("tinybox", "pwd") }
        runCurrent()
        val otherPrompt = requireNotNull(controller.pendingShellCommand.value)
        controller.resolve(otherPrompt.id, allow = false)
        otherWorker.join()
    }

    @Test
    fun `disabled confirmation answers without prompting`() = runTest {
        val controller = controller(enabled = false)

        assertEquals(true, controller.confirm("Quantum", "ls"))
        assertNull(controller.pendingShellCommand.value)
    }

    @Test
    fun `setEnabled writes through the injected sink`() = runTest {
        var persisted: Boolean? = null
        val controller = ShellConfirmationController(MutableStateFlow(true), { persisted = it })

        controller.setEnabled(true)
        assertEquals(true, persisted)
        controller.setEnabled(false)
        assertEquals(false, persisted)
    }
}
