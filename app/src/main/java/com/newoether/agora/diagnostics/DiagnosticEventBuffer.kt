package com.newoether.agora.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Serializes capture state changes and durable event writes on one bounded IO command queue. */
internal class DiagnosticEventBuffer(
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initializationLock = Any()
    private val pendingDroppedEvents = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(DiagnosticSnapshot())

    @Volatile
    private var initializationStarted = false

    @Volatile
    private var commandChannel: Channel<Command>? = null

    @Volatile
    private var captureState = DiagnosticCaptureState.IDLE

    private var consumer: Job? = null

    val snapshots: StateFlow<DiagnosticSnapshot> = mutableSnapshot.asStateFlow()
    val isCaptureActive: Boolean get() = captureState == DiagnosticCaptureState.RUNNING

    init {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY)
    }

    suspend fun initialize(
        store: DiagnosticCaptureStore,
        scope: CoroutineScope,
    ) {
        synchronized(initializationLock) {
            check(!initializationStarted) { "Diagnostic capture is already initialized" }
            initializationStarted = true
        }
        require(scope.isActive) { "Diagnostic capture scope is not active" }
        val restored = try {
            withContext(ioDispatcher) { store.load() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiagnosticStoredState()
        }
        val channel = Channel<Command>(
            capacity = queueCapacity,
            onUndeliveredElement = { command ->
                command.fail(CancellationException("Diagnostic capture queue stopped"))
            },
        )
        commandChannel = channel
        publish(restored)
        consumer = scope.launch(ioDispatcher) {
            consume(store, restored, channel)
        }
    }

    suspend fun start(): DiagnosticSnapshot = submit { response -> Command.Start(response) }

    suspend fun pause(): DiagnosticSnapshot = submit { response -> Command.Pause(response) }

    suspend fun clear(): DiagnosticSnapshot = submit { response -> Command.Clear(response) }

    suspend fun disableAndClear(): DiagnosticSnapshot =
        submit { response -> Command.DisableAndClear(response) }

    suspend fun flush(): DiagnosticSnapshot = submit { response -> Command.Flush(response) }

    /** The factory must close only over credential-sanitized values. */
    fun record(eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent) {
        if (!isCaptureActive) return
        val channel = commandChannel
        if (channel == null || channel.trySend(Command.Record(eventFactory)).isFailure) {
            pendingDroppedEvents.incrementAndGet()
        }
    }

    private suspend fun submit(
        commandFactory: (CompletableDeferred<DiagnosticSnapshot>) -> ControlCommand,
    ): DiagnosticSnapshot {
        val channel = checkNotNull(commandChannel) { "Diagnostic capture is not initialized" }
        val response = CompletableDeferred<DiagnosticSnapshot>()
        channel.send(commandFactory(response))
        return response.await()
    }

    private suspend fun consume(
        store: DiagnosticCaptureStore,
        initialState: DiagnosticStoredState,
        channel: Channel<Command>,
    ) {
        var stored = initialState
        var pendingCommand: Command? = null
        try {
            while (true) {
                val command = pendingCommand?.also { pendingCommand = null }
                    ?: channel.receiveCatching().getOrNull()
                    ?: break
                val drained = if (command is Command.Record) {
                    drainRecordBatch(command, channel)
                } else {
                    emptyList<Command.Record>() to null
                }
                val records = drained.first
                pendingCommand = drained.second
                var attemptedRecordSequences = emptyList<Long>()
                var factoryDropCount = 0L
                stored = try {
                    var next = persistPendingDrops(store, stored)
                    if (records.isEmpty()) {
                        next = processControl(store, next, command as ControlCommand)
                    } else if (next.metadata.state == DiagnosticCaptureState.RUNNING) {
                        val events = mutableListOf<DiagnosticEvent>()
                        records.forEach { record ->
                            val event = try {
                                record.eventFactory(
                                    next.metadata.nextSequence + events.size,
                                    clock(),
                                )
                            } catch (_: Exception) {
                                factoryDropCount++
                                null
                            }
                            if (event != null) events += event
                        }
                        attemptedRecordSequences = events.map(DiagnosticEvent::sequence)
                        if (factoryDropCount > 0L) {
                            next = next.copy(
                                metadata = next.metadata.copy(
                                    droppedEventCount =
                                        next.metadata.droppedEventCount + factoryDropCount,
                                ),
                            )
                        }
                        next = when {
                            events.isNotEmpty() -> store.appendBatch(next, events).also {
                                attemptedRecordSequences = emptyList()
                                factoryDropCount = 0L
                            }
                            factoryDropCount > 0L -> store.persistMetadata(next).also {
                                factoryDropCount = 0L
                            }
                            else -> next
                        }
                    }
                    persistPendingDrops(store, next)
                } catch (cancelled: CancellationException) {
                    command.fail(cancelled)
                    throw cancelled
                } catch (_: Exception) {
                    failClosed(
                        store = store,
                        state = stored,
                        attemptedRecordSequences = attemptedRecordSequences,
                        factoryDropCount = factoryDropCount,
                    )
                }
                publish(stored)
                command.complete(mutableSnapshot.value)
            }
        } finally {
            pendingCommand?.fail(CancellationException("Diagnostic capture consumer stopped"))
            channel.cancel(CancellationException("Diagnostic capture consumer stopped"))
        }
    }

    private fun drainRecordBatch(
        first: Command.Record,
        channel: Channel<Command>,
    ): Pair<List<Command.Record>, Command?> {
        val records = mutableListOf(first)
        var pendingCommand: Command? = null
        while (records.size < MAX_RECORD_BATCH_SIZE) {
            val next = channel.tryReceive().getOrNull() ?: break
            if (next is Command.Record) {
                records += next
            } else {
                pendingCommand = next
                break
            }
        }
        return records to pendingCommand
    }

    private fun processControl(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
        command: ControlCommand,
    ): DiagnosticStoredState = when (command) {
        is Command.Start -> start(store, state)
        is Command.Pause -> pause(store, state)
        is Command.Clear -> store.clear(state)
        is Command.DisableAndClear -> store.deleteAll()
        is Command.Flush -> state
    }

    private fun start(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState = when (state.metadata.state) {
        DiagnosticCaptureState.RUNNING -> state
        DiagnosticCaptureState.PAUSED -> store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(state = DiagnosticCaptureState.RUNNING),
            ),
        )
        DiagnosticCaptureState.IDLE -> {
            val empty = store.deleteAll()
            store.persistMetadata(
                empty.copy(
                    metadata = DiagnosticCaptureMetadata(
                        state = DiagnosticCaptureState.RUNNING,
                        sessionId = sessionIdFactory(),
                        startedAtMillis = clock(),
                    ),
                ),
            )
        }
    }

    private fun pause(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState {
        if (state.metadata.state != DiagnosticCaptureState.RUNNING) return state
        return store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(state = DiagnosticCaptureState.PAUSED),
            ),
        )
    }

    private fun persistPendingDrops(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState {
        val dropped = pendingDroppedEvents.get()
        if (dropped == 0L) return state
        val persisted = store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(
                    droppedEventCount = state.metadata.droppedEventCount + dropped,
                ),
            ),
        )
        pendingDroppedEvents.addAndGet(-dropped)
        return persisted
    }

    private fun failClosed(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
        attemptedRecordSequences: List<Long>,
        factoryDropCount: Long,
    ): DiagnosticStoredState {
        val pending = pendingDroppedEvents.getAndSet(0L)
        val reconciled = try {
            store.load()
        } catch (_: Exception) {
            state
        }
        val recoveredSequences = reconciled.events
            .asSequence()
            .map(DiagnosticEvent::sequence)
            .toHashSet()
        val unrecoveredRecordCount = attemptedRecordSequences.count { sequence ->
            sequence !in recoveredSequences
        }
        val requiredDropped = state.metadata.droppedEventCount + pending +
            factoryDropCount + unrecoveredRecordCount
        val fallbackState = if (reconciled.metadata.sessionId == null) {
            DiagnosticCaptureState.IDLE
        } else {
            DiagnosticCaptureState.PAUSED
        }
        val fallback = reconciled.copy(
            metadata = reconciled.metadata.copy(
                state = fallbackState,
                nextSequence = maxOf(
                    reconciled.metadata.nextSequence,
                    attemptedRecordSequences.lastOrNull()?.plus(1L) ?: 1L,
                ),
                droppedEventCount = maxOf(
                    reconciled.metadata.droppedEventCount,
                    requiredDropped,
                ),
            ),
        )
        return try {
            store.persistMetadata(fallback)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun publish(stored: DiagnosticStoredState) {
        val metadata = stored.metadata
        val session = metadata.sessionId?.let { id ->
            metadata.startedAtMillis?.let { startedAtMillis ->
                DiagnosticSession(id = id, startedAtMillis = startedAtMillis)
            }
        }
        captureState = metadata.state
        mutableSnapshot.value = DiagnosticSnapshot(
            state = metadata.state,
            session = session,
            events = stored.events,
            nextSequence = metadata.nextSequence,
            retainedPayloadBytes = stored.retainedPayloadBytes,
            droppedEventCount = metadata.droppedEventCount,
            evictedEventCount = metadata.evictedEventCount,
            truncatedPayloadCount = metadata.truncatedPayloadCount,
            capacityLimitReached = metadata.capacityLimitReached,
        )
    }

    private sealed interface Command {
        data class Start(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Pause(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Clear(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class DisableAndClear(
            override val response: CompletableDeferred<DiagnosticSnapshot>,
        ) : ControlCommand

        data class Flush(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Record(
            val eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent,
        ) : Command

        fun complete(snapshot: DiagnosticSnapshot) {
            if (this is ControlCommand) response.complete(snapshot)
        }

        fun fail(error: Throwable) {
            if (this is ControlCommand) response.completeExceptionally(error)
        }
    }

    private sealed interface ControlCommand : Command {
        val response: CompletableDeferred<DiagnosticSnapshot>
    }

    internal companion object {
        const val DEFAULT_QUEUE_CAPACITY = 128
        const val MAX_QUEUE_CAPACITY = 4_096
        private const val MAX_RECORD_BATCH_SIZE = 64
    }
}
