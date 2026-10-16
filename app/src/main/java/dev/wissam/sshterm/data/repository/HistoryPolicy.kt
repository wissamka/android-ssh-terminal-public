package dev.wissam.sshterm.data.repository

/** Sessions without a saved profile have no private scope; recording them requires opt-in. */
object HistoryPolicy {
    fun shouldRecord(profileId: Long?, enabled: Boolean, globalFallback: Boolean): Boolean =
        enabled && (profileId != null || globalFallback)

    fun commandToRecord(command: String): String? = command.trim().takeIf { it.isNotEmpty() }
}

/** Invalidates asynchronous suggestion results after input, scope, or privacy changes. */
class HistorySuggestionPolicy {
    private var generation = 0L
    fun invalidate(): Long = ++generation
    fun isCurrent(request: Long): Boolean = request == generation
}
