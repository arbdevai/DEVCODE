package com.devcode.terminal.core.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Result of executing a shell command.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Root management singleton. All root operations go via `su -c`.
 *
 * Safety invariants:
 * - Never crashes when root is unavailable; all failure paths return false / exitCode=-1.
 * - [runAsRoot] drains stdout and stderr concurrently on separate threads to avoid
 *   blocking the pipe buffer and deadlocking [Process.waitFor].
 * - [Process.waitFor] is always called with a finite timeout; a timed-out process is
 *   forcibly destroyed before returning.
 * - [sh] follows the same drain + finite-wait contract for non-root commands.
 */
object RootManager {

    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val DRAIN_JOIN_MS = 2_000L
    private const val MAX_OUTPUT_CHARS = 256_000

    // ---- public suspend API -----------------------------------------------

    /**
     * Returns true if `su -c id` succeeds and reports uid=0 within [DEFAULT_TIMEOUT_MS].
     */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                val r = runAsRoot("id", DEFAULT_TIMEOUT_MS)
                r.isSuccess && r.stdout.contains("uid=0")
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Attempts to obtain root by running `su -c echo ok`.
     * Returns true only when the shell acknowledges with "ok".
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                val r = runAsRoot("echo ok", DEFAULT_TIMEOUT_MS)
                r.isSuccess && r.stdout.trim() == "ok"
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Async wrapper for [runAsRoot] with configurable timeout.
     */
    suspend fun runAsRootAsync(
        cmd: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) {
                runAsRoot(cmd, timeoutMs)
            } ?: ShellResult(exitCode = -1, stdout = "", stderr = "Timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "exec failed")
        }
    }

    /**
     * Async wrapper for [sh] with configurable timeout.
     */
    suspend fun shAsync(
        cmd: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) {
                sh(cmd, timeoutMs)
            } ?: ShellResult(exitCode = -1, stdout = "", stderr = "Timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "exec failed")
        }
    }

    // ---- blocking helpers (call only from Dispatchers.IO) -----------------

    /**
     * Executes [cmd] via `su -c <cmd>`.
     *
     * stdout and stderr are drained concurrently before [waitFor] so the OS
     * pipe buffers never fill and deadlock the call.
     *
     * Never throws except [CancellationException]; all other failures
     * return exitCode=-1.
     */
    fun runAsRoot(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        execWithDrain(timeoutMs, "su", "-c", cmd)

    /**
     * Executes [cmd] via `sh -c <cmd>` without root.
     *
     * Same concurrent drain + finite-wait contract as [runAsRoot].
     *
     * Never throws except [CancellationException]; all other failures
     * return exitCode=-1.
     */
    fun sh(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        execWithDrain(timeoutMs, "sh", "-c", cmd)

    // ---- internal implementation ------------------------------------------

    /**
     * Builds a process from [args], drains stdout and stderr concurrently on
     * daemon threads, then waits up to [timeoutMs] for exit.
     * A process that exceeds the timeout is forcibly destroyed.
     * Streams are always closed. Never throws except [CancellationException].
     */
    private fun execWithDrain(timeoutMs: Long, vararg args: String): ShellResult {
        val timeout = if (timeoutMs <= 0) DEFAULT_TIMEOUT_MS else timeoutMs
        var process: Process? = null
        try {
            process = ProcessBuilder(*args)
                .redirectErrorStream(false)
                .start()
            // We never write to the child; close stdin early so it cannot
            // block waiting for input.
            try {
                process.outputStream.close()
            } catch (_: Exception) {}

            val p = process
            // Drain both streams concurrently to prevent pipe-buffer deadlock.
            // Containers (single-element arrays) hold results across threads;
            // Thread.join() establishes happens-before visibility.
            val stdoutBox = arrayOf("")
            val stderrBox = arrayOf("")

            val stdoutThread = drainThread(p.inputStream) { stdoutBox[0] = it }
            val stderrThread = drainThread(p.errorStream) { stderrBox[0] = it }

            stdoutThread.start()
            stderrThread.start()

            // Never wait indefinitely.
            val finished = try {
                p.waitFor(timeout, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

            if (!finished) {
                p.destroyForcibly()
            }

            // Closing the streams unblocks readers stuck in read() after kill.
            try {
                p.inputStream.close()
            } catch (_: Exception) {}
            try {
                p.errorStream.close()
            } catch (_: Exception) {}

            stdoutThread.join(DRAIN_JOIN_MS)
            stderrThread.join(DRAIN_JOIN_MS)

            val exitCode = if (finished) {
                try {
                    p.exitValue()
                } catch (_: IllegalThreadStateException) {
                    p.destroyForcibly()
                    -1
                }
            } else {
                -1
            }
            val stderr = if (!finished && stderrBox[0].isBlank()) {
                "Timed out after ${timeout}ms"
            } else {
                stderrBox[0]
            }
            return ShellResult(
                exitCode = exitCode,
                stdout = stdoutBox[0].trim(),
                stderr = stderr.trim()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                process?.destroyForcibly()
            } catch (_: Exception) {}
            return ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "exec failed")
        } catch (e: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {}
            return ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "exec failed")
        } finally {
            if (process != null) {
                try {
                    process.inputStream.close()
                } catch (_: Throwable) {}
                try {
                    process.errorStream.close()
                } catch (_: Throwable) {}
                try {
                    process.outputStream.close()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun drainThread(stream: InputStream, onDone: (String) -> Unit): Thread =
        Thread({
            try {
                onDone(readBounded(stream))
            } catch (_: Exception) {
                try {
                    onDone("")
                } catch (_: Exception) {}
            }
        }, "shell-drain").also { it.isDaemon = true }

    /**
     * Reads [stream] fully (to keep the pipe drained) but keeps at most
     * [MAX_OUTPUT_CHARS] characters to bound memory.
     */
    private fun readBounded(stream: InputStream): String {
        var reader: BufferedReader? = null
        try {
            reader = stream.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(8_192)
            while (true) {
                val n = try {
                    reader.read(buf)
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                val remaining = MAX_OUTPUT_CHARS - sb.length
                if (remaining > 0) {
                    sb.append(buf, 0, minOf(n, remaining))
                }
                // Beyond the cap: keep reading (drain) but discard.
            }
            return sb.toString()
        } catch (_: Exception) {
            return ""
        }
    }
}
