package com.devcode.terminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode.terminal.DevCodeApp
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.terminal.TerminalManager
import com.devcode.terminal.core.terminal.TerminalSession
import com.devcode.terminal.service.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val sessions by TerminalManager.sessions.collectAsState()
    val activeId by TerminalManager.activeId.collectAsState()
    val fontSize by DevCodeApp.settings.fontSize.collectAsState(initial = 14f)
    val active = sessions.firstOrNull { it.id == activeId }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showTerminateDialog by remember { mutableStateOf(false) }

    // Terminate all sessions confirmation dialog
    if (showTerminateDialog) {
        AlertDialog(
            onDismissRequest = { showTerminateDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.PowerOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Terminate All Sessions?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will forcibly kill all active background shell processes, unmount chroot virtual filesystems, and stop the workspace service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTerminateDialog = false
                        scope.launch {
                            try {
                                TerminalManager.sessions.value.toList().forEach { s ->
                                    try {
                                        s.stop()
                                        TerminalManager.closeSession(s.id)
                                    } catch (_: Throwable) {}
                                }
                                ChrootManager.stopAllCleanly()
                                WorkspaceService.stop(context)
                            } catch (_: Throwable) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Terminate All", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTerminateDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // --- Top Bar ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Console",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )

            // Add new session tab button
            IconButton(
                onClick = {
                    val s = TerminalManager.createSession("Shell ${sessions.size + 1}")
                    scope.launch { s.start() }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New session",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // More options menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Terminate All Sessions", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.PowerOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            showMenu = false
                            showTerminateDialog = true
                        }
                    )
                }
            }
        }

        // --- Session Chips Bar ---
        if (sessions.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    val running by session.isRunning.collectAsState()
                    val isSelected = activeId == session.id

                    FilterChip(
                        selected = isSelected,
                        onClick = { TerminalManager.setActive(session.id) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        color = if (running) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape = CircleShape
                                    )
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        session.stop()
                                        TerminalManager.closeSession(session.id)
                                    }
                                },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(12.dp))
                            }
                        },
                        label = {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }

        // --- Main Console Window ---
        if (active == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Text(
                            text = "No Active Terminal Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Launch an interactive shell to run bash commands inside the Ubuntu chroot environment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                val s = TerminalManager.createSession("Shell 1")
                                scope.launch { s.start() }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Launch Shell Session", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        } else {
            key(active.id) {
                SessionConsole(
                    session = active,
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SessionConsole(session: TerminalSession, fontSize: Float, modifier: Modifier) {
    val output by session.output.collectAsState()
    val running by session.isRunning.collectAsState()
    val lines = remember(output) { output.split('\n') }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var command by rememberSaveable(session.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember(output) { mutableStateOf(false) }

    // Command history
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }

    // Automatically start the session if not running
    LaunchedEffect(session.id) {
        if (!session.isRunning.value) {
            session.start()
        }
    }

    fun send() {
        val target = TerminalManager.active() ?: return
        if (target.id != session.id || command.isBlank() || sending) return
        val submitted = command
        history.add(submitted)
        historyIndex = -1
        sending = true
        command = ""
        error = null
        scope.launch {
            try {
                target.execute(submitted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Command failed"
            } finally {
                sending = false
            }
        }
    }

    LaunchedEffect(output) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex.coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Console Header info
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (running) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (running) "coder@ubuntu:~$ (Live)" else "Session Idle",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            TextButton(
                enabled = output.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(output))
                    copied = true
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "Copied" else "Copy Output",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // Terminal Screen Output Container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF070B0E))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
        ) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(lines.size) { index ->
                        Text(
                            text = if (output.isEmpty()) "$ coder@ubuntu:~$ " else lines[index],
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.35f).sp,
                            color = Color(0xFFE2E8F0),
                        )
                    }
                }
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- Mobile Terminal Accessory Keyboard Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ESC key
            AccessoryKey("ESC") {
                scope.launch { session.sendInput("\u001b") }
            }
            // TAB key
            AccessoryKey("TAB") {
                scope.launch { session.sendInput("\t") }
            }
            // CTRL+C key
            AccessoryKey("CTRL+C", isAccent = true) {
                scope.launch { session.sendInput("\u0003") }
            }
            // CTRL+D key
            AccessoryKey("CTRL+D") {
                scope.launch { session.sendInput("\u0004") }
            }
            // History UP
            AccessoryKey("▲") {
                if (history.isNotEmpty()) {
                    val nextIdx = if (historyIndex == -1) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
                    historyIndex = nextIdx
                    command = history[nextIdx]
                }
            }
            // History DOWN
            AccessoryKey("▼") {
                if (history.isNotEmpty() && historyIndex != -1) {
                    val nextIdx = historyIndex + 1
                    if (nextIdx <= history.lastIndex) {
                        historyIndex = nextIdx
                        command = history[nextIdx]
                    } else {
                        historyIndex = -1
                        command = ""
                    }
                }
            }
            // Common developer symbols
            AccessoryKey("|") { command += "|" }
            AccessoryKey("/") { command += "/" }
            AccessoryKey("~") { command += "~" }
            AccessoryKey("-") { command += "-" }
            AccessoryKey("&") { command += "&" }
            AccessoryKey("$") { command += "$" }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- Command Input Field & Send Button ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Enter bash command...",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp
                ),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )

            Button(
                onClick = { send() },
                enabled = command.isNotBlank() && !sending,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAccent) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isAccent) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        )
    }
}
