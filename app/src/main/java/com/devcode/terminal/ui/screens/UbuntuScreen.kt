package com.devcode.terminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode.terminal.core.ubuntu.SetupWizard
import com.devcode.terminal.core.ubuntu.UbuntuManager
import com.devcode.terminal.ui.components.StatusRow
import kotlinx.coroutines.launch

@Composable
fun UbuntuScreen(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ubuntuState by UbuntuManager.state.collectAsState()
    val wizardLogs = remember { mutableStateListOf<String>() }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        UbuntuManager.refreshStatus()
    }

    val statusName = ubuntuState.status.name
    val isInstalled = ubuntuState.status == UbuntuManager.InstallStatus.INSTALLED
    val isDownloading = ubuntuState.downloadProgress in 0.001f..0.999f

    // Confirmation dialog before remove action
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = "REMOVE UBUNTU ROOTFS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to completely remove the Ubuntu rootfs at /data/local/devcode/ubuntu? This will delete all installed packages, configurations, and rootfs data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveDialog = false
                        scope.launch {
                            UbuntuManager.remove()
                            UbuntuManager.refreshStatus()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("CONFIRM REMOVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("CANCEL", style = MaterialTheme.typography.labelSmall)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF7DD3FC), RoundedCornerShape(2.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "UBUNTU // ROOTFS CONTROL",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // --- Status card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "ENVIRONMENT STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    StatusRow(label = "State", ok = isInstalled, detail = statusName)

                    val usedMb = ubuntuState.storageUsedBytes / (1024L * 1024L)
                    val availMb = ubuntuState.storageAvailBytes / (1024L * 1024L)
                    StatusRow(label = "Used", ok = true, detail = "$usedMb MB")
                    StatusRow(label = "Available", ok = availMb > 500, detail = "$availMb MB")

                    // Download progress bar
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "DOWNLOADING: ${(ubuntuState.downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ubuntuState.downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }

                    // Message log line
                    if (ubuntuState.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0B0F14), RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                text = ubuntuState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        // --- Install / Repair / Remove ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            UbuntuManager.install()
                            UbuntuManager.refreshStatus()
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("INSTALL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            UbuntuManager.repair()
                            UbuntuManager.refreshStatus()
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REPAIR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        showRemoveDialog = true
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REMOVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- Dev tools setup ---
        item {
            Button(
                onClick = {
                    scope.launch {
                        wizardLogs.clear()
                        wizardLogs.add(">> Launching dev setup wizard...")
                        val ok = SetupWizard.runDevSetup { line ->
                            wizardLogs.add(line)
                        }
                        wizardLogs.add(
                            if (ok) ">> Dev setup completed successfully."
                            else ">> Dev setup encountered an error."
                        )
                        UbuntuManager.refreshStatus()
                    }
                },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("SETUP DEV TOOLS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        // --- Wizard log output ---
        if (wizardLogs.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF080C10)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "WIZARD LOG",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { wizardLogs.clear() },
                                shape = RoundedCornerShape(2.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(24.dp),
                            ) {
                                Text("CLEAR", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            items(wizardLogs.toList()) { line ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF080C10))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = when {
                            line.startsWith(">>") -> MaterialTheme.colorScheme.primary
                            line.contains("error", ignoreCase = true) ||
                                    line.contains("failed", ignoreCase = true) -> Color(0xFFFB7185)
                            line.contains("success", ignoreCase = true) ||
                                    line.contains("done", ignoreCase = true) ||
                                    line.contains("ready", ignoreCase = true) -> Color(0xFF4ADE80)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
