package com.devcode.terminal.core.terminal

import com.devcode.terminal.core.chroot.ChrootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * A persistent terminal session backed by a real interactive bash process
 * running inside the Ubuntu chroot via [ChrootManager.openInteractiveSession].
 *
 * Lifecycle:
 *   1. Call [start] to launch the persistent shell (mounts + process group).
 *   2. Call [execute] to send a command line to the shell's stdin.
 *   3. Call [sendInput] to send raw input (e.g. Ctrl-C sequences or text without newline).
 *   4. Call [stop] to cleanly terminate the process group via ChrootManager and close all streams.
 *
 * [output] is an append-only transcript capped at [MAX_TRANSCRIPT] characters.
 * [isRunning] is true while the underlying process is alive.
 *
 * Output is streamed live from stdout and stderr via concurrent coroutine readers
 * on [Dispatchers.IO]; execute() writes to stdin and returns immediately.
 *
 * Shell state (cwd, env, prompt) persists naturally because all commands share
 * the one long-lived `bash -i` process; `cd` in one [execute] call affects later ones.
 *
 * PTY note: [ChrootManager.openInteractiveSession] currently returns pipes
 * (bare `su`/`bash`, no pty). Line-oriented and most interactive I/O works, but
 * full terminal emulation (job control, curses, password prompts needing a tty)
 * requires a future `ChrootManager.openPtySession()` that wraps the shell with
 * `/usr/bin/script -qefc '<inner>' /dev/null` inside the mounts. This class
 * deliberately does NOT fake pty output; when that API exists, [start] is the
 * only place that needs to switch over.
 */
class TerminalSession(
    val id: String,
    val title: String
) {
    /** Append-only transcript visible to the UI. Capped at [MAX_TRANSCRIPT] chars. */
    val output = MutableStateFlow("")

    /** True while the underlying shell process is alive. */
    val isRunning = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Volatile so reads from the main thread don't need synchronisation for liveness.
    @Volatile private var process: Process? = null
    @Volatile private var stdinWriter: BufferedWriter? = null

    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    // ---- startup ----------------------------------------------------------

    /**
     * Launch the persistent interactive shell.
     *
     * Delegates to [ChrootManager.openInteractiveSession] which:
     *   - ensures chroot filesystems are mounted (atomically, under a mutex),
     *   - starts `su -c "... chroot ... /bin/su - coder -c '... exec setsid /bin/bash -i'"`
     *   - registers the process in the shared session registry.
     *
     * The session owns the app-side shell process's stdin/stdout/stderr pipes
     * (this is a real backend — no one-shot RootManager.runAsRoot per command,
     * no fake output). Output readers drain stdout and stderr concurrently so
     * neither pipe buffer can fill and deadlock the shell.
     *
     * When ChrootManager gains a PTY variant (e.g. `openPtySession()` using
     * `/usr/bin/script -qefc '<inner>' /dev/null`), switch the call here.
     *
     * Returns true on success.  On failure (root unavailable, chroot not installed)
     * returns false and appends a system error line to [output]. Safe to retry:
     * a second call after a failure, or while already running, is handled.
     */
    suspend fun start(): Boolean {
        // Already live — nothing to do (idempotent for repeated start calls).
        val live = process
        if (live != null && live.isAlive && isRunning.value) return true
        // Stale handle from a previous run — clean up before relaunching.
        if (live != null) {
            stop()
        }

        val proc = withContext(Dispatchers.IO) {
            ChrootManager.openInteractiveSession(id)
        }

        if (proc == null) {
            appendSystem("[error] Failed to open chroot session — root unavailable or Ubuntu not installed.\n")
            return false
        }

        process = proc
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        isRunning.value = true

        // Launch concurrent chunk readers for stdout and stderr so large/binary
        // output cannot stall one stream while the other fills its pipe buffer.
        stdoutJob = scope.launch {
            try {
                val buf = CharArray(8192)
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val n = reader.read(buf) ?: break
                        if (n > 0) appendOutput(String(buf, 0, n))
                    }
                }
            } catch (_: Exception) {
                // Stream closed during stop(); exit watcher reports below.
            } finally {
                // Process has exited or closed its stream.
                onProcessExited()
            }
        }

        stderrJob = scope.launch {
            try {
                val buf = CharArray(8192)
                proc.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val n = reader.read(buf) ?: break
                        if (n > 0) appendOutput(String(buf, 0, n))
                    }
                }
            } catch (_: Exception) {
                // Stream closed during stop(); stdout watcher owns exit reporting.
            }
        }

        return true
    }

    // ---- command execution ------------------------------------------------

    /**
     * Write [cmd] followed by a newline to the shell's stdin.
     *
     * Appends a "$ cmd" prompt line to [output] for readability, then sends the
     * raw command to the persistent shell — state (cwd, env, prompt) carries over
     * between calls. Returns immediately; the shell's response appears asynchronously
     * via the stdout/stderr reader jobs.
     *
     * Dropped (with a system line, not a fake result) if the session has not been
     * started or has already stopped.
     */
    suspend fun execute(cmd: String) {
        val writer = stdinWriter
        val proc = process
        if (!isRunning.value || writer == null || proc == null || !proc.isAlive) {
            appendSystem("[error] execute dropped: session not running — call start() first.\n")
            return
        }
        appendOutput("$ $cmd\n")
        withContext(Dispatchers.IO) {
            try {
                // Synchronise on the writer so execute() and sendInput() cannot
                // interleave partial writes from concurrent callers.
                synchronized(writer) {
                    writer.write("$cmd\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                appendSystem("[error] stdin write failed: ${e.message}\n")
            }
        }
    }

    // ---- raw input --------------------------------------------------------

    /**
     * Send [text] verbatim to the shell's stdin without appending a newline or
     * a prompt line in the transcript.
     *
     * Use this for:
     *   - Control sequences (e.g. "" for Ctrl-C, "" for Ctrl-D / EOF)
     *   - Passwords / interactive prompts where echoing to the transcript is undesirable
     *   - Partial lines that will be completed by a subsequent [sendInput] call
     *
     * Dropped if the session has not been started or has already stopped.
     */
    suspend fun sendInput(text: String) {
        val writer = stdinWriter
        val proc = process
        if (!isRunning.value || writer == null || proc == null || !proc.isAlive) return
        withContext(Dispatchers.IO) {
            try {
                synchronized(writer) {
                    writer.write(text)
                    writer.flush()
                }
            } catch (e: Exception) {
                appendSystem("[error] sendInput failed: ${e.message}\n")
            }
        }
    }

    // ---- stop -------------------------------------------------------------

    /**
     * Terminate the session.
     *
     * 1. Close stdin so the shell sees EOF.
     * 2. Delegate to [ChrootManager.stopSession] which kills the real process group
     *    (tracked via the setsid marker placed at launch — NOT pkill on the `su` wrapper).
     * 3. Cancel stream-reader coroutines and release the process handle.
     * 4. Call [ChrootManager.releaseSession] for registry cleanup.
     */
    suspend fun stop() {
        if (process == null) return

        // Close stdin first so the shell gets EOF and can flush.
        withContext(Dispatchers.IO) {
            try { stdinWriter?.close() } catch (_: Exception) {}
        }

        // Kill the real process group via ChrootManager (setsid-based group kill).
        ChrootManager.stopSession(id)

        // Cancel our output reader coroutines.
        stdoutJob?.cancel()
        stderrJob?.cancel()

        // Best-effort force-destroy in case ChrootManager couldn't reach the group.
        withContext(Dispatchers.IO) {
            try { process?.destroyForcibly() } catch (_: Exception) {}
        }

        process = null
        stdinWriter = null
        isRunning.value = false

        // Release from the shared session registry.
        ChrootManager.releaseSession(id)

        appendSystem("[session ended]\n")
    }

    // ---- system messages --------------------------------------------------

    /** Clear all transcript text from the console view. */
    fun clearTranscript() {
        output.value = ""
    }

    /** Append an informational (non-command) line to the transcript. */
    fun appendSystem(text: String) {
        appendOutput(text)
    }

    // ---- internal ---------------------------------------------------------

    private fun onProcessExited() {
        if (isRunning.value) {
            isRunning.value = false
            appendSystem("[process exited]\n")
            scope.launch { ChrootManager.releaseSession(id) }
        }
    }

    private fun appendOutput(text: String) {
        val current = output.value + text
        output.value = if (current.length > MAX_TRANSCRIPT) {
            current.substring(current.length - MAX_TRANSCRIPT)
        } else {
            current
        }
    }

    companion object {
        /** Maximum transcript size in characters; older output is dropped from the head. */
        const val MAX_TRANSCRIPT = 200_000

        /**
         * Shell-escape a string for safe inclusion in a single-quoted shell argument.
         * The result is always wrapped in single quotes with inner single quotes
         * escaped via the standard `'\''` idiom.
         */
        fun esc(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
