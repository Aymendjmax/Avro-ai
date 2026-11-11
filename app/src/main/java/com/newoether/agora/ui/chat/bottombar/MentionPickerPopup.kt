package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.MentionParser.ActiveMentionQuery
import com.newoether.agora.data.MentionParser.MentionType
import com.newoether.agora.data.SkillManager.SkillFileInfo

/**
 * Floating popup appearing above the chat composer when typing '/' for Skills or '@' for MCP servers.
 * Features tabs for quick switching between Skills and MCP with real-time query filtering,
 * full RTL/Arabic support, and IME compatibility.
 */
@Composable
internal fun MentionPickerPopup(
    activeQuery: ActiveMentionQuery?,
    availableSkills: List<SkillFileInfo>,
    availableMcpServers: List<McpServerConfig>,
    textFieldState: TextFieldState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = activeQuery != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        if (activeQuery == null) return@AnimatedVisibility

        val isSkill = activeQuery.type == MentionType.SKILL
        val query = activeQuery.query.lowercase()

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                // Interactive Header with Tabs: Skills (/) and MCP (@)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MentionTabPill(
                            selected = isSkill,
                            label = stringResource(R.string.skills_row_label),
                            symbol = "/",
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                if (!isSkill) {
                                    // Switch trigger from '@' to '/'
                                    textFieldState.edit {
                                        replace(activeQuery.startIndex, activeQuery.startIndex + 1, "/")
                                    }
                                }
                            },
                        )

                        MentionTabPill(
                            selected = !isSkill,
                            label = stringResource(R.string.mcp_row_label),
                            symbol = "@",
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_mcp),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                if (isSkill) {
                                    // Switch trigger from '/' to '@'
                                    textFieldState.edit {
                                        replace(activeQuery.startIndex, activeQuery.startIndex + 1, "@")
                                    }
                                }
                            },
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.collapse),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )

                // Filtered List Items
                if (isSkill) {
                    val filteredSkills = availableSkills.filter {
                        val base = it.name.removeSuffix(".md").lowercase()
                        base.contains(query) || it.description.lowercase().contains(query)
                    }

                    if (filteredSkills.isEmpty()) {
                        EmptyMentionNotice(stringResource(R.string.mention_no_skills_found))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                        ) {
                            items(filteredSkills, key = { it.name }) { skill ->
                                val cleanName = skill.name.removeSuffix(".md")
                                MentionOptionRow(
                                    title = cleanName,
                                    subtitle = skill.description.ifBlank { skill.name },
                                    prefix = "/",
                                    onClick = {
                                        textFieldState.edit {
                                            replace(
                                                activeQuery.startIndex,
                                                activeQuery.endIndex,
                                                "/$cleanName ",
                                            )
                                        }
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                } else {
                    val filteredMcp = availableMcpServers.filter {
                        it.name.lowercase().contains(query) || it.url.lowercase().contains(query)
                    }

                    if (filteredMcp.isEmpty()) {
                        EmptyMentionNotice(stringResource(R.string.mention_no_mcp_found))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                        ) {
                            items(filteredMcp, key = { it.id }) { server ->
                                MentionOptionRow(
                                    title = server.name,
                                    subtitle = server.url.ifBlank { "MCP Server" },
                                    prefix = "@",
                                    onClick = {
                                        textFieldState.edit {
                                            replace(
                                                activeQuery.startIndex,
                                                activeQuery.endIndex,
                                                "@${server.name} ",
                                            )
                                        }
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionTabPill(
    selected: Boolean,
    label: String,
    symbol: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .clip(CircleShape)
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            icon()
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$symbol $label",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EmptyMentionNotice(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun MentionOptionRow(
    title: String,
    subtitle: String,
    prefix: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (prefix == "/") MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
        ) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (prefix == "/") MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$prefix$title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (subtitle.isNotBlank() && subtitle != title) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
