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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
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

    val isInstalled = ubuntuState.status == UbuntuManager.InstallStatus.INSTALLED
    val isBusy = ubuntuState.busy
    val isDownloading = ubuntuState.status == UbuntuManager.InstallStatus.DOWNLOADING
    val isVerifying = ubuntuState.status == UbuntuManager.InstallStatus.VERIFYING
    val isExtracting = ubuntuState.status == UbuntuManager.InstallStatus.EXTRACTING
    val isCorrupt = ubuntuState.status == UbuntuManager.InstallStatus.CORRUPT

    // Confirmation dialog before remove action
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Remove Ubuntu Rootfs?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "This will remove the entire Ubuntu 24.04 filesystem at /data/local/devcode/ubuntu, including all installed apt packages and configs. User files inside /home/coder/projects will be wiped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Delete Rootfs", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
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
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ubuntu Manager",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "24.04.5 LTS ARM64 Isolated Chroot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (isInstalled) Color(0xFF10B981).copy(alpha = 0.12f)
                            else if (isBusy) Color(0xFF0EA5E9).copy(alpha = 0.12f)
                            else if (isCorrupt) Color(0xFFF43F5E).copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isInstalled) "INSTALLED"
                        else if (isBusy) ubuntuState.status.name
                        else if (isCorrupt) "CORRUPTED"
                        else "AVAILABLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isInstalled) Color(0xFF10B981)
                        else if (isBusy) Color(0xFF0EA5E9)
                        else if (isCorrupt) Color(0xFFF43F5E)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Status overview card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LIFECYCLE STATUS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    StatusRow(
                        label = "State",
                        ok = isInstalled,
                        detail = ubuntuState.status.name,
                    )

                    val usedMb = ubuntuState.storageUsedBytes / (1024L * 1024L)
                    val availMb = ubuntuState.storageAvailBytes / (1024L * 1024L)
                    StatusRow(label = "Chroot Size", ok = true, detail = "$usedMb MB", isMonospaceDetail = true)
                    StatusRow(label = "Available Disk", ok = availMb > 500, detail = "$availMb MB", isMonospaceDetail = true)

                    // Download / Processing progress bar
                    if (isBusy) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isDownloading -> "Downloading rootfs: ${(ubuntuState.downloadProgress * 100).toInt()}%"
                                    isVerifying   -> "Verifying SHA-256 checksum..."
                                    isExtracting  -> "Extracting and bootstrapping..."
                                    else          -> "Processing..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { ubuntuState.downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }

                    // Message log banner
                    if (ubuntuState.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCorrupt) Color(0xFFF43F5E).copy(alpha = 0.1f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp),
                        ) {
                            Text(
                                text = ubuntuState.message,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (isCorrupt) Color(0xFFF43F5E) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Detailed installation step-by-step logs
                    if (ubuntuState.stepLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF070B0E), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ubuntuState.stepLogs.takeLast(6).forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = when {
                                            logLine.contains("SUCCESS", ignoreCase = true) -> Color(0xFF10B981)
                                            logLine.contains("FAILED", ignoreCase = true)  -> Color(0xFFF43F5E)
                                            logLine.contains("Step", ignoreCase = true)    -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Action Buttons ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "OPERATIONS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isInstalled) {
                        Button(
                            onClick = {
                                scope.launch {
                                    UbuntuManager.install()
                                    UbuntuManager.refreshStatus()
                                }
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBusy) "Installing..." else "Install Ubuntu 24.04 ARM64",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        if (isCorrupt) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        UbuntuManager.forceRemove()
                                        UbuntuManager.refreshStatus()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Force Clean Corrupted Files", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        UbuntuManager.repair()
                                        UbuntuManager.refreshStatus()
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Repair Config", style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = { showRemoveDialog = true },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Remove", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // --- Dev tools setup section ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DEVELOPMENT TOOLCHAIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Installs essential packages: git, curl, python3, pip, nodejs, npm, build-essential, vim, and nano.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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
                        enabled = isInstalled && !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!isInstalled) "Install Ubuntu First" else "Provision Dev Packages",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        // --- Wizard log output ---
        if (wizardLogs.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090D12)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Setup Output Log",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { wizardLogs.clear() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        wizardLogs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = when {
                                    line.startsWith(">>") -> MaterialTheme.colorScheme.primary
                                    line.contains("error", ignoreCase = true) ||
                                            line.contains("failed", ignoreCase = true) -> Color(0xFFF43F5E)
                                    line.contains("success", ignoreCase = true) ||
                                            line.contains("complete", ignoreCase = true) ||
                                            line.contains("ready", ignoreCase = true) -> Color(0xFF10B981)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
