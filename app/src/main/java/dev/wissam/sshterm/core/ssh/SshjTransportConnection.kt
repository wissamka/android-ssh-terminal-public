package dev.wissam.sshterm.core.ssh

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener

internal class SshjTransportConnection(private val client: SSHClient) : SshTransportConnection {
    override val isAuthenticated get() = client.isAuthenticated
    override fun close() = client.close()
    override fun openShell(term: String, columns: Int, rows: Int, width: Int, height: Int): SshShell {
        val session = client.startSession()
        try {
            session.allocatePTY(term, columns, rows, width, height, emptyMap())
            val shell = session.startShell()
            return object : SshShell {
                override val inputStream get() = shell.inputStream
                override val outputStream get() = shell.outputStream
                override val exitStatus: Int? get() {
                    session.join(2, java.util.concurrent.TimeUnit.SECONDS)
                    return (session as? net.schmizz.sshj.connection.channel.direct.SessionChannel)?.exitStatus
                }
                override val termination: SshTermination get() {
                    val status = exitStatus // Wait for channel requests before inspecting the signal.
                    val signal = (session as? net.schmizz.sshj.connection.channel.direct.SessionChannel)?.exitSignal
                    return SshTermination.from(status, signal?.name)
                }
                override fun changeWindowDimensions(columns: Int, rows: Int, width: Int, height: Int) =
                    shell.changeWindowDimensions(columns, rows, width, height)
                override fun close() = session.close()
            }
        } catch (e: Exception) { runCatching { session.close() }; throw e }
    }
    override fun startForward(spec: ForwardSpec, onClosed: (String?) -> Unit): SshForward {
        val stopped = AtomicBoolean(false)
        if (spec.direction == ForwardDirection.REMOTE) {
            val forwarder = client.remotePortForwarder
            val forward = forwarder.bind(RemotePortForwarder.Forward(spec.bindHost, spec.bindPort),
                SocketForwardingConnectListener(InetSocketAddress(InetAddress.getByName(spec.targetHost), spec.targetPort)))
            return object : SshForward {
                override val boundPort get() = forward.port
                override fun close() { if (stopped.compareAndSet(false, true) && client.isConnected) forwarder.cancel(forward) }
            }
        }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(spec.bindHost, spec.bindPort))
            val forwarder = client.newLocalPortForwarder(
                Parameters(spec.bindHost, socket.localPort, spec.targetHost, spec.targetPort), socket)
            val port = socket.localPort
            Thread({
                try { forwarder.listen() }
                catch (e: Exception) { if (!stopped.get()) onClosed(e.message) }
            }, "SshjForward[$port]").apply { isDaemon = true }.start()
            return object : SshForward {
                override val boundPort = port
                override fun close() {
                    if (stopped.compareAndSet(false, true)) {
                        try { forwarder.close() } finally { socket.close() }
                    }
                }
            }
        } catch (e: Exception) { runCatching { socket.close() }; throw e }
    }
}
