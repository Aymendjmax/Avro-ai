package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Remote owns saved connections and presentation; native Codex owns durable execution. */
internal class RemoteViewModel(
    private val connections: RemoteConnectionStore,
    private val imageStore: com.newoether.agora.tool.ToolImageStore? = null,
    private val imageCache: RemoteImageCache? = null,
    projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val attachmentStore: RemoteAttachmentStore? = null,
    createClient: (String, String) -> FiloClient = { address, token -> FiloClient(address, token) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteState())
    private val attachmentDrafts = RemoteAttachmentDrafts(attachmentStore, viewModelScope, mutableState) { owner, error ->
        if (state.value.owner == owner) trace("attachment_failed", error)
    }
    fun addAttachments(owner: String, uris: List<android.net.Uri>) = attachmentDrafts.add(owner, uris)
    fun removeAttachment(owner: String, id: String) = attachmentDrafts.remove(owner, id)
    fun retryAttachment(owner: String, id: String) = attachmentDrafts.retry(owner, id)
    val state = mutableState.asStateFlow()
    private val noticeChannel = Channel<RemoteNotice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()
    private val hydration = RemoteMessageHydration(state, { owner, cursor ->
        val snapshot = state.value
        if (snapshot.owner != owner) throw CancellationException()
        val client = clients[snapshot.deviceId] ?: throw CancellationException()
        client.conversation(snapshot.session!!.id, cursor)
    }, { trace("payload_failed", it) }, { owner, request ->
        val snapshot = state.value
        if (snapshot.owner != owner) throw CancellationException()
        val client = clients[snapshot.deviceId] ?: throw CancellationException()
        val store = imageStore ?: throw java.io.IOException("Image storage is unavailable")
        val cache = imageCache ?: throw java.io.IOException("Image cache is unavailable")
        cache.load(owner + "/" + request.id + "/" + request.revision + "/" + request.imageIndex) {
            client.image(snapshot.session!!.id, request, store::persistStream)
        }
    }, projectionDispatcher = projectionDispatcher)
    private val historyMutation = kotlinx.coroutines.sync.Mutex()
    fun cachedMessage(owner: String, id: String) = state.value.messageGroups.firstOrNull { it.stub.id == id }
        ?.let { hydration.cachedMessage(owner, it) }
    fun observeMessage(owner: String, id: String) = hydration.observeMessage(owner, id)
    suspend fun loadToolImage(owner: String, id: String, revision: String) =
        hydration.loadToolImage(owner, id, revision)
    suspend fun searchMessages(owner: String, ids: List<String>) = try {
        hydration.loadMessages(owner, ids)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { trace("payload_failed", error); emptyList() }
    private val scrollRequests = ScrollRequestCoordinator()
    val animatedScrollRequest = scrollRequests.request
    fun completeAnimatedScroll(id: Long) = scrollRequests.complete(id)
    private val checkSlots = Semaphore(2)
    private var epoch = 0L
    private var selectionEpoch = 0L
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private var statusPolling: Job? = null
    private var visibleSessions: List<String> = emptyList()
    private var modelLoading: Job? = null
    private var modelEpoch = 0L
    private val createdAt = System.nanoTime()
    private val diagnosticContext = DiagnosticRequestContext(
        requestId = UUID.randomUUID().toString(), provider = "Filo", model = "Codex", requestKind = "remote",
    )

    private val deviceDirectory = RemoteDeviceDirectory(
        connections = connections,
        scope = viewModelScope,
        mutableState = mutableState,
        checkSlots = checkSlots,
        createClient = createClient,
        selectionEpoch = { selectionEpoch },
        selectDevice = ::selectDevice,
        refresh = ::refresh,
        updateDevice = ::updateDevice,
        report = { stage, error -> trace(stage, error) },
    )
    private val clients get() = deviceDirectory.clients

    init { trace("owner_created"); restoreConnections() }

    private fun trace(stage: String, error: Exception? = null, notify: Boolean = true): RemoteFailure? {
        val failure = error?.let(::classifyRemoteFailure)
        if (failure != null && notify) noticeChannel.trySend(RemoteNotice(stage, failure, selectionEpoch, remoteErrorDetail(error), remoteErrorCode(error)))
        val suffix = if (failure == null) "" else ".${failure.name}.${error.javaClass.simpleName}"
        // Preserve the existing privacy wrapper and diagnostic logging preferences.
        if (failure != null) runCatching {
            com.newoether.agora.util.DebugLog.w("AgoraRemote", "remote.$stage$suffix" +
                (if (error is FiloHttpException) " code=${error.status}" else "") +
                " cause=${error.cause?.javaClass?.simpleName.orEmpty()}")
        }
        DeveloperDiagnostics.recordHttpStage(diagnosticContext, "remote.$stage$suffix",
            (System.nanoTime() - createdAt) / 1_000_000,
            "addresses=${state.value.devices.size}" + if (error is FiloHttpException) " code=${error.status}" else "")
        return failure
    }

    fun isNoticeCurrent(notice: RemoteNotice): Boolean = notice.selection == selectionEpoch
    fun retryNotice(notice: RemoteNotice) {
        if (!isNoticeCurrent(notice)) return
        when (notice.stage) {
            "restore_failed" -> restoreConnections()
            "page_failed" -> loadMore()
            "payload_failed" -> mutableState.value = state.value.copy(hydrationRevision = state.value.hydrationRevision + 1)
            "check_failed", "read_failed" -> refresh()
        }
    }

    override fun onCleared() {
        trace("owner_cleared")
        noticeChannel.close()
        super.onCleared()
    }

    fun restoreConnections() = deviceDirectory.restoreConnections()
    suspend fun usage(): RemoteUsage? {
        val device = state.value.deviceId ?: return null
        return try { clients[device]?.usage()?.takeIf { state.value.deviceId == device } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (state.value.deviceId == device) trace("usage_failed", error); null }
    }

    fun addDevice() = editDevice(null)

    fun editorConnection(): RemoteConnection? = deviceDirectory.editorConnection()

    fun editDevice(id: String?) {
        if (state.value.saving || state.value.restoring || (id != null && id !in clients)) return
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        mutableState.value = state.value.copy(deviceId = null, session = null, addingDevice = true,
            editedDeviceId = id, storageError = false, failure = null, lastKnownModel = null)
    }

    fun saveDevice(address: String, token: String, name: String? = null) =
        deviceDirectory.saveDevice(address, token, name)

    fun removeDevice(id: String) = deviceDirectory.removeDevice(id)

    private fun checkDevice(id: String) = deviceDirectory.checkDevice(id)

    private fun updateDevice(id: String, update: (RemoteDevice) -> RemoteDevice) {
        mutableState.value = state.value.copy(devices = state.value.devices.map { if (it.id == id) update(it) else it })
    }

    fun selectDevice(id: String?) {
        if (id != null && id !in clients) return
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        modelEpoch++
        modelLoading?.cancel()
        mutableState.value = state.value.copy(deviceId = id, addingDevice = false, editedDeviceId = null,
            sessions = emptyList(), sessionCursor = null,
            session = null, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            runtime = null, lastKnownModel = null, models = emptyList(), modelsLoading = false, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings())
        refresh()
    }

    fun selectSession(session: RemoteSession?) {
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        mutableState.value = state.value.copy(session = session, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null, runtime = null, lastKnownModel = null, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings())
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        trace(if (value) "visible" else "hidden")
        if (value) refresh() else {
            invalidateReads()
            modelEpoch++
            modelLoading?.cancel()
            mutableState.value = state.value.copy(modelsLoading = false)
        }
    }

    private fun invalidateReads() {
        hydration.resetStreaming()
        epoch++
        polling?.cancel()
        paging?.cancel()
        statusPolling?.cancel()
        // Suspend control readiness, not the last native presentation. Reconnecting is not completion.
        mutableState.value = state.value.copy(loading = false, loadingMore = false, runtime = null, hydrationEnabled = false)
    }

    /**
     * Re-reads the device's model catalog, e.g. when the model menu opens, so a model published
     * while the page stayed open appears without reconnecting. The current list stays shown meanwhile.
     */
    fun refreshModels() {
        val id = state.value.deviceId ?: return
        val client = clients[id] ?: return
        if (!visible || state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) return
        loadModels(id, client)
    }
    private fun loadModels(id: String, client: FiloClient) {
        if (modelLoading?.isActive == true) return
        val modelGeneration = ++modelEpoch
        mutableState.value = state.value.copy(modelsLoading = true)
        modelLoading = viewModelScope.launch {
            try {
                val models = client.models()
                if (modelGeneration == modelEpoch && clients[id] === client && state.value.deviceId == id) {
                    mutableState.value = state.value.copy(models = models)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { trace("models_failed", error) }
            finally {
                if (modelGeneration == modelEpoch) mutableState.value = state.value.copy(modelsLoading = false)
            }
        }
    }
    fun refresh() {
        if (!visible || state.value.restoring || state.value.addingDevice || state.value.isDraft) return
        invalidateReads()
        val id = state.value.deviceId
        if (id == null) {
            clients.keys.forEach(::checkDevice)
            return
        }
        val client = clients[id] ?: return
        if (state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) {
            mutableState.value = state.value.copy(loading = true, failure = null)
            checkDevice(id)
            return
        }
        val generation = epoch
        val session = state.value.session
        loadModels(id, client)
        polling = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true)
            var consecutiveFailures = 0
            var notifiedFailure: RemoteFailure? = null
            do {
                if (state.value.devices.firstOrNull { it.id == id }?.status == RemoteDeviceStatus.ERROR) {
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                }
                try {
                    if (session == null) {
                        val page = client.sessions()
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(
                            sessions = page.sessions, sessionCursor = page.nextCursor,
                            sessionStatuses = mergeListedSessionStatuses(id, page),
                            loading = false, failure = null,
                        )
                        startSessionStatusReads()
                    } else {
                        client.events(session.id).collect { page ->
                            if (generation == epoch) {
                                applyPage(client, session.id, generation, page)
                                consecutiveFailures = 0
                                notifiedFailure = null
                            }
                        }
                    }
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (generation == epoch) {
                        val readFailure = requireNotNull(trace("read_failed", error, notify = false))
                        mutableState.value = state.value.copy(loading = false, runtime = null,
                            stoppingOwner = null, stoppingTurnId = null)
                        // A failed native read is not proof that the device is offline.
                        var reachable = false
                        if (session != null && readFailure in setOf(RemoteFailure.NETWORK, RemoteFailure.SERVICE)) {
                            try {
                                // Read the same original owner when subscription fails; never admit another host.
                                applyPage(client, session.id, generation, client.conversation(session.id), liveControl = false)
                                if (generation != epoch) return@launch
                                reachable = true
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (snapshotError: Exception) {
                                if (generation != epoch) return@launch
                                trace("read_snapshot_failed", snapshotError, notify = false)
                            }
                        }
                        val failure = if (readFailure == RemoteFailure.NETWORK && !reachable) {
                            updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                            try {
                                checkSlots.withPermit { client.connect() }
                                if (generation != epoch) return@launch
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                                reachable = true
                                RemoteFailure.SERVICE
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (healthError: Exception) {
                                if (generation != epoch) return@launch
                                val healthFailure = requireNotNull(trace("read_health_failed", healthError, notify = false))
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = healthFailure) }
                                healthFailure
                            }
                        } else {
                            if (readFailure == RemoteFailure.AUTHENTICATION) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = readFailure) }
                            } else if (readFailure in setOf(RemoteFailure.SERVICE, RemoteFailure.PROTOCOL,
                                    RemoteFailure.SESSION_BUSY, RemoteFailure.CONTENT_TOO_LARGE)) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                            }
                            if (readFailure == RemoteFailure.NETWORK) RemoteFailure.SERVICE else readFailure
                        }
                        consecutiveFailures++
                        // One interrupted GET may recover on the existing three-second loop.
                        // Health failure and non-network errors remain immediately visible.
                        val recovering = session != null && readFailure == RemoteFailure.NETWORK &&
                            reachable && consecutiveFailures == 1
                        mutableState.value = state.value.copy(failure = failure.takeUnless { recovering })
                        if (!recovering && notifiedFailure != failure) {
                            noticeChannel.trySend(RemoteNotice("read_failed", failure, selectionEpoch,
                                remoteErrorDetail(error).takeIf { failure == readFailure },
                                remoteErrorCode(error).takeIf { failure == readFailure }))
                            notifiedFailure = failure
                        }
                    }
                }
                if (session == null) break
                delay(3000)
            } while (isActive && visible && generation == epoch)
        }
    }

    private suspend fun applyPage(
        client: FiloClient, sessionId: String, generation: Long, page: RemoteConversationPage, liveControl: Boolean = true,
    ) {
        if (generation != epoch) return
        val old = state.value.nodes
        val oldIds = old.mapTo(HashSet()) { it.id }
        val owner = state.value.owner ?: return
        val incoming = mutableListOf(cachePage(owner, page, live = true))
        var cursor = page.nextCursor
        val cursors = mutableSetOf<String>()
        // Opening publishes exactly the latest packet; only live updates bridge existing history.
        while (old.isNotEmpty() && cursor != null && incoming.last().nodes.none { it.id in oldIds }) {
            require(cursors.add(cursor)) { "Filo history cursor did not advance" }
            val raw = client.conversation(sessionId, cursor)
            if (generation != epoch) return
            val older = cachePage(owner, raw, live = false)
            incoming += older
            cursor = older.nextCursor
        }
        if (generation != epoch) return
        historyMutation.withLock {
            if (generation != epoch) return
            var nodes = state.value.nodes
            for (chunk in incoming.asReversed()) nodes = admitRemoteNodes(nodes, chunk.nodes)
            val runtime = page.runtime
            val groups = projectRemoteTopology(nodes, runtime)
            for (chunk in incoming.asReversed()) hydration.accept(owner, chunk, groups, live = false)
            if (generation != epoch) return
            mutableState.value = state.value.copy(
                nodes = nodes, messageGroups = groups, hydrationEnabled = visible,
                historyCursor = if (old.isEmpty()) incoming.last().nextCursor else state.value.historyCursor,
                queued = page.queued, loading = false, failure = null,
                runtime = page.runtime.takeIf { liveControl },
                lastKnownModel = page.runtime?.model ?: state.value.lastKnownModel,
            )
        }
        state.value.deviceId?.let { id -> updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) } }
        state.value.attempts[owner]?.let { attempt -> confirmDelivery(owner, attempt, state.value.nodes) }
        settleStop()
        page.runtime?.takeIf { it.status in setOf("idle", "active", "ready") }?.let { runtime ->
            val key = "${state.value.deviceId}/$sessionId"
            mutableState.value = state.value.copy(sessionStatuses = state.value.sessionStatuses +
                (key to RemoteSessionStatus(sessionId, runtime.status, runtime.activeTurnId, runtime.completedTurnId)))
        }
        page.runtime?.completedTurnId?.let { turn ->
            val address = state.value.deviceId ?: return@let
            val key = "$address/$sessionId"
            if (visible && state.value.viewedTurns[key] != turn) {
                mutableState.value = state.value.copy(viewedTurns = state.value.viewedTurns + (key to turn))
                viewModelScope.launch {
                    try { connections.markViewed(address, sessionId, turn) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        trace("mark_viewed_failed", error)
                        mutableState.value = state.value.copy(storageError = true)
                    }
                }
            }
        }
    }

    private fun mergeListedSessionStatuses(
        address: String, page: RemoteSessionPage,
    ): Map<String, RemoteSessionStatus> {
        val previous = state.value.sessionStatuses
        val exact = page.statuses.associateBy { it.id }
        val listed = page.sessions.mapNotNull { session ->
            val key = "$address/${session.id}"
            val old = previous[key]
            val item = exact[session.id]
                ?: session.status?.let { old?.copy(status = it) ?: RemoteSessionStatus(session.id, it) }
                ?: return@mapNotNull null
            val resolved = if (item.status == null && old != null) old else item
            key to resolved.copy(hasUnreadTurn = resolved.hasUnreadTurn ||
                item.completedTurnId != null && (old?.activeTurnId == item.completedTurnId &&
                    old.completedTurnId != item.completedTurnId ||
                    old?.hasUnreadTurn == true && old.completedTurnId == item.completedTurnId))
        }.toMap()
        return previous + listed
    }

    fun observeSessions(deviceId: String, ids: List<String>) {
        if (state.value.deviceId != deviceId || state.value.session != null) return
        val allowed = state.value.sessions.map { it.id }.toSet()
        val next = ids.distinct().filter { it in allowed }.take(12)
        if (next == visibleSessions && statusPolling?.isActive == true) return
        visibleSessions = next
        startSessionStatusReads()
    }

    private fun startSessionStatusReads() {
        statusPolling?.cancel()
        val snapshot = state.value
        if (!visible || snapshot.session != null || snapshot.addingDevice) return
        val address = snapshot.deviceId ?: return
        val client = clients[address] ?: return
        val ids = visibleSessions.filter { id -> snapshot.sessions.any { it.id == id } }
        if (ids.isEmpty()) return
        val selected = selectionEpoch
        statusPolling = viewModelScope.launch {
            while (isActive && visible && selected == selectionEpoch) {
                // The list already supplied exact status. Refresh that same page only after the interval.
                delay(3000)
                try {
                    val cursors = state.value.sessions.filter { it.id in ids }.map { it.listCursor }.distinct()
                    for (cursor in cursors) {
                        val page = client.sessions(cursor)
                        if (selected != selectionEpoch || clients[address] !== client || !visible) return@launch
                        mutableState.value = state.value.copy(
                            sessionStatuses = mergeListedSessionStatuses(address, page),
                        )
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (selected != selectionEpoch) return@launch
                    trace("session_status_failed", error)
                    // A failed read does not erase the last confirmed presentation.
                    if (error is FiloHttpException && error.status in setOf(401, 403, 404, 501)) return@launch
                }
            }
        }
    }

    private fun settleStop() {
        val snapshot = state.value
        val runtime = snapshot.runtime ?: return
        if (!snapshot.isStopping || snapshot.controlling) return
        if (!runtime.isRunning || runtime.activeTurnId != null && runtime.activeTurnId != snapshot.stoppingTurnId) {
            mutableState.value = snapshot.copy(stoppingOwner = null, stoppingTurnId = null)
        }
    }

    fun newSession() {
        val snapshot = state.value
        if (snapshot.deviceId !in clients || snapshot.isDraft || snapshot.controlling) return
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null)
        invalidateReads()
        val id = UUID.randomUUID().toString()
        // A local composer identity survives promotion to the first native session.
        mutableState.value = state.value.copy(
            session = RemoteSession(id, "", "", 0), draftSessionId = id, draftSettings = RemoteSettings(),
            nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            lastKnownModel = null, composerFocusOwner = "${snapshot.deviceId}/$id",
        )
    }

    fun completeComposerFocus(owner: String) {
        if (state.value.composerFocusOwner == owner) mutableState.value = state.value.copy(composerFocusOwner = null)
    }

    fun setModel(model: String) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val available = snapshot.models.firstOrNull { it.id == model } ?: return
        if (!snapshot.canEditSettings || model == snapshot.selectedModel) return
        val settings = if (available.reasoningEfforts != null) snapshot.settingsForModel(available) else RemoteSettings(model = model)
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
        } else if (available.reasoningEfforts == null) {
            val selected = selectionEpoch
            control { client -> client.setModel(session.id, model); if (selected == selectionEpoch) refresh() }
        } else changeSettings(settings)
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val snapshot = state.value
        if (enabled == (snapshot.selectedEffort != null && snapshot.selectedEffort != "none")) return
        val model = snapshot.settingsModel ?: return
        val effort = if (enabled) model.defaultReasoningEffort?.takeUnless { it == "none" }
            ?: model.reasoningEfforts?.firstOrNull { it != "none" } else "none"
        effort?.let(::setThinkingLevel)
    }

    fun setThinkingLevel(effort: String) {
        val snapshot = state.value
        if (effort == snapshot.selectedEffort || effort !in snapshot.settingsModel?.reasoningEfforts.orEmpty()) return
        changeSettings(RemoteSettings(effort = effort))
    }

    fun setServiceTierEnabled(enabled: Boolean) {
        val snapshot = state.value
        if (enabled == (snapshot.selectedServiceTier != null)) return
        val model = snapshot.settingsModel ?: return
        val tier = if (enabled) model.defaultServiceTier?.takeIf { value -> model.serviceTiers.orEmpty().any { it.id == value } }
            ?: model.serviceTiers?.firstOrNull()?.id ?: return else null
        setServiceTier(tier)
    }

    fun setServiceTier(tier: String?) {
        val snapshot = state.value
        if (snapshot.settingsModel?.serviceTiers == null || tier == snapshot.selectedServiceTier ||
            tier != null && snapshot.settingsModel?.serviceTiers.orEmpty().none { it.id == tier }) return
        changeSettings(RemoteSettings(serviceTier = tier, updateServiceTier = true))
    }

    private fun changeSettings(settings: RemoteSettings) {
        val snapshot = state.value
        if (!snapshot.canEditSettings) return
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
            return
        }
        val selected = selectionEpoch
        control { client ->
            val id = snapshot.session!!.id
            client.updateSettings(id, settings)
            if (selected == selectionEpoch && clients[snapshot.deviceId] === client && visible) {
                val generation = epoch
                applyPage(client, id, generation, client.conversation(id))
            }
        }
    }

    fun stop() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (snapshot.runtime?.isRunning != true) return
        val turn = snapshot.runtime.activeTurnId ?: return
        control(stoppingTurnId = turn) { client -> client.stop(session.id, turn) }
    }

    private fun control(stoppingTurnId: String? = null, operation: suspend (FiloClient) -> Unit) {
        if (state.value.controlling || state.value.isStopping) return
        val client = clients[state.value.deviceId] ?: return
        val selected = selectionEpoch
        val owner = state.value.owner
        mutableState.value = state.value.copy(controlling = true,
            stoppingOwner = if (stoppingTurnId != null) owner else state.value.stoppingOwner,
            stoppingTurnId = stoppingTurnId ?: state.value.stoppingTurnId)
        viewModelScope.launch {
            var succeeded = false
            try { operation(client); succeeded = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("control_failed", error)
                if (selected == selectionEpoch) mutableState.value = state.value.copy(failure = failure)
            } finally {
                if (selected == selectionEpoch) mutableState.value = state.value.copy(controlling = false,
                    settingsRevision = state.value.settingsRevision + 1)
                if (stoppingTurnId != null && state.value.stoppingOwner == owner &&
                    state.value.stoppingTurnId == stoppingTurnId && (!succeeded || selected != selectionEpoch)) {
                    mutableState.value = state.value.copy(stoppingOwner = null, stoppingTurnId = null)
                }
                // A Stop receipt and the native end-of-turn snapshot may arrive in either order.
                settleStop()
            }
        }
    }

    fun renameSession(id: String, name: String) {
        if (name.isBlank() || name.length > 4096) return
        manageSession(id) { it.rename(id, name) }
    }
    fun archiveSession(id: String) = manageSession(id) { it.archiveSession(id); null }

    private fun manageSession(id: String, operation: suspend (FiloClient) -> RemoteSession?) {
        val snapshot = state.value
        if (snapshot.session != null || snapshot.controlling || snapshot.sessions.none { it.id == id }) return
        val selected = selectionEpoch
        invalidateReads()
        control { client ->
            try {
                val updated = operation(client)
                if (selected != selectionEpoch || clients[snapshot.deviceId] !== client) return@control
                val owner = snapshot.sessionOwners["${snapshot.deviceId}/$id"] ?: "${snapshot.deviceId}/$id"
                mutableState.value = state.value.copy(
                    sessions = state.value.sessions.mapNotNull { session ->
                        if (session.id != id) session else updated?.let { session.copy(title = it.title, updatedAt = it.updatedAt) }
                    },
                    sessionStatuses = if (updated == null) state.value.sessionStatuses - "${snapshot.deviceId}/$id" else state.value.sessionStatuses,
                    drafts = if (updated == null) state.value.drafts - owner else state.value.drafts,
                    attempts = if (updated == null) state.value.attempts - owner else state.value.attempts,
                    failure = null,
                )
            } finally { if (selected == selectionEpoch) startSessionStatusReads() }
        }
    }

    fun loadMore() {
        if (paging?.isActive == true || state.value.loading) return
        val snapshot = state.value
        val client = clients[snapshot.deviceId] ?: return
        val cursor = (if (snapshot.session != null) snapshot.historyCursor else snapshot.sessionCursor) ?: return
        val generation = epoch
        mutableState.value = state.value.copy(loadingMore = true)
        paging = viewModelScope.launch {
            try {
                if (snapshot.session != null) {
                    prependPage(client, snapshot.session.id, generation, cursor)
                } else {
                    val page = client.sessions(cursor)
                    require(page.nextCursor != cursor) { "Filo session cursor did not advance" }
                    if (generation == epoch) {
                        val sessions = (state.value.sessions + page.sessions).distinctBy { it.id }
                        mutableState.value = state.value.copy(
                            sessions = sessions,
                            sessionStatuses = mergeListedSessionStatuses(requireNotNull(snapshot.deviceId), page),
                            sessionCursor = page.nextCursor, failure = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == epoch) mutableState.value = state.value.copy(failure = trace("page_failed", error))
            } finally {
                if (generation == epoch) mutableState.value = state.value.copy(loadingMore = false)
            }
        }
    }

    private suspend fun cachePage(owner: String, page: RemoteConversationPage, live: Boolean): RemoteConversationPage {
        require(page.nodes.map { it.id } == page.messages.map { it.id }) { "Filo page metadata is missing" }
        hydration.accept(owner, page, emptyList(), live)
        // Retain only topology here; body retention stays in the bounded LRU.
        return page.copy(messages = emptyList(), nodes = page.nodes.map { it.copy(pageCursor = page.pageCursor) })
    }

    private suspend fun prependPage(client: FiloClient, session: String, generation: Long, cursor: String) {
        val owner = state.value.owner ?: return
        val page = cachePage(owner, client.conversation(session, cursor), live = false)
        require(page.nextCursor != cursor) { "Filo history cursor did not advance" }
        historyMutation.withLock {
            if (generation != epoch || state.value.historyCursor != cursor) return
            val nodes = admitRemoteNodes(state.value.nodes, page.nodes, older = true)
            val groups = projectRemoteTopology(nodes, state.value.runtime)
            hydration.accept(owner, page, groups, live = false)
            if (generation != epoch) return
            mutableState.value = state.value.copy(nodes = nodes, messageGroups = groups,
                historyCursor = page.nextCursor, failure = null)
        }
    }

    fun searchHistory(query: String): kotlinx.coroutines.flow.Flow<List<com.newoether.agora.ui.chat.ConversationSearchMatch>> {
        val snapshot = state.value
        val owner = snapshot.owner ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val client = clients[snapshot.deviceId] ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val session = snapshot.session ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val generation = epoch
        return remoteHistorySearch(state, owner, query,
            loadMessages = { ids -> hydration.loadMessages(owner, ids) },
            loadEarlier = { cursor ->
                if (generation != epoch) throw CancellationException()
                prependPage(client, session.id, generation, cursor)
            },
            failed = { trace("page_failed", it) },
            isCached = { group -> hydration.cachedMessage(owner, group) != null },
        )
    }

    fun editDraft(owner: String, text: String) {
        mutableState.value = state.value.copy(drafts = state.value.drafts + (owner to text))
    }

    fun acknowledgeUnknown(owner: String) {
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.UNKNOWN) {
            mutableState.value = state.value.copy(attempts = state.value.attempts - owner)
        }
    }

    fun send() {
        val snapshot = state.value
        if (!visible || snapshot.controlling || snapshot.isStopping) return
        val owner = snapshot.owner ?: return
        val client = clients[snapshot.deviceId] ?: return
        val text = snapshot.drafts[owner].orEmpty()
        val attachments = snapshot.attachments[owner].orEmpty()
        if ((text.isBlank() && attachments.isEmpty()) || snapshot.attempts[owner]?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)) return
        if (attachments.any { it.importState != com.newoether.agora.model.AttachmentImportState.READY }) {
            trace("send_failed", RemoteAttachmentException("Finish or remove pending attachments before sending")); return
        }
        val selected = selectionEpoch
        val attempt = RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING)
        mutableState.value = state.value.copy(attempts = state.value.attempts + (owner to attempt))
        viewModelScope.launch {
            var uploadComplete = false
            try {
                val uploads = attachments.map { client.upload(it) }
                uploadComplete = true
                if (selected != selectionEpoch || clients[snapshot.deviceId] !== client) throw FiloInputException()
                var sessionId = snapshot.session!!.id
                if (snapshot.isDraft) {
                    // One mutation: the session is created together with this first message.
                    val settingsModel = snapshot.settingsModel
                    val settings = if (settingsModel == null) null else if (settingsModel.reasoningEfforts != null) RemoteSettings(
                        settingsModel.id, snapshot.selectedEffort, snapshot.selectedServiceTier, updateServiceTier = true,
                    ) else RemoteSettings(model = settingsModel.id)
                    val created = client.create(text, attempt.clientId, uploads.map { it.id }, settings)
                    // Creation includes the first turn, even if its owner is no longer selected.
                    mutableState.value = state.value.copy(
                        sessionOwners = state.value.sessionOwners + ("${snapshot.deviceId}/${created.id}" to owner),
                    )
                    if (selected != selectionEpoch || clients[snapshot.deviceId] !== client ||
                        state.value.attempts[owner]?.clientId != attempt.clientId) {
                        // Keep the accepted outcome on the original owner without replacing the selection.
                        if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                            state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                            mutableState.value = state.value.copy(attempts = state.value.attempts +
                                (owner to attempt.copy(delivery = RemoteDelivery.ACCEPTED)))
                        }
                        return@launch
                    }
                    sessionId = created.id
                    mutableState.value = state.value.copy(
                        session = created,
                        lastKnownModel = snapshot.selectedModel,
                        sessionOwners = state.value.sessionOwners + ("${snapshot.deviceId}/${created.id}" to owner),
                    )
                    refresh()
                } else if (selected != selectionEpoch || clients[snapshot.deviceId] !== client) throw FiloInputException()
                if (!snapshot.isDraft) client.send(sessionId, text, attempt.clientId, uploads.map { it.id })
                if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                    state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                    mutableState.value = state.value.copy(attempts = state.value.attempts +
                        (owner to attempt.copy(delivery = RemoteDelivery.ACCEPTED)))
                    confirmDelivery(owner, attempt)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = trace("send_failed", error)
                if (state.value.owner == owner && failure == RemoteFailure.SESSION_BUSY) {
                    mutableState.value = state.value.copy(failure = failure)
                }
                if (state.value.attempts[owner]?.clientId == attempt.clientId &&
                    state.value.attempts[owner]?.delivery == RemoteDelivery.SUBMITTING) {
                    val rejected = !uploadComplete || error is FiloInputException ||
                        error is FiloHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 415, 429)
                    mutableState.value = state.value.copy(attempts = state.value.attempts +
                        (owner to attempt.copy(delivery = if (rejected) RemoteDelivery.REJECTED else RemoteDelivery.UNKNOWN)))
                }
            }
        }
    }

    private fun confirmDelivery(owner: String, attempt: RemoteAttempt, fresh: List<RemoteMessageNode> = state.value.nodes) {
        if (state.value.attempts[owner]?.clientId != attempt.clientId) return
        if (state.value.attempts[owner]?.delivery == RemoteDelivery.DELIVERED || state.value.owner != owner) return
        val message = fresh.firstOrNull { it.role == "user" && it.clientId == attempt.clientId } ?: return
        state.value.attachments[owner].orEmpty().forEach { attachmentStore?.remove(it) }
        mutableState.value = state.value.copy(
            attachments = state.value.attachments - owner,
            drafts = if (state.value.drafts[owner] == attempt.text) state.value.drafts - owner else state.value.drafts,
            attempts = state.value.attempts + (owner to attempt.copy(delivery = RemoteDelivery.DELIVERED)),
        )
        scrollRequests.requestAbsoluteBottomAfter(owner, message.nativeId ?: message.id)
    }
}
