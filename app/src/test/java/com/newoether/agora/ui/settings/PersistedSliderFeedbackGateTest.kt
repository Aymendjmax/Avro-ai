package com.newoether.agora.ui.settings

import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedSliderFeedbackGateTest {
    @Test
    fun activeGestureRejectsPersistenceEcho() {
        val gate = gate()

        gate.updateFromGesture(4f)

        assertFalse(gate.reconcile(2))
        assertEquals(4f, gate.displayed)
    }

    @Test
    fun staleEchoAfterCommitIsIgnoredUntilLatestValueArrives() {
        val gate = gate()
        gate.updateFromGesture(4f)
        gate.expectPersisted(4)

        assertFalse(gate.reconcile(2))
        assertEquals(4f, gate.displayed)
        assertTrue(gate.reconcile(4))
        assertEquals(4f, gate.displayed)
    }

    @Test
    fun newerGestureReplacesAnOlderPendingCommit() {
        val gate = gate()
        gate.updateFromGesture(3f)
        gate.expectPersisted(3)
        gate.updateFromGesture(5f)
        gate.expectPersisted(5)

        assertFalse(gate.reconcile(3))
        assertEquals(5f, gate.displayed)
        assertTrue(gate.reconcile(5))
    }

    private fun gate() = PersistedSliderFeedbackGate(
        initialPersisted = 1,
        toDisplay = Int::toFloat,
    )
}
