package com.newoether.agora.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ConversationMode
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated settings page for Work Mode and autonomous agent workspace management.
 *
 * Consolidates:
 * 1. Mode behavior (Default mode for new chats, auto request_work tool, managed directives).
 * 2. Linux sandbox environment & shell execution policies.
 * 3. Multimodal tool integrations (view_image, MCP file bridge, present_files).
 * 4. Workspace storage inspection and cleanup (uploads, outputs, projects).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWorkPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val defaultModeId by viewModel.settings.defaultConversationMode.collectAsState()
    val requestWorkToolEnabled by viewModel.settings.requestWorkToolEnabled.collectAsState()
    val shellConfirmEnabled by viewModel.settings.shellConfirmEnabled.collectAsState()
    val sandboxSharedStorageEnabled by viewModel.settings.sandboxSharedStorageEnabled.collectAsState()

    // Sandbox management sub-pane navigation
    var showSandboxMgmt by remember { mutableStateOf(false) }
    BackHandler(enabled = showSandboxMgmt) { showSandboxMgmt = false }

    // Dialog states
    var showDefaultModeDialog by remember { mutableStateOf(false) }
    var showDirectivesInfoDialog by remember { mutableStateOf(false) }
    var showCleanOutputsDialog by remember { mutableStateOf(false) }
    var showCleanUploadsDialog by remember { mutableStateOf(false) }
    var showResetWorkspaceDialog by remember { mutableStateOf(false) }

    // Workspace storage statistics
    var storageStats by remember { mutableStateOf<FileStore.WorkspaceStorageStats?>(null) }
    var isCalculatingStorage by remember { mutableStateOf(false) }

    fun refreshStorage() {
        scope.launch {
            isCalculatingStorage = true
            val stats = withContext(Dispatchers.IO) {
                FileStore.calculateWorkspaceStorage(context)
            }
            storageStats = stats
            isCalculatingStorage = false
        }
    }

    LaunchedEffect(Unit) {
        refreshStorage()
    }

    val isSandboxReady = viewModel.sandboxManager?.isAvailableSync() == true

    GuardedAnimatedContent(
        targetState = showSandboxMgmt,
        forward = showSandboxMgmt,
    ) { isMgmt ->
        if (isMgmt && viewModel.sandboxManager != null) {
            SettingsSecondaryPane {
                SettingsSandboxPage(
                    sandboxManager = viewModel.sandboxManager!!,
                    onBack = { showSandboxMgmt = false },
                    showDocFab = showDocFab,
                    sharedStorageEnabled = sandboxSharedStorageEnabled,
                    onSharedStorageEnabledChange = viewModel.settings::setSandboxSharedStorageEnabled,
                )
            }
        } else {
            CollapsingSettingsScaffold(
                title = stringResource(R.string.settings_work_title),
                onBack = onBack,
                scrollState = scrollState,
                floatingActionButton = { if (showDocFab) DocumentationFab("work.md") },
            ) {
                SettingsGroupColumn {
                    // ── Group 1: Operating Mode & Behavior ──
                    SettingsGroup(
                        title = stringResource(R.string.work_group_mode),
                        items = buildList {
                            // Default Mode for new chats
                            add {
                                val currentDefaultMode = ConversationMode.fromId(defaultModeId)
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_default_mode)) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                if (currentDefaultMode == ConversationMode.WORK) {
                                                    R.string.conversation_mode_work
                                                } else {
                                                    R.string.conversation_mode_ask
                                                },
                                            ),
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = currentDefaultMode.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable { showDefaultModeDialog = true },
                                )
                            }

                            // Allow Request Work tool in Ask Mode
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_request_tool)) },
                                    supportingContent = { Text(stringResource(R.string.work_request_tool_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.SwitchAccessShortcut,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = requestWorkToolEnabled,
                                            onCheckedChange = { viewModel.settings.setRequestWorkToolEnabled(it) },
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.settings.setRequestWorkToolEnabled(!requestWorkToolEnabled)
                                    },
                                )
                            }

                            // Work Mode Directives & Prompts
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_directives_title)) },
                                    supportingContent = { Text(stringResource(R.string.work_directives_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable { showDirectivesInfoDialog = true },
                                )
                            }
                        },
                    )

                    // ── Group 2: Sandbox & Execution ──
                    SettingsGroup(
                        title = stringResource(R.string.work_group_environment),
                        items = buildList {
                            // Local Sandbox status & entry
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.sandbox_title)) },
                                    supportingContent = {
                                        Text(
                                            if (isSandboxReady) {
                                                stringResource(R.string.work_sandbox_status_ready)
                                            } else {
                                                stringResource(R.string.work_sandbox_status_not_installed)
                                            },
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Terminal,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stringResource(R.string.work_sandbox_manage),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable { showSandboxMgmt = true },
                                )
                            }

                            // Remote command confirmation
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.shell_confirm_setting)) },
                                    supportingContent = { Text(stringResource(R.string.shell_confirm_setting_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = shellConfirmEnabled,
                                            onCheckedChange = { viewModel.shellConfirmation.setEnabled(it) },
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.shellConfirmation.setEnabled(!shellConfirmEnabled)
                                    },
                                )
                            }

                            // Shared storage access
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.sandbox_shared_storage)) },
                                    supportingContent = { Text(stringResource(R.string.sandbox_shared_storage_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.FolderShared,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = sandboxSharedStorageEnabled,
                                            onCheckedChange = { viewModel.settings.setSandboxSharedStorageEnabled(it) },
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.settings.setSandboxSharedStorageEnabled(!sandboxSharedStorageEnabled)
                                    },
                                )
                            }
                        },
                    )

                    // ── Group 3: Tools & Deliverables ──
                    SettingsGroup(
                        title = stringResource(R.string.work_group_tools),
                        items = buildList {
                            // Visual Inspection (view_image)
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_view_image_title)) },
                                    supportingContent = { Text(stringResource(R.string.work_view_image_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.ImageSearch,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(stringResource(R.string.work_active_badge)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    },
                                )
                            }

                            // MCP File Bridge
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_mcp_bridge_title)) },
                                    supportingContent = { Text(stringResource(R.string.work_mcp_bridge_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.SyncAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(stringResource(R.string.work_active_badge)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    },
                                )
                            }

                            // Artifact Presentation Cards
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_deliverables_title)) },
                                    supportingContent = { Text(stringResource(R.string.work_deliverables_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(stringResource(R.string.work_active_badge)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )

                    // ── Group 4: Storage & Workspace Cleanup ──
                    SettingsGroup(
                        title = stringResource(R.string.work_group_storage),
                        items = buildList {
                            // Total usage breakdown
                            add {
                                val currentStats = storageStats
                                val totalStr = FileStore.formatStorageSize(currentStats?.totalBytes ?: 0L)
                                val uploadsStr = FileStore.formatStorageSize(currentStats?.uploadsBytes ?: 0L)
                                val outputsStr = FileStore.formatStorageSize(currentStats?.outputsBytes ?: 0L)
                                val projectsStr = FileStore.formatStorageSize(
                                    (currentStats?.projectsBytes ?: 0L) + (currentStats?.notesBytes ?: 0L),
                                )

                                SettingsItem(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.work_storage_total))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = totalStr,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.work_storage_breakdown,
                                                uploadsStr,
                                                outputsStr,
                                                projectsStr,
                                            ),
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Storage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(
                                            onClick = { refreshStorage() },
                                            enabled = !isCalculatingStorage,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = stringResource(R.string.refresh_action),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }

                            // Clean Outputs
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_clean_outputs)) },
                                    supportingContent = { Text(stringResource(R.string.work_clean_outputs_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable { showCleanOutputsDialog = true },
                                )
                            }

                            // Clean Uploads
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.work_clean_uploads)) },
                                    supportingContent = { Text(stringResource(R.string.work_clean_uploads_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable { showCleanUploadsDialog = true },
                                )
                            }

                            // Reset Entire Workspace
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            text = stringResource(R.string.work_reset_workspace),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    supportingContent = { Text(stringResource(R.string.work_reset_workspace_desc)) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    modifier = Modifier.clickable { showResetWorkspaceDialog = true },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Dialog: Default Conversation Mode ──
    if (showDefaultModeDialog) {
        val current = ConversationMode.fromId(defaultModeId)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDefaultModeDialog = false },
            title = { Text(stringResource(R.string.work_default_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ConversationMode.ASK, ConversationMode.WORK).forEach { mode ->
                        val selected = mode == current
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (selected) 2.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.settings.setDefaultConversationMode(mode.id)
                                    showDefaultModeDialog = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(mode.titleRes),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = stringResource(mode.descriptionRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultModeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Dialog: Directives Info ──
    if (showDirectivesInfoDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDirectivesInfoDialog = false },
            title = { Text(stringResource(R.string.work_mode_prompt_section_title)) },
            text = {
                Text(
                    text = stringResource(R.string.work_mode_prompt_section_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDirectivesInfoDialog = false }) {
                    Text(stringResource(R.string.close_action))
                }
            },
        )
    }

    // ── Dialog: Clean Outputs Confirmation ──
    if (showCleanOutputsDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showCleanOutputsDialog = false },
            title = { Text(stringResource(R.string.work_clean_outputs_confirm_title)) },
            text = { Text(stringResource(R.string.work_clean_outputs_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanOutputsDialog = false
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                FileStore.clearOutputs(context)
                            }
                            refreshStorage()
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.work_storage_cleaned),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanOutputsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Dialog: Clean Uploads Confirmation ──
    if (showCleanUploadsDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showCleanUploadsDialog = false },
            title = { Text(stringResource(R.string.work_clean_uploads_confirm_title)) },
            text = { Text(stringResource(R.string.work_clean_uploads_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanUploadsDialog = false
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                FileStore.clearUploads(context)
                            }
                            refreshStorage()
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.work_storage_cleaned),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanUploadsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Dialog: Reset Workspace Confirmation ──
    if (showResetWorkspaceDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showResetWorkspaceDialog = false },
            title = { Text(stringResource(R.string.work_reset_workspace_confirm_title)) },
            text = { Text(stringResource(R.string.work_reset_workspace_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWorkspaceDialog = false
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                FileStore.clearWorkspace(context)
                            }
                            refreshStorage()
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.work_storage_cleaned),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetWorkspaceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
