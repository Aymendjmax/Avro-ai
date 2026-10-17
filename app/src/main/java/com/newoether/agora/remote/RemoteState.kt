package com.newoether.agora.remote

/** Old installations persisted the address as the temporary title. */
internal fun remoteDeviceName(name: String): String = name.trim().takeUnless {
    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
}.orEmpty()

internal enum class RemoteDeviceStatus { IDLE, CONNECTING, CONNECTED, ERROR }
internal data class RemoteDevice(
    val id: String, val name: String, val address: String,
    val status: RemoteDeviceStatus = RemoteDeviceStatus.IDLE, val failure: RemoteFailure? = null,
)
internal data class RemoteNotice(val stage: String, val failure: RemoteFailure, val selection: Long, val detail: String? = null, val code: String? = null) {
    val canRetryRead: Boolean get() = stage in setOf("restore_failed", "check_failed", "read_failed", "page_failed", "payload_failed")
}
internal enum class RemoteDelivery { SUBMITTING, ACCEPTED, DELIVERED, REJECTED, UNKNOWN }
internal data class RemoteAttempt(val clientId: String, val text: String, val delivery: RemoteDelivery)
internal data class RemoteState(
    val devices: List<RemoteDevice> = emptyList(), val deviceId: String? = null,
    val sessions: List<RemoteSession> = emptyList(), val sessionCursor: String? = null,
    val session: RemoteSession? = null, val nodes: List<RemoteMessageNode> = emptyList(),
    val messageGroups: List<RemoteMessageGroup> = emptyList(),
    val hydrationEnabled: Boolean = false, val hydrationRevision: Long = 0,
    val historyCursor: String? = null,
    val queued: List<RemoteQueuedMessage> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val attempts: Map<String, RemoteAttempt> = emptyMap(),
    val attachments: Map<String, List<com.newoether.agora.model.SelectedAttachment>> = emptyMap(),
    val saving: Boolean = false, val loading: Boolean = false, val loadingMore: Boolean = false, val failure: RemoteFailure? = null,
    val restoring: Boolean = true, val storageError: Boolean = false, val addingDevice: Boolean = false,
    val editedDeviceId: String? = null,
    val runtime: RemoteRuntime? = null, val lastKnownModel: String? = null,
    val models: List<RemoteModel> = emptyList(),
    val controlling: Boolean = false, val composerFocusOwner: String? = null,
    val draftSessionId: String? = null, val draftSettings: RemoteSettings = RemoteSettings(),
    val modelsLoading: Boolean = false,
    val sessionOwners: Map<String, String> = emptyMap(),
    val sessionStatuses: Map<String, RemoteSessionStatus> = emptyMap(),
    val viewedTurns: Map<String, String> = emptyMap(),
    val settingsRevision: Long = 0,
    val stoppingOwner: String? = null, val stoppingTurnId: String? = null,
) {
    val error: Boolean get() = failure != null
    val owner: String? get() = session?.let {
        val nativeOwner = "$deviceId/${it.id}"
        sessionOwners[nativeOwner] ?: nativeOwner
    }
    fun hasUnreadGeneration(sessionId: String): Boolean {
        val status = sessionStatuses["$deviceId/$sessionId"] ?: return false
        val completed = status.completedTurnId ?: return false
        return status.hasUnreadTurn && viewedTurns["$deviceId/$sessionId"] != completed
    }
    val isStopping: Boolean get() = stoppingOwner != null && stoppingOwner == owner
    val isDraft: Boolean get() = session != null && session.id == draftSessionId
    val selectedModel: String? get() = if (isDraft) {
        draftSettings.model ?: models.firstOrNull { it.isDefault }?.id
    } else runtime?.model ?: lastKnownModel
    val settingsModel: RemoteModel? get() = models.firstOrNull { it.id == selectedModel }
    val selectedEffort: String? get() = if (isDraft) draftSettings.effort ?: settingsModel?.defaultReasoningEffort else runtime?.effort
    val selectedServiceTier: String? get() = (if (isDraft) {
        if (draftSettings.updateServiceTier) draftSettings.serviceTier else settingsModel?.defaultServiceTier
    } else runtime?.serviceTier).takeUnless { it == "default" }
    val canEditSettings: Boolean get() = session != null && settingsModel != null &&
        (isDraft || runtime?.status in setOf("idle", "active", "ready")) &&
        !controlling && !isStopping && attempts[owner]?.delivery !in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)
    fun settingsForModel(model: RemoteModel) = RemoteSettings(model.id,
        selectedEffort?.takeIf { it in model.reasoningEfforts.orEmpty() } ?: model.defaultReasoningEffort,
        selectedServiceTier?.takeIf { tier -> model.serviceTiers.orEmpty().any { it.id == tier } }, true)
}
