package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ConversationMode

/**
 * Plus button trigger in the chat composer capsule that opens [AttachmentPlusBottomSheet].
 */
@Composable
internal fun AttachmentAddMenu(
    enabled: Boolean,
    conversationMode: ConversationMode = ConversationMode.DEFAULT,
    shellReady: Boolean = true,
    onConversationModeChange: (ConversationMode) -> Unit = {},
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onVideos: () -> Unit,
    onFiles: () -> Unit,
    showCamera: Boolean = true,
    showVideos: Boolean = true,
    onSkillsClick: (() -> Unit)? = null,
    onMcpClick: (() -> Unit)? = null,
    onWorkSettingsClick: (() -> Unit)? = null,
    sheetVisible: Boolean? = null,
    onSheetVisibleChange: ((Boolean) -> Unit)? = null,
) {
    var internalSheetVisible by remember { mutableStateOf(false) }
    val isVisible = sheetVisible ?: internalSheetVisible
    fun setVisible(value: Boolean) {
        if (onSheetVisibleChange != null) {
            onSheetVisibleChange(value)
        } else {
            internalSheetVisible = value
        }
    }

    IconButton(
        onClick = { if (enabled) setVisible(true) },
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            Icons.Default.Add,
            stringResource(R.string.add_attachment),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AttachmentPlusBottomSheet(
        visible = isVisible,
        onDismissRequest = { setVisible(false) },
        conversationMode = conversationMode,
        onConversationModeChange = onConversationModeChange,
        shellReady = shellReady,
        onCamera = onCamera,
        onPhotos = onPhotos,
        onVideos = onVideos,
        onFiles = onFiles,
        showCamera = showCamera,
        showVideos = showVideos,
        onSkillsClick = onSkillsClick,
        onMcpClick = onMcpClick,
        onWorkSettingsClick = onWorkSettingsClick,
    )
}
