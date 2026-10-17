package com.newoether.agora.data

import kotlinx.serialization.Serializable

internal object NativeBackupFormat {
    /** v6 adds messages[].sourceJson (automatic user input source); v5 archives import with none. */
    const val CURRENT_VERSION = 6
    const val MIN_SUPPORTED_VERSION = 1
    /**
     * Backups older than this embedded secrets inside settings. From this version on secrets live
     * only in [SECRETS_ENTRY], so settings-embedded secrets are never trusted.
     */
    const val SEPARATE_SECRETS_SINCE_VERSION = 5
    /**
     * Written to the manifest of every backup whose conversation items can be reused by the next
     * incremental export. Older backups predate complete dataChangedAt tracking, so they lack it
     * and the next export rebuilds every conversation.
     */
    const val INCREMENTAL_BASELINE_REVISION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val CONVERSATIONS_ENTRY = "conversations.json"
    const val CONVERSATION_INDEX_ENTRY = "conv/index.json"
    const val CONVERSATION_ENTRY_PREFIX = "conv/items/"
    const val TASKS_ENTRY = "conv/tasks.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val LEGACY_EXTRA_SETTINGS_ENTRY = "extra_settings.json"
    const val SECRETS_ENTRY = "api_keys.json"
    const val SYSTEM_PROMPTS_ENTRY = "system_prompts.json"
    const val CUSTOM_FONT_ENTRY = "custom_font/font"

    const val IMAGE_MEDIA_PREFIX = "media/images/"
    const val VIDEO_MEDIA_PREFIX = "media/videos/"
    const val DRAFT_MEDIA_PREFIX = "media/drafts/"

    fun isSupported(version: Int): Boolean =
        version in MIN_SUPPORTED_VERSION..CURRENT_VERSION

    fun conversationEntry(id: String): String =
        CONVERSATION_ENTRY_PREFIX + id.encodeToByteArray().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        } + ".json"
}

@Serializable
internal data class NativeConversationIndex(
    val conversations: List<NativeConversationIndexEntry>,
)

@Serializable
internal data class NativeConversationIndexEntry(
    val id: String,
    val dataChangedAt: Long,
    val entry: String,
    val mediaEntries: List<String> = emptyList(),
)

@Serializable
internal data class ShellDeviceSecrets(
    val apiKey: String = "",
    val sshPassword: String = "",
)

@Serializable
internal data class NativeBackupSecrets(
    val apiKeys: List<ApiKeyEntry> = emptyList(),
    val activeApiKeyIds: Map<String, String> = emptyMap(),
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val proxyPassword: String = "",
    val shellDevices: Map<String, ShellDeviceSecrets> = emptyMap(),
    /** v1-v3 compatibility only. v4 always keys shell credentials by stable device ID. */
    val shellApiKeys: Map<String, String> = emptyMap(),
    val embeddingApiKeys: Map<String, String> = emptyMap(),
    val mcpHeaders: Map<String, Map<String, String>> = emptyMap(),
)

internal fun ShellDeviceConfig.withoutSecrets(): ShellDeviceConfig =
    copy(apiKey = "", sshPassword = "")

internal fun McpServerConfig.withoutSecrets(): McpServerConfig =
    copy(headers = emptyMap())

internal fun EmbeddingModelConfig.asPortableRemoteConfig(): EmbeddingModelConfig? =
    takeIf { it.type == EmbeddingModelType.REMOTE }
        ?.copy(remoteApiKey = "", localFilePath = "")
