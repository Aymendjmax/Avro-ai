package com.newoether.agora.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.newoether.agora.R
import kotlinx.serialization.Serializable

/**
 * The high-level mode for an Avro conversation.
 *
 * - [ASK]: Ordinary chat flow (default). Lightweight, conversational, supporting RAG, Web search,
 *   attachments, and MCP tools. Shell and raw workspace manipulation tools are withheld.
 * - [WORK]: Autonomous agent environment. Enables the local sandbox, terminal execution, FileStore,
 *   workspace inspection, and active agentic tooling.
 */
@Serializable
enum class ConversationMode(val id: String) {
    ASK("ask"),
    WORK("work");

    val titleRes: Int
        get() = when (this) {
            ASK -> R.string.conversation_mode_ask
            WORK -> R.string.conversation_mode_work
        }

    val descriptionRes: Int
        get() = when (this) {
            ASK -> R.string.conversation_mode_ask_desc
            WORK -> R.string.conversation_mode_work_desc
        }

    val icon: ImageVector
        get() = when (this) {
            ASK -> Icons.Default.ChatBubbleOutline
            WORK -> Icons.Default.Terminal
        }

    companion object {
        val DEFAULT = ASK

        fun fromId(raw: String?): ConversationMode = when (raw?.lowercase()) {
            "work" -> WORK
            else -> ASK
        }
    }
}
