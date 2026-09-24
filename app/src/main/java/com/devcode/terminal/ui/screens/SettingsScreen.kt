package com.devcode.terminal.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode.terminal.DevCodeApp
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.terminal.TerminalManager
import com.devcode.terminal.core.ubuntu.UbuntuManager
import com.devcode.terminal.core.update.UpdateManager
import com.devcode.terminal.core.update.UpdateState
import com.devcode.terminal.service.WorkspaceService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val fontSize by DevCodeApp.settings.fontSize.collectAsState(initial = 14f)
    val savedToken by DevCodeApp.settings.githubToken.collectAsState(initial = "")
    val updaterStatus by UpdateManager.status.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    var showExitDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var statusOutput by remember { mutableStateOf("") }
    var customTokenInput by remember(savedToken) { mutableStateOf(savedToken) }
    var showTokenConfig by remember { mutableStateOf(false) }

    val currentVersionCode = UpdateManager.getAppVersionCode(context)

    // Full exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Exit & Terminate Everything?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will stop all running terminal sessions, unmount all chroot filesystems, stop the background service, and close the application.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        scope.launch {
                            TerminalManager.sessions.value.toList().forEach { session ->
                                try {
                                    session.stop()
                                    TerminalManager.closeSession(session.id)
                                } catch (_: Throwable) {}
                            }
                            ChrootManager.stopAllCleanly()
                            WorkspaceService.killAllSessionsAndUnmount(context)
                            activity?.finishAndRemoveTask()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Exit & Terminate", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    // Clean uninstall dialog
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            },
            title = {
                Text("Uninstall DEVCODE Rootfs?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Executes 'devcode uninstall': cleanly kills all sessions, unmounts all virtual filesystems, and completely wipes /data/local/devcode without leaving zombie processes. Other chroots will NOT be touched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstallDialog = false
                        scope.launch {
                            UbuntuManager.uninstallCleanly()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clean Uninstall", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    // Status report dialog
    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            icon = {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            },
            title = {
                Text("DEVCODE System Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF070B0E), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = statusOutput.ifBlank { "Fetching status..." },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFE2E8F0),
                        lineHeight = 15.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text("Close", style = MaterialTheme.typography.labelMedium)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Workstation preferences, updates & lifecycle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- In-App Updates Card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "APPLICATION UPDATES",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Build $currentVersionCode",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (updaterStatus.state == UpdateState.AVAILABLE && updaterStatus.updateInfo != null) {
                        val info = updaterStatus.updateInfo!!
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF10B981).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "New Version: ${info.tagName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (info.releaseBody.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = info.releaseBody.take(200) + if (info.releaseBody.length > 200) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    if (updaterStatus.state == UpdateState.DOWNLOADING || updaterStatus.state == UpdateState.INSTALLING) {
                        Text(
                            text = updaterStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { updaterStatus.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    } else if (updaterStatus.message.isNotBlank()) {
                        Text(
                            text = updaterStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updaterStatus.state == UpdateState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    UpdateManager.checkForUpdates(context, customTokenInput)
                                }
                            },
                            enabled = updaterStatus.state != UpdateState.CHECKING && updaterStatus.state != UpdateState.DOWNLOADING,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (updaterStatus.state == UpdateState.CHECKING) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Checking...")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Update", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        if (updaterStatus.state == UpdateState.AVAILABLE && updaterStatus.updateInfo != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        UpdateManager.downloadAndInstall(
                                            context = context,
                                            info = updaterStatus.updateInfo!!,
                                            customToken = customTokenInput,
                                            preferRootInstall = true
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Install Now", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    // Optional Token Config toggle
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showTokenConfig = !showTokenConfig },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (showTokenConfig) "Hide GitHub Token Config" else "Configure GitHub Access Token",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (showTokenConfig) {
                        OutlinedTextField(
                            value = customTokenInput,
                            onValueChange = {
                                customTokenInput = it
                                scope.launch { DevCodeApp.settings.setGithubToken(it) }
                            },
                            label = { Text("GitHub Token (for private repo updates)", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }

        // --- Terminal Appearance ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "TERMINAL APPEARANCE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Console Font Size",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${fontSize.toInt()} sp",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Slider(
                        value = fontSize,
                        onValueChange = { new ->
                            scope.launch { DevCodeApp.settings.setFontSize(new) }
                        },
                        valueRange = 10f..22f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Live preview box
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF070B0E), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = "coder@localhost:~$ sudo apt update",
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color(0xFF38BDF8),
                            )
                            Text(
                                text = "Hit:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease",
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color(0xFF94A3B8),
                            )
                            Text(
                                text = "All packages are up to date.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color(0xFF34D399),
                            )
                        }
                    }
                }
            }
        }

        // --- Environment Info ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ENVIRONMENT PATHS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    InfoRow(
                        icon = Icons.Default.Code,
                        label = "App Package ID",
                        value = "com.devcode.terminal",
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 32.dp, top = 6.dp, bottom = 6.dp),
                    )
                    InfoRow(
                        icon = Icons.Default.Layers,
                        label = "Isolated Rootfs Path",
                        value = ChrootManager.UBUNTU_ROOT,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 32.dp, top = 6.dp, bottom = 6.dp),
                    )
                    InfoRow(
                        icon = Icons.Default.FolderOpen,
                        label = "Workspace Path",
                        value = ChrootManager.WORKSPACE,
                    )
                }
            }
        }

        // --- Lifecycle & CLI Command Manager ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DEVCODE COMMAND MANAGER",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Manage DEVCODE sessions, mounts, and uninstallation without affecting other chroots on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    statusOutput = ChrootManager.getDevcodeStatus()
                                    showStatusDialog = true
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("devcode status", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    ChrootManager.stopAllCleanly()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("devcode stop", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { showUninstallDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("devcode uninstall (Clean Wipe)", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showExitDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PowerOff, null, Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exit App & Kill All Sessions", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
