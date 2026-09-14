package com.devcode.terminal.core.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Singleton that owns every [TerminalSession] and tracks which one is active.
 *
 * Callers are responsible for calling [TerminalSession.start] after creating
 * a session and [TerminalSession.stop] before (or instead of) [closeSession].
 * [closeSession] removes the session from the registry; it does not stop the
 * underlying process — call [TerminalSession.stop] first if the process is alive.
 */
object TerminalManager {

    /** All open sessions, in creation order. */
    val sessions = MutableStateFlow<List<TerminalSession>>(emptyList())

    /** The [TerminalSession.id] of the currently-active session, or null. */
    val activeId = MutableStateFlow<String?>(null)

    /**
     * Create a new [TerminalSession], add it to the registry, and make it active.
     *
     * The session is not started automatically — call [TerminalSession.start]
     * after creation to launch the underlying persistent shell process.
     *
     * @param title Display name shown in the tab bar (defaults to "coder").
     * @return The newly-created [TerminalSession].
     */
    fun createSession(title: String = "coder"): TerminalSession {
        val session = TerminalSession(
            id = UUID.randomUUID().toString(),
            title = title
        )
        sessions.value = sessions.value + session
        activeId.value = session.id
        return session
    }

    /**
     * Remove the session with the given [id] from the registry.
     *
     * Does NOT stop the underlying process.  Call [TerminalSession.stop] first
     * if the session may still be running.
     *
     * If the closed session was active, the most-recently-created remaining
     * session becomes active (or null if none remain).
     */
    fun closeSession(id: String) {
        sessions.value = sessions.value.filter { it.id != id }
        if (activeId.value == id) {
            activeId.value = sessions.value.lastOrNull()?.id
        }
    }

    /** Make the session with the given [id] active. No-op if the id is unknown. */
    fun setActive(id: String) {
        activeId.value = id
    }

    /** Return the currently-active [TerminalSession], or null if none is active. */
    fun active(): TerminalSession? {
        val currentId = activeId.value ?: return null
        return sessions.value.firstOrNull { it.id == currentId }
    }
}
