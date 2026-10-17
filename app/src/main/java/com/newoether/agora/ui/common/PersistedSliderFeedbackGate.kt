package com.newoether.agora.ui.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps a slider gesture authoritative until its final persistence echo arrives. */
@Stable
internal class PersistedSliderFeedbackGate<Display, Persisted>(
    initialPersisted: Persisted,
    private val toDisplay: (Persisted) -> Display,
    private val equivalent: (Persisted, Persisted) -> Boolean = { left, right -> left == right },
) {
    var displayed by mutableStateOf(toDisplay(initialPersisted))
        private set

    private var interacting = false
    private var hasPendingWrite = false
    private var pendingPersisted = initialPersisted

    fun updateFromGesture(value: Display) {
        interacting = true
        displayed = value
    }

    fun expectPersisted(value: Persisted, finalDisplay: Display = displayed) {
        interacting = false
        displayed = finalDisplay
        pendingPersisted = value
        hasPendingWrite = true
    }

    fun settleWithoutWrite(persisted: Persisted, finalDisplay: Display = toDisplay(persisted)) {
        interacting = false
        hasPendingWrite = false
        pendingPersisted = persisted
        displayed = finalDisplay
    }

    fun reconcile(persisted: Persisted): Boolean {
        if (interacting) return false
        if (hasPendingWrite) {
            if (!equivalent(persisted, pendingPersisted)) return false
            hasPendingWrite = false
        }
        pendingPersisted = persisted
        displayed = toDisplay(persisted)
        return true
    }
}
