package com.newoether.agora.viewmodel

import com.newoether.agora.automation.LoopManager
import com.newoether.agora.data.local.LoopEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Observe the selected timer using the same ViewModel-owned scope and eager subscription. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun LoopManager.observeCurrentLoop(
    currentConversationId: StateFlow<String?>,
    scope: CoroutineScope,
): StateFlow<LoopEntity?> = currentConversationId
    .flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            combine(
                loopForConversation(id),
                runningConversationIds,
            ) { loop, _ ->
                // Visibility tracks the TIMER only. The card is a schedule indicator, so once
                // the schedule is inactive it must disappear at once, even mid-cycle.
                //
                // It deliberately does not stay up for a running worker: an in-flight
                // generation is already stoppable through the composer's Stop button, so
                // keeping the card alive for that would make one control appear to own two
                // unrelated lifetimes.
                loop?.takeIf { it.active }
            }
        }
    }
    .stateIn(scope, SharingStarted.Eagerly, null)
