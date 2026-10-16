package dev.wissam.sshterm.ui.terminal

/** Main-thread-owned, Android-free OSC 52 consent policy. No clipboard access occurs here. */
class RemoteClipboardPolicy {
    data class Request(val id: Long, val sessionId: String, val charCount: Int)
    private data class Pending(val request: Request, val text: String, val offeredAt: Long)
    private var pending: Pending? = null
    private var nextId = 0L

    /** Keep at most one 64K-character payload; ignore bursts rather than queueing prompts. */
    fun offer(sessionId: String, activeSessionId: String?, text: String, now: Long): Request? {
        if (sessionId != activeSessionId || text.length > MAX_CHARS) return null
        pending?.let {
            if (now - it.offeredAt < REQUEST_TTL_MS) return null
            pending = null
        }
        val request = Request(++nextId, sessionId, text.length)
        pending = Pending(request, text, now)
        return request
    }

    /** A stale action must neither copy a newer payload nor dismiss a newer request. */
    fun accept(requestId: Long, activeSessionId: String?, now: Long): String? {
        val current = pending ?: return null
        if (current.request.id != requestId) return null
        pending = null
        if (current.request.sessionId != activeSessionId ||
            now - current.offeredAt !in 0 until REQUEST_TTL_MS
        ) return null
        return current.text
    }

    fun dismiss(requestId: Long) {
        if (pending?.request?.id == requestId) pending = null
    }

    companion object {
        const val MAX_CHARS = 64 * 1024
        const val REQUEST_TTL_MS = 10_000L
    }
}
