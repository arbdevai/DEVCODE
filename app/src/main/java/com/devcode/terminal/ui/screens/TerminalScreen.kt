package com.devcode.terminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode.terminal.DevCodeApp
import com.devcode.terminal.core.terminal.TerminalManager
import com.devcode.terminal.core.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val sessions by TerminalManager.sessions.collectAsState()
    val activeId by TerminalManager.activeId.collectAsState()
    val fontSize by DevCodeApp.settings.fontSize.collectAsState(initial = 14f)
    val active = sessions.firstOrNull { it.id == activeId }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(
                "TERMINAL // SHELL",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val s = TerminalManager.createSession("Shell ${sessions.size + 1}")
                scope.launch { s.start() }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "New terminal session", tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (sessions.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    val running by session.isRunning.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = activeId == session.id,
                            onClick = { TerminalManager.setActive(session.id) },
                            label = { Text(session.title + if (running) " · live" else " · idle") },
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    session.stop()
                                    TerminalManager.closeSession(session.id)
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Close, "Close ${session.title}", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        if (active == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "DEVCODE WORKSTATION",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "No active terminal session. Launch a shell in Ubuntu chroot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val s = TerminalManager.createSession("Shell 1")
                            scope.launch { s.start() }
                        },
                    ) {
                        Text("OPEN SHELL SESSION")
                    }
                }
            }
        } else {
            key(active.id) {
                SessionConsole(active, fontSize, Modifier.weight(1f))
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(
                if (running) "STATUS: LIVE (chroot)" else "STATUS: IDLE",
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
            ) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (copied) "COPIED" else "COPY OUTPUT",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(lines.size) { index ->
                    Text(
                        text = if (output.isEmpty()) "$ coder@ubuntu:~$ " else lines[index],
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.35f).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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

        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Enter bash command...", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
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
            ) {
                Text("SEND", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
