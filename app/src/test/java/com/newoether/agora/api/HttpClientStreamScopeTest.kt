package com.newoether.agora.api

import com.newoether.agora.viewmodel.StreamScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HttpClientStreamScopeTest {

    @Test
    fun parallelCoroutines_keepIndependentStreamScopesAcrossSuspension() = runTest {
        val scopeA = StreamScope()
        val scopeB = StreamScope()
        val traceA = HttpClient.RequestTrace("request-a", "chat")
        val traceB = HttpClient.RequestTrace("request-b", "task")
        val aSuspended = CompletableDeferred<Unit>()
        val bObserved = CompletableDeferred<Unit>()
        var observedA: StreamScope? = null
        var observedB: StreamScope? = null
        var observedTraceA: HttpClient.RequestTrace? = null
        var observedTraceB: HttpClient.RequestTrace? = null

        val jobA = launch {
            HttpClient.withStreamScope(scopeA, traceA) {
                aSuspended.complete(Unit)
                bObserved.await()
                observedA = HttpClient.boundStreamScope()
                observedTraceA = HttpClient.boundRequestTrace()
            }
        }
        aSuspended.await()
        val jobB = launch {
            HttpClient.withStreamScope(scopeB, traceB) {
                observedB = HttpClient.boundStreamScope()
                observedTraceB = HttpClient.boundRequestTrace()
                bObserved.complete(Unit)
            }
        }

        jobA.join()
        jobB.join()

        assertSame(scopeA, observedA)
        assertSame(scopeB, observedB)
        assertSame(traceA, observedTraceA)
        assertSame(traceB, observedTraceB)
        assertNull(HttpClient.boundStreamScope())
        assertNull(HttpClient.boundRequestTrace())
    }
}
