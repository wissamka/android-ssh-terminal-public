package dev.wissam.sshterm.core.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

enum class SshBackendChoice {
    SSHJ, MINA_PQ;
    companion object {
        fun parse(value: String): SshBackendChoice = when (value) {
            "sshj" -> SSHJ
            "mina-pq" -> MINA_PQ
            else -> throw IllegalArgumentException("Unknown SSH backend: $value")
        }
    }
}

/** Explicit remote termination must never be treated as a reconnectable transport failure. */
sealed interface SshTermination {
    val shouldReconnect: Boolean get() = this === TransportLost
    data class Exited(val code: Int) : SshTermination
    data class Signalled(val signal: String) : SshTermination
    data object TransportLost : SshTermination
    companion object {
        fun from(status: Int?, signal: String?): SshTermination = when {
            status != null -> Exited(status)
            signal != null -> Signalled(signal)
            else -> TransportLost
        }
    }
}

interface SshShell : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val termination: SshTermination get() = SshTermination.from(exitStatus, null)
    val exitStatus: Int?
    fun changeWindowDimensions(columns: Int, rows: Int, width: Int, height: Int)
}

interface SshForward : Closeable {
    val boundPort: Int
}

/** Authenticated transport; the terminal retains reconnect and resource ownership. */
interface SshTransportConnection : Closeable {
    val isAuthenticated: Boolean
    val negotiatedHybrid: String? get() = null
    fun openShell(term: String, columns: Int, rows: Int, width: Int, height: Int): SshShell
    fun startForward(spec: ForwardSpec, onClosed: (String?) -> Unit = {}): SshForward
}
