package dev.wissam.sshterm.core.ssh

/** Owns either backend's forward handles across terminal reconnect/close races. */
class PortForwardManager(
    private val client: SshTransportConnection,
    private val onStatus: (ForwardSpec, ForwardStatus, String?) -> Unit,
) {
    enum class ForwardStatus { ACTIVE, FAILED, CLOSED }
    private val lock = Any()
    private val handles = mutableListOf<SshForward>()
    private var closed = false

    fun start(specs: List<ForwardSpec>) {
        for (spec in specs) {
            synchronized(lock) { if (closed) return }
            try {
                val handle = client.startForward(spec) { error ->
                    synchronized(lock) { if (!closed) onStatus(spec, ForwardStatus.CLOSED, error) }
                }
                val owned = synchronized(lock) {
                    if (closed) false else {
                        handles.add(handle)
                        onStatus(spec, ForwardStatus.ACTIVE, null)
                        true
                    }
                }
                if (!owned) { runCatching { handle.close() }; return }
            } catch (e: Exception) {
                synchronized(lock) { if (!closed) onStatus(spec, ForwardStatus.FAILED, e.message) }
            }
        }
    }
    fun stop() {
        val release = synchronized(lock) {
            closed = true
            handles.toList().also { handles.clear() }
        }
        release.asReversed().forEach { runCatching { it.close() } }
    }
}
