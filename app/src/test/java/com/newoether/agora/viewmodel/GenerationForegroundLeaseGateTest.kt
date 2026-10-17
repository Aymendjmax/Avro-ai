package com.newoether.agora.viewmodel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationForegroundLeaseGateTest {
    @Test
    fun localGenerationContinuesWithoutLeaseWhenForegroundServiceIsUnavailable() = runTest {
        var acquireCalled = false
        val acquired = acquireGenerationForegroundLease(managedExternally = false) {
            acquireCalled = true
            false
        }
        assertTrue(acquireCalled)
        assertFalse(acquired)
    }

    @Test
    fun localGenerationTracksSuccessfullyAcquiredForegroundLease() = runTest {
        assertTrue(
            acquireGenerationForegroundLease(managedExternally = false) { true }
        )
    }

    @Test
    fun externallyManagedGenerationDoesNotAcquireAgoraLease() = runTest {
        var acquireCalled = false
        val acquired = acquireGenerationForegroundLease(managedExternally = true) {
            acquireCalled = true
            true
        }
        assertFalse(acquireCalled)
        assertEquals(false, acquired)
    }
}
