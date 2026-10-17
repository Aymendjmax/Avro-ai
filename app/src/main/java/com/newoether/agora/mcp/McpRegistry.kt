package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

internal fun mcpServerIdsForPageEntryRefresh(
    configs: List<McpServerConfig>,
    snapshots: Map<String, McpServerSnapshot>,
): List<String> = configs
    .filter { it.enabled && it.url.isNotBlank() }
    .filter { snapshots[it.id]?.status != McpConnectionStatus.CONNECTING }
    .map(McpServerConfig::id)
    .distinct()

internal enum class McpRuntimeRefreshReason {
    RECONCILE,
    EXPLICIT,
    PAGE_ENTRY,
}

internal data class McpRuntimeBuildTicket(
    val generation: Long,
    val config: McpServerConfig,
)

internal fun shouldStartMcpRuntimeBuild(
    reason: McpRuntimeRefreshReason,
    config: McpServerConfig,
    runtimeConfig: McpServerConfig?,
    runtimeConnectionActive: Boolean,
    runtimeStatus: McpConnectionStatus?,
    pendingConfig: McpServerConfig?,
): Boolean = when {
    pendingConfig == config -> false
    reason == McpRuntimeRefreshReason.RECONCILE && runtimeConfig == config -> false
    reason == McpRuntimeRefreshReason.PAGE_ENTRY &&
        runtimeConfig == config &&
        runtimeConnectionActive &&
        runtimeStatus == McpConnectionStatus.CONNECTING -> false
    else -> true
}

internal fun isCurrentMcpRuntimeBuild(
    ticket: McpRuntimeBuildTicket,
    pendingTicket: McpRuntimeBuildTicket?,
    currentConfig: McpServerConfig?,
): Boolean =
    pendingTicket == ticket &&
        currentConfig == ticket.config &&
        currentConfig.enabled &&
        currentConfig.url.isNotBlank()

/**
 * Process-wide MCP supervisor.
 *
 * Every server owns its own client and retry job. Jobs are siblings under the app's SupervisorJob:
 * a broken endpoint can only update its own snapshot and cannot cancel another server or a model
 * generation. Tool definitions are immutable snapshots, so request construction never blocks on
 * network I/O.
 */
class McpRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 5L * 60L * 1_000L
        internal const val MAX_CONCURRENT_CONNECTIONS = 2
    }

    private data class Runtime(
        val config: McpServerConfig,
        val client: McpProtocolClient,
        var connectionJob: Job? = null,
    ) {
        fun close() {
            connectionJob?.cancel()
            connectionJob = null
            client.close()
        }
    }

    private data class RuntimeBuild(
        val ticket: McpRuntimeBuildTicket,
        val previousRuntime: Runtime?,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val imageStore = ToolImageStore(context)
    private val lock = Any()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val pendingBuilds = mutableMapOf<String, McpRuntimeBuildTicket>()
    private val connectionPermits = Semaphore(permits = MAX_CONCURRENT_CONNECTIONS)
    private var nextBuildGeneration = 0L
    private val _snapshots = MutableStateFlow<Map<String, McpServerSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, McpServerSnapshot>> = _snapshots.asStateFlow()

    init {
        scope.launch(workDispatcher) {
            settings.mcpServers.collect(::reconcile)
        }
    }

    fun enabledTools(): List<McpToolDescriptor> =
        snapshots.value.values
            .asSequence()
            .filter { it.status == McpConnectionStatus.CONNECTED }
            .flatMap { it.tools.asSequence() }
            .filter(McpToolDescriptor::enabled)
            .sortedBy(McpToolDescriptor::publicName)
            .toList()

    fun descriptor(publicName: String): McpToolDescriptor? =
        snapshots.value.values.asSequence()
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.enabled && it.publicName == publicName }

    fun refresh(serverId: String) {
        scope.launch(workDispatcher) {
            val current = currentConfig(serverId) ?: return@launch
            rebuildRuntime(current, McpRuntimeRefreshReason.EXPLICIT)
        }
    }

    fun refreshOnPageEntry() {
        scope.launch(workDispatcher) {
            val configs = settings.mcpServers.value
            val serverIds = mcpServerIdsForPageEntryRefresh(
                configs = configs,
                snapshots = snapshots.value,
            )
            serverIds.forEach { serverId ->
                val current = currentConfig(serverId)
                    ?.takeIf { it.enabled && it.url.isNotBlank() }
                    ?: return@forEach
                rebuildRuntime(current, McpRuntimeRefreshReason.PAGE_ENTRY)
            }
        }
    }

    suspend fun execute(publicName: String, arguments: String): ToolExecutionResult {
        val descriptor = descriptor(publicName)
            ?: return ToolExecutionResult("Unknown or disabled MCP tool: $publicName", isError = true)
        val runtime = synchronized(lock) { runtimes[descriptor.serverId] }
            ?: return ToolExecutionResult(
                "MCP server '${descriptor.serverName}' is not enabled",
                isError = true,
            )
        val args = runCatching { json.parseToJsonElement(arguments).asObjectOrNull() }
            .getOrNull()
            ?: return ToolExecutionResult(
                "MCP tool '$publicName' requires a complete JSON object",
                isError = true,
            )

        return try {
            val payload = runtime.client.callTool(descriptor.remote.name, args)
            val attachments = withContext(Dispatchers.IO) {
                payload.images.map { image ->
                    imageStore.persistBase64(
                        data = image.data,
                        mimeType = image.mimeType,
                        filePrefix = "mcp",
                    )
                }
            }
            val structured = payload.structuredContent?.toString()
            val contentText = payload.textParts
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            // MCP servers commonly repeat structuredContent as a text block for older clients.
            // Compare the actual JSON trees, not spelling, so the detail UI never shows the same
            // payload twice while still preserving distinct explanatory text.
            val displayText = contentText
                .takeIf(String::isNotBlank)
                ?.takeUnless { text ->
                    payload.structuredContent != null &&
                        runCatching { json.parseToJsonElement(text) }.getOrNull() ==
                        payload.structuredContent
                }
            val resultText = buildString {
                append(contentText)
                if (!structured.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(structured)
                }
                if (attachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(
                        if (attachments.size == 1) {
                            "[The tool returned one image. It is attached as visual context.]"
                        } else {
                            "[The tool returned ${attachments.size} images. They are attached as visual context.]"
                        },
                    )
                }
                if (isEmpty()) append(if (payload.isError) "MCP tool failed" else "MCP tool completed")
            }
            updateConnected(runtime, keepTools = true)
            ToolExecutionResult(
                text = resultText,
                images = attachments,
                structuredContent = structured,
                displayText = displayText,
                isError = payload.isError,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(runtime, e)
            scheduleRetry(runtime)
            ToolExecutionResult(
                text = "MCP tool '${descriptor.remote.name}' failed: ${userMessage(e)}",
                isError = true,
            )
        }
    }

    private fun reconcile(configs: List<McpServerConfig>) {
        val desiredIds = configs.mapTo(mutableSetOf(), McpServerConfig::id)
        val runtimesToClose = synchronized(lock) {
            buildList {
                runtimes.keys.filter { it !in desiredIds }.forEach { id ->
                    pendingBuilds.remove(id)
                    runtimes.remove(id)?.let(::add)
                }
                configs.filter { !it.enabled || it.url.isBlank() }.forEach { config ->
                    pendingBuilds.remove(config.id)
                    runtimes.remove(config.id)?.let(::add)
                }
            }.also {
                _snapshots.update { current ->
                    current.filterKeys(desiredIds::contains).toMutableMap().apply {
                        configs.filter { !it.enabled || it.url.isBlank() }.forEach { config ->
                            this[config.id] = McpServerSnapshot(
                                serverId = config.id,
                                status = McpConnectionStatus.IDLE,
                            )
                        }
                    }
                }
            }
        }
        runtimesToClose.forEach(Runtime::close)
        configs.filter { it.enabled && it.url.isNotBlank() }.forEach { config ->
            rebuildRuntime(config, McpRuntimeRefreshReason.RECONCILE)
        }
    }

    private fun rebuildRuntime(
        config: McpServerConfig,
        reason: McpRuntimeRefreshReason,
    ) {
        val build = synchronized(lock) {
            if (currentConfig(config.id) != config) return
            val runtime = runtimes[config.id]
            val pending = pendingBuilds[config.id]
            if (
                !shouldStartMcpRuntimeBuild(
                    reason = reason,
                    config = config,
                    runtimeConfig = runtime?.config,
                    runtimeConnectionActive = runtime?.connectionJob?.isActive == true,
                    runtimeStatus = snapshots.value[config.id]?.status,
                    pendingConfig = pending?.config,
                )
            ) {
                return
            }
            val ticket = McpRuntimeBuildTicket(
                generation = ++nextBuildGeneration,
                config = config,
            )
            pendingBuilds[config.id] = ticket
            RuntimeBuild(
                ticket = ticket,
                previousRuntime = runtimes.remove(config.id),
            )
        }

        build.previousRuntime?.close()
        putBuildSnapshotIfCurrent(
            ticket = build.ticket,
            snapshot = McpServerSnapshot(
                serverId = config.id,
                status = McpConnectionStatus.CONNECTING,
                tools = snapshots.value[config.id]?.tools.orEmpty(),
            ),
        )

        val runtime = try {
            Runtime(
                config = config,
                client = McpProtocolClient(
                    endpoint = normalizeEndpoint(config.url),
                    customHeaders = config.headers,
                    transportType = config.transport,
                ),
            )
        } catch (e: IllegalArgumentException) {
            finishBuildWithError(build.ticket, e)
            return
        }
        val connectionJob = launchConnectionLoop(runtime, start = CoroutineStart.LAZY)
        runtime.connectionJob = connectionJob
        val installed = synchronized(lock) {
            if (
                isCurrentMcpRuntimeBuild(
                    ticket = build.ticket,
                    pendingTicket = pendingBuilds[config.id],
                    currentConfig = currentConfig(config.id),
                )
            ) {
                pendingBuilds.remove(config.id)
                runtimes[config.id] = runtime
                true
            } else {
                false
            }
        }
        if (installed) {
            connectionJob.start()
        } else {
            runtime.close()
        }
    }

    private fun launchConnectionLoop(
        runtime: Runtime,
        start: CoroutineStart = CoroutineStart.DEFAULT,
    ): Job = scope.launch(workDispatcher, start = start) {
        var retryMs = INITIAL_RETRY_MS
        while (isActive && isCurrent(runtime)) {
            if (
                !putRuntimeSnapshotIfCurrent(
                    runtime = runtime,
                    snapshot = McpServerSnapshot(
                        serverId = runtime.config.id,
                        status = McpConnectionStatus.CONNECTING,
                        tools = snapshots.value[runtime.config.id]?.tools.orEmpty(),
                    ),
                )
            ) {
                return@launch
            }
            try {
                val remoteTools = connectionPermits.withPermit {
                    runtime.client.listTools()
                }
                    .distinctBy(McpRemoteTool::name)
                    .sortedBy(McpRemoteTool::name)
                val descriptors = remoteTools.map { remote ->
                    McpToolDescriptor(
                        publicName = publicMcpToolName(runtime.config.id, remote.name),
                        serverId = runtime.config.id,
                        serverName = runtime.config.name.ifBlank { runtime.config.url },
                        remote = remote,
                        enabled = remote.name !in runtime.config.disabledTools,
                    )
                }
                if (
                    !putRuntimeSnapshotIfCurrent(
                        runtime = runtime,
                        snapshot = McpServerSnapshot(
                            serverId = runtime.config.id,
                            status = McpConnectionStatus.CONNECTED,
                            tools = descriptors,
                            lastSyncedAt = System.currentTimeMillis(),
                        ),
                    )
                ) {
                    return@launch
                }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(runtime, e)
                delay(retryMs)
                retryMs = min(MAX_RETRY_MS, retryMs * 2)
            }
        }
    }

    private fun scheduleRetry(runtime: Runtime) {
        scope.launch(workDispatcher) {
            val retryJob = synchronized(lock) {
                if (runtimes[runtime.config.id] !== runtime || runtime.connectionJob?.isActive == true) {
                    return@launch
                }
                launchConnectionLoop(runtime, start = CoroutineStart.LAZY).also {
                    runtime.connectionJob = it
                }
            }
            retryJob.start()
        }
    }

    private fun updateConnected(runtime: Runtime, keepTools: Boolean) {
        synchronized(lock) {
            if (runtimes[runtime.config.id] !== runtime) return
            val previous = snapshots.value[runtime.config.id]
            putSnapshotLocked(
                McpServerSnapshot(
                    serverId = runtime.config.id,
                    status = McpConnectionStatus.CONNECTED,
                    tools = if (keepTools) previous?.tools.orEmpty() else emptyList(),
                    lastSyncedAt = previous?.lastSyncedAt ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun markError(runtime: Runtime, error: Exception) {
        val marked = synchronized(lock) {
            if (runtimes[runtime.config.id] !== runtime) return
            val previous = snapshots.value[runtime.config.id]
            putSnapshotLocked(
                McpServerSnapshot(
                    serverId = runtime.config.id,
                    status = McpConnectionStatus.ERROR,
                    tools = previous?.tools.orEmpty(),
                    error = userMessage(error),
                    lastSyncedAt = previous?.lastSyncedAt,
                ),
            )
            true
        }
        if (marked) {
            DebugLog.e("McpRegistry", "MCP server ${runtime.config.id} failed", error)
        }
    }

    private fun finishBuildWithError(ticket: McpRuntimeBuildTicket, error: Exception) {
        synchronized(lock) {
            if (
                !isCurrentMcpRuntimeBuild(
                    ticket = ticket,
                    pendingTicket = pendingBuilds[ticket.config.id],
                    currentConfig = currentConfig(ticket.config.id),
                )
            ) {
                return
            }
            pendingBuilds.remove(ticket.config.id)
            putSnapshotLocked(
                McpServerSnapshot(
                    serverId = ticket.config.id,
                    status = McpConnectionStatus.ERROR,
                    error = userMessage(error),
                ),
            )
        }
    }

    private fun putBuildSnapshotIfCurrent(
        ticket: McpRuntimeBuildTicket,
        snapshot: McpServerSnapshot,
    ): Boolean = synchronized(lock) {
        if (
            !isCurrentMcpRuntimeBuild(
                ticket = ticket,
                pendingTicket = pendingBuilds[ticket.config.id],
                currentConfig = currentConfig(ticket.config.id),
            )
        ) {
            false
        } else {
            putSnapshotLocked(snapshot)
            true
        }
    }

    private fun putRuntimeSnapshotIfCurrent(
        runtime: Runtime,
        snapshot: McpServerSnapshot,
    ): Boolean = synchronized(lock) {
        if (runtimes[runtime.config.id] !== runtime) {
            false
        } else {
            putSnapshotLocked(snapshot)
            true
        }
    }

    private fun isCurrent(runtime: Runtime): Boolean =
        synchronized(lock) { runtimes[runtime.config.id] === runtime }

    private fun currentConfig(serverId: String): McpServerConfig? =
        settings.mcpServers.value.firstOrNull { it.id == serverId }

    private fun putSnapshotLocked(snapshot: McpServerSnapshot) {
        _snapshots.update { it + (snapshot.serverId to snapshot) }
    }

    private fun normalizeEndpoint(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { java.net.URI(value) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid MCP URL")
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "MCP URL must use http or https"
        }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "Invalid MCP URL"
        }
        return value
    }

    private fun userMessage(error: Throwable): String =
        error.localizedMessage?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
}
