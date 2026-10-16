package dev.wissam.sshterm.core.ssh

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * Builds authenticated transports from a [ResolvedConnection], following the jump-host chain
 * recursively. Returns the full chain (target last) so callers can close every hop.
 */
@Singleton
class SshEngine(private val backend: SshBackendChoice) {
    @Inject constructor() : this(SshBackendChoice.parse(dev.wissam.sshterm.BuildConfig.SSH_BACKEND))

    class Chain(val clients: List<SshTransportConnection>) {
        val target: SshTransportConnection get() = clients.last()
        fun closeAll() {
            // Target first, bastion last.
            clients.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** Blocking; call from a connector thread. */
    @Throws(IOException::class)
    fun connect(conn: ResolvedConnection, knownHostsFile: File, prompter: HostKeyPrompter): Chain {
        if (backend == SshBackendChoice.MINA_PQ) return MinaBackend.connect(conn, knownHostsFile, prompter)
        val clients = mutableListOf<SSHClient>()
        try {
            connectHop(conn, knownHostsFile, prompter, clients)
            return Chain(clients.map(::SshjTransportConnection))
        } catch (t: Throwable) {
            clients.asReversed().forEach { runCatching { it.close() } }
            throw t
        }
    }

    private fun connectHop(
        conn: ResolvedConnection,
        knownHostsFile: File,
        prompter: HostKeyPrompter,
        clients: MutableList<SSHClient>,
    ): SSHClient {
        val via = conn.jumpHost?.let { connectHop(it, knownHostsFile, prompter, clients) }

        val config = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            (conn.auth as? SshAuthMethod.PublicKey)?.let { auth ->
                val protection = (auth.keyProvider as? dev.wissam.sshterm.core.keys.KeystoreKeyProvider)
                    ?.signingProtection() ?: SigningProtection.SOFTWARE_APP_GATE
                keyAlgorithms = authorizedAlgorithms(
                    keyAlgorithms,
                    SigningRequest(conn.host, conn.port, conn.username, "", protection),
                    auth.signingAuthorizer,
                )
            }
        }
        val client = SSHClient(config)
        clients.add(client)
        client.addHostKeyVerifier(TofuKnownHostsVerifier(knownHostsFile, prompter))
        client.connectTimeout = CONNECT_TIMEOUT_MS

        if (via != null) {
            client.connectVia(via.newDirectConnection(conn.host, conn.port))
        } else {
            client.connect(conn.host, conn.port)
        }

        try {
            authenticate(client, conn)
        } catch (e: UserAuthException) {
            throw SshFatalException("Authentication failed for ${conn.username}@${conn.host}: ${e.message}", e)
        }

        // Mobile networks silently kill idle TCP; aggressive keepalive detects it fast so the
        // session can reconnect instead of hanging.
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
        return client
    }

    private fun authenticate(client: SSHClient, conn: ResolvedConnection) {
        when (val auth = conn.auth) {
            is SshAuthMethod.KeyboardInteractive -> {
                val previousTimeout = client.timeout
                client.timeout = 120_000
                try {
                    client.auth(conn.username, object : AuthKeyboardInteractive(
                        InteractiveChallenges("${conn.username}@${conn.host}:${conn.port}", auth.respond),
                    ) {
                        override fun handle(cmd: net.schmizz.sshj.common.Message, packet: net.schmizz.sshj.common.SSHPacket) {
                            try { super.handle(cmd, packet) }
                            catch (e: SshFatalException) { throw UserAuthException("Interactive authentication cancelled or unavailable", e) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw UserAuthException("Interactive authentication cancelled", e) }
                        }
                    })
                } finally { client.timeout = previousTimeout }
            }
            is SshAuthMethod.PublicKey -> {
                // SSHJ waits on its auth result while its reader thread runs the suspend prompt.
                val previousTimeout = client.timeout
                client.timeout = 210_000
                try {
                    client.auth(conn.username, object : net.schmizz.sshj.userauth.method.AuthPublickey(auth.keyProvider) {
                        private var signingFailed = false
                        override fun putSig(packet: net.schmizz.sshj.common.SSHPacket): net.schmizz.sshj.common.SSHPacket =
                            try {
                                super.putSig(packet)
                            } catch (e: RuntimeException) {
                                // Includes denial and invalidated/locked Keystore operations. Do not
                                // retry other algorithms after a signing failure or leak a reader error.
                                signingFailed = true
                                throw UserAuthException("SSH signing failed", e)
                            }
                        override fun shouldRetry(): Boolean = !signingFailed && super.shouldRetry()
                    })
                } finally {
                    client.timeout = previousTimeout
                }
            }
            is SshAuthMethod.Password -> {
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray = auth.password.copyOf()
                    override fun shouldRetry(resource: Resource<*>?): Boolean = false
                }
                client.auth(conn.username, AuthPassword(finder))
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val KEEPALIVE_INTERVAL_SECONDS = 15
    }
}
