package com.newoether.agora.automation

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.util.DebugLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationWakeLockOwnerTest {
    @Before
    fun disableAndroidLoggingForJvmTests() {
        // DebugLog state is JVM-global; set it here so the result does not depend on test order.
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun disabledExecutionNeverAcquiresLease() = runTest {
        var acquired = false
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                acquired = true
                AutoCloseable {}
            },
        )

        val result = owner.whileHeld(enabled = false) { "done" }

        assertEquals("done", result)
        assertFalse(acquired)
    }

    @Test
    fun enabledExecutionReleasesLeaseAfterSuccessAndFailure() = runTest {
        var acquisitions = 0
        var releases = 0
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                acquisitions += 1
                AutoCloseable { releases += 1 }
            },
        )

        assertEquals("done", owner.whileHeld(enabled = true) { "done" })
        assertEquals(1, acquisitions)
        assertEquals(1, releases)

        val failure = runCatching {
            owner.whileHeld(enabled = true) { error("boom") }
        }
        assertTrue(failure.isFailure)
        assertEquals(2, acquisitions)
        assertEquals(2, releases)
    }

    @Test
    fun cancellationStillReleasesLease() = runTest {
        var releases = 0
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                AutoCloseable { releases += 1 }
            },
        )
        val execution = async {
            owner.whileHeld(enabled = true) { awaitCancellation() }
        }

        runCurrent()
        execution.cancelAndJoin()

        assertEquals(1, releases)
    }

    @Test
    fun acquireFailureDoesNotBlockAutomationExecution() = runTest {
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory { error("not available") },
        )

        assertEquals("done", owner.whileHeld(enabled = true) { "done" })
    }
}
