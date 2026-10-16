package dev.wissam.sshterm.core.ssh

/** Serializes ownership, never network I/O. Late connect results are closed after cancellation. */
internal class LifecycleResources {
    private var closed = false
    private val releases = mutableListOf<() -> Unit>()

    fun own(release: () -> Unit): Boolean {
        val accepted = synchronized(this) {
            if (closed) false else { releases.add(release); true }
        }
        if (!accepted) runCatching(release)
        return accepted
    }

    @Synchronized fun ifOpen(action: () -> Unit): Boolean {
        if (closed) return false
        action()
        return true
    }

    @Synchronized fun seal() { closed = true }

    fun releaseAll(permanently: Boolean = false) {
        val pending = synchronized(this) {
            if (permanently) closed = true
            releases.toList().asReversed().also { releases.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}
