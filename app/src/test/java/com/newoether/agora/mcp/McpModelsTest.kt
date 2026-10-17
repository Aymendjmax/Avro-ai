package com.newoether.agora.mcp

import com.newoether.agora.data.McpServerConfig
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpModelsTest {
    @Test
    fun publicNamesAreStableBoundedAndCollisionResistant() {
        val first = publicMcpToolName("server-123", "read image")
        val repeated = publicMcpToolName("server-123", "read image")
        val punctuationVariant = publicMcpToolName("server-123", "read-image")

        assertEquals(first, repeated)
        assertNotEquals(first, punctuationVariant)
        assertTrue(first.startsWith("mcp_server123_"))
        assertTrue(first.length <= 64)
    }

    @Test
    fun jsonSchemaIsConvertedWithoutInventingRequiredFields() {
        val remote = McpRemoteTool(
            name = "inspect",
            description = "Inspect a value",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "path",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Absolute path")
                            },
                        )
                        put(
                            "tags",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("unknown"))
                    },
                )
            },
        )

        val definition = McpToolDescriptor(
            publicName = "mcp_server_inspect",
            serverId = "server",
            serverName = "Test",
            remote = remote,
        ).asToolDefinition()

        assertEquals(listOf("path"), definition.function.parameters.required)
        assertEquals("string", definition.function.parameters.properties["path"]?.type)
        assertEquals("array", definition.function.parameters.properties["tags"]?.type)
        assertEquals(
            "string",
            definition.function.parameters.properties["tags"]?.items?.type,
        )
    }

    @Test
    fun pageEntryRefreshSelectsEveryEnabledServerExceptCurrentlyConnecting() {
        val configs = listOf(
            McpServerConfig(id = "connected", enabled = true, url = "https://one.example/mcp"),
            McpServerConfig(id = "new", enabled = true, url = "https://two.example/mcp"),
            McpServerConfig(id = "connecting", enabled = true, url = "https://three.example/mcp"),
            McpServerConfig(id = "error", enabled = true, url = "https://error.example/mcp"),
            McpServerConfig(id = "disabled", enabled = false, url = "https://four.example/mcp"),
            McpServerConfig(id = "blank", enabled = true, url = ""),
        )
        val snapshots = mapOf(
            "connected" to McpServerSnapshot(
                serverId = "connected",
                status = McpConnectionStatus.CONNECTED,
            ),
            "connecting" to McpServerSnapshot(
                serverId = "connecting",
                status = McpConnectionStatus.CONNECTING,
            ),
            "error" to McpServerSnapshot(
                serverId = "error",
                status = McpConnectionStatus.ERROR,
            ),
        )

        assertEquals(
            listOf("connected", "new", "error"),
            mcpServerIdsForPageEntryRefresh(configs, snapshots),
        )
    }

    @Test
    fun pageEntryRuntimeBuildsSkipOnlyConnectingOrExactPendingConfig() {
        val config = McpServerConfig(
            id = "server",
            enabled = true,
            url = "https://example.com/mcp",
        )

        assertFalse(
            shouldStartMcpRuntimeBuild(
                reason = McpRuntimeRefreshReason.PAGE_ENTRY,
                config = config,
                runtimeConfig = config,
                runtimeConnectionActive = true,
                runtimeStatus = McpConnectionStatus.CONNECTING,
                pendingConfig = null,
            ),
        )
        assertFalse(
            shouldStartMcpRuntimeBuild(
                reason = McpRuntimeRefreshReason.PAGE_ENTRY,
                config = config,
                runtimeConfig = null,
                runtimeConnectionActive = false,
                runtimeStatus = McpConnectionStatus.CONNECTING,
                pendingConfig = config,
            ),
        )
        assertTrue(
            shouldStartMcpRuntimeBuild(
                reason = McpRuntimeRefreshReason.PAGE_ENTRY,
                config = config,
                runtimeConfig = config,
                runtimeConnectionActive = true,
                runtimeStatus = McpConnectionStatus.ERROR,
                pendingConfig = null,
            ),
        )
        assertTrue(
            shouldStartMcpRuntimeBuild(
                reason = McpRuntimeRefreshReason.PAGE_ENTRY,
                config = config,
                runtimeConfig = config,
                runtimeConnectionActive = false,
                runtimeStatus = McpConnectionStatus.CONNECTED,
                pendingConfig = null,
            ),
        )
    }

    @Test
    fun mcpConnectionsAreCappedToTwoConcurrentAttempts() {
        assertEquals(2, McpRegistry.MAX_CONCURRENT_CONNECTIONS)
    }

    @Test
    fun staleRuntimeBuildCannotInstallOverNewOrDisabledConfig() {
        val oldConfig = McpServerConfig(
            id = "server",
            enabled = true,
            url = "https://old.example/mcp",
        )
        val newConfig = oldConfig.copy(url = "https://new.example/mcp")
        val oldTicket = McpRuntimeBuildTicket(generation = 1L, config = oldConfig)
        val newTicket = McpRuntimeBuildTicket(generation = 2L, config = newConfig)

        assertFalse(
            isCurrentMcpRuntimeBuild(
                ticket = oldTicket,
                pendingTicket = newTicket,
                currentConfig = newConfig,
            ),
        )
        assertFalse(
            isCurrentMcpRuntimeBuild(
                ticket = newTicket,
                pendingTicket = newTicket,
                currentConfig = newConfig.copy(enabled = false),
            ),
        )
        assertTrue(
            isCurrentMcpRuntimeBuild(
                ticket = newTicket,
                pendingTicket = newTicket,
                currentConfig = newConfig,
            ),
        )
    }
}
