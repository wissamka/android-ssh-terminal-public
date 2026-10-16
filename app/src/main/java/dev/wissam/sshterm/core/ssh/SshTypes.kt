package dev.wissam.sshterm.core.ssh

import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * A fully-resolved connection target: profile references (key refs, jump-host refs) have already
 * been resolved by the caller so the SSH layer stays decoupled from the persistence layer.
 */
data class ResolvedConnection(
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshAuthMethod,
    val termType: String = "xterm-256color",
    /** Bastion to tunnel through (ProxyJump equivalent); chains recursively. */
    val jumpHost: ResolvedConnection? = null,
    /** Port forwards to start once the session's shell channel is up. */
    val forwards: List<ForwardSpec> = emptyList(),
    /** Cancels all pending UI interactions when this session definitively ends. */
    val cancelPrompts: () -> Unit = {},
)

enum class ForwardDirection {
    /** -L: listen locally on the device, tunnel to target reachable from the server. */
    LOCAL,

    /** -R: listen on the server, tunnel back to target reachable from the device. */
    REMOTE,
}

/**
 * A resolved port-forwarding rule. For LOCAL, (bindHost,bindPort) is the on-device listener and
 * (targetHost,targetPort) is resolved from the server. For REMOTE, (bindHost,bindPort) is the
 * server-side listener and (targetHost,targetPort) is resolved from the device.
 */
data class ForwardSpec(
    val direction: ForwardDirection,
    val bindHost: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    /** Stable unique identity (the source rule id) — used to key status without label collisions. */
    val key: Long = 0,
) {
    val label: String
        get() = when (direction) {
            ForwardDirection.LOCAL -> "L $bindHost:$bindPort → $targetHost:$targetPort"
            ForwardDirection.REMOTE -> "R $bindHost:$bindPort → $targetHost:$targetPort"
        }
}

sealed interface SshAuthMethod {
    /** Held in memory only for the lifetime of the session (needed for reconnects); never persisted. */
    class Password(val password: CharArray) : SshAuthMethod
    class PublicKey(
        val keyProvider: KeyProvider,
        val signingAuthorizer: SigningAuthorizer = SigningAuthorizer.Deny,
    ) : SshAuthMethod
    class KeyboardInteractive(val respond: suspend (InteractiveChallenge) -> CharArray?) : SshAuthMethod
}

enum class SessionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

class HostKeyInfo(
    val host: String,
    val keyType: String,
    /** OpenSSH-style SHA256:base64 fingerprint. */
    val fingerprint: String,
)

/**
 * UI hook for trust-on-first-use host key decisions. Both calls block the connector thread
 * until the user decides; implementations bridge to a dialog.
 */
interface HostKeyPrompter {
    /** First contact with this host: return true to trust and persist the key. */
    suspend fun onUnknownHost(info: HostKeyInfo): Boolean

    /**
     * The host presented a key that CONTRADICTS a previously trusted one (possible MITM).
     * Return true only if the user explicitly accepts the new key for this connection.
     */
    suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String): Boolean
}

/** Terminal outcome of a connection attempt that should not be retried. */
class SshFatalException(message: String, cause: Throwable? = null) : Exception(message, cause)
