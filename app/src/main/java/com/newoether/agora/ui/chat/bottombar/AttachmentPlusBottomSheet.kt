package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ConversationMode
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet
import com.newoether.agora.ui.settings.PillTabSwitcher

/**
 * Bottom Sheet replacing the legacy attachment dropdown menu.
 * Surfaces:
 * 1. Primary attachments: Camera, Photos, Videos, Files.
 * 2. Segmented Mode Switcher: Ask | Work with dynamic description.
 * 3. Quick shortcuts for Skills and MCP configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPlusBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversationMode: ConversationMode,
    onConversationModeChange: (ConversationMode) -> Unit,
    shellReady: Boolean = true,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onVideos: () -> Unit,
    onFiles: () -> Unit,
    showCamera: Boolean = true,
    showVideos: Boolean = true,
    onSkillsClick: (() -> Unit)? = null,
    onMcpClick: (() -> Unit)? = null,
    onWorkSettingsClick: (() -> Unit)? = null,
) {
    if (!visible) return

    MotionAwareModalBottomSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            // 1. Top action items (Attachments)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCamera) {
                    AttachmentActionItem(
                        icon = Icons.Default.PhotoCamera,
                        label = stringResource(R.string.camera),
                        onClick = {
                            onDismissRequest()
                            onCamera()
                        },
                    )
                }
                AttachmentActionItem(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.photos),
                    onClick = {
                        onDismissRequest()
                        onPhotos()
                    },
                )
                if (showVideos) {
                    AttachmentActionItem(
                        icon = Icons.Default.Videocam,
                        label = stringResource(R.string.videos),
                        onClick = {
                            onDismissRequest()
                            onVideos()
                        },
                    )
                }
                AttachmentActionItem(
                    icon = Icons.Default.AttachFile,
                    label = stringResource(R.string.files),
                    onClick = {
                        onDismissRequest()
                        onFiles()
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 2. Conversation Mode Switcher (Ask | Work)
            Text(
                text = stringResource(R.string.conversation_mode_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            val tabs = listOf(
                stringResource(R.string.conversation_mode_ask),
                stringResource(R.string.conversation_mode_work),
            )
            val selectedIndex = if (conversationMode == ConversationMode.WORK) 1 else 0

            PillTabSwitcher(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    val newMode = if (idx == 1) ConversationMode.WORK else ConversationMode.ASK
                    if (newMode != conversationMode) {
                        onConversationModeChange(newMode)
                    }
                },
                tabHeight = 42.dp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(
                targetState = conversationMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "modeDescription",
            ) { mode ->
                Text(
                    text = stringResource(mode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            if (conversationMode == ConversationMode.WORK && !shellReady) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clickable(enabled = onWorkSettingsClick != null) {
                            onDismissRequest()
                            onWorkSettingsClick?.invoke()
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.work_mode_sandbox_unavailable_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 3. Quick Rows: Skills, MCP, & Work Mode Settings
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    IntegrationRowItem(
                        iconVector = Icons.Default.Extension,
                        title = stringResource(R.string.skills_row_label),
                        onClick = {
                            onDismissRequest()
                            onSkillsClick?.invoke()
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    IntegrationRowItem(
                        iconPainter = painterResource(R.drawable.ic_mcp),
                        title = stringResource(R.string.mcp_row_label),
                        onClick = {
                            onDismissRequest()
                            onMcpClick?.invoke()
                        },
                    )
                    if (conversationMode == ConversationMode.WORK && onWorkSettingsClick != null) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        IntegrationRowItem(
                            iconVector = ConversationMode.WORK.icon,
                            title = stringResource(R.string.settings_work_title),
                            onClick = {
                                onDismissRequest()
                                onWorkSettingsClick.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun IntegrationRowItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconVector: ImageVector? = null,
    iconPainter: Painter? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
