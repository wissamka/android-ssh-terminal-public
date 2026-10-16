package dev.wissam.sshterm.core.ssh

import java.io.File
import java.security.KeyPair
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties

internal data class MinaTimeouts(
    val connectMs: Long = 15_000,
    val trustMs: Long = 120_000,
    val publicKeyAuthMs: Long = 210_000,
    val otherAuthMs: Long = 120_000,
)

internal object MinaBackend {
    private const val CONNECT_MS = 15_000L
    val hybrids = listOf(BuiltinDHFactories.mlkem768x25519, BuiltinDHFactories.sntrup761x25519,
        BuiltinDHFactories.sntrup761x25519_openssh)

    fun connect(conn: ResolvedConnection, knownHosts: File, prompter: HostKeyPrompter, timeouts: MinaTimeouts = MinaTimeouts()): SshEngine.Chain {
        // Resolve MINA's optional filesystem defaults to the supplied app-private trust directory.
        // Do not change global JCE providers or JVM system properties used by SSHJ/AndroidKeyStore.
        val privateDirectory = requireNotNull(knownHosts.absoluteFile.parentFile).toPath()
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { privateDirectory }
        org.apache.sshd.common.util.OsUtils.setCurrentWorkingDirectoryResolver { privateDirectory }
        if (hybrids.none { it.isSupported }) throw SshFatalException("MINA hybrid key exchange unavailable on this runtime")
        // Validate the whole chain before contacting any host. Never silently skip unsupported auth.
        fun preflight(c: ResolvedConnection) {
            c.jumpHost?.let(::preflight)
            val auth = c.auth as? SshAuthMethod.PublicKey ?: return
            val type = auth.keyProvider.type.toString()
            if (type !in setOf("ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-ed25519")) {
                throw SshFatalException("MINA does not support this authentication key: $type")
            }
            val names = minaSignatureFactories(c).map { it.name }
            val requested = if (type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256") else listOf(type)
            if (names.none { it in requested }) throw SshFatalException("No authorized MINA signing algorithm available for $type")
            val hardware = auth.keyProvider as? dev.wissam.sshterm.core.keys.KeystoreKeyProvider
            if (hardware?.signingProtection() == SigningProtection.KEYSTORE_PER_OPERATION &&
                type !in setOf("ssh-rsa", "ecdsa-sha2-nistp256")) {
                throw SshFatalException("MINA per-operation key type is not supported: $type")
            }
        }
        preflight(conn)
        val connections = mutableListOf<SshTransportConnection>()
        try {
            fun hop(c: ResolvedConnection): MinaConnection {
                val via = c.jumpHost?.let(::hop)
                val tunnel = via?.openJumpChannel(c.host, c.port, timeouts.connectMs)
                try {
                    return connectOne(c, knownHosts, prompter, tunnel, timeouts).also { connections.add(it) }
                } catch (e: Exception) { runCatching { tunnel?.close(true) }; throw e }
            }
            hop(conn)
            return SshEngine.Chain(connections)
        } catch (e: Exception) {
            connections.asReversed().forEach { runCatching { it.close() } }
            throw e
        }
    }

    private fun connectOne(c: ResolvedConnection, knownHosts: File, prompter: HostKeyPrompter, tunnel: org.apache.sshd.client.channel.ChannelDirectTcpip?, timeouts: MinaTimeouts): MinaConnection {
        // Match SSHJ's budget for fresh approval followed by protected material loading.
        val authWait = if (c.auth is SshAuthMethod.PublicKey) timeouts.publicKeyAuthMs else timeouts.otherAuthMs
        val client = SshClient.setUpDefaultClient()
        var session: ClientSession? = null
        val trust = TofuKnownHostsVerifier(knownHosts, prompter)
        val trustFailed = AtomicBoolean(false)
        val authDenied = AtomicBoolean(false)
        try {
            client.ioServiceFactoryFactory = tunnel?.let(::MinaJumpTransport) ?: Nio2ServiceFactoryFactory()
            client.hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            client.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            client.agentFactory = null
            client.userInteraction = UserInteraction.NONE
            client.signatureFactories = minaSignatureFactories(c) { authDenied.set(true) }
            client.keyExchangeFactories = hybrids.filter { it.isSupported }.map { ClientBuilder.DH2KEX.apply(it) }
            client.serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key ->
                try {
                    // Decode via SSH wire format so SSHJ sees its supported key representation.
                    // Bind trust to the original destination, NEVER to a local jump tunnel endpoint.
                    val encoded = ByteArrayBuffer().apply { putRawPublicKey(key) }.compactData
                    val converted = net.schmizz.sshj.common.Buffer.PlainBuffer(encoded).readPublicKey()
                    trust.verify(c.host, c.port, converted).also { if (!it) trustFailed.set(true) }
                } catch (e: Exception) { trustFailed.set(true); throw e }
            }
            // Initial KEX/trust has its own bounded wait; it must not consume signing time.
            CoreModuleProperties.AUTH_TIMEOUT.set(client, Duration.ZERO)
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(15))
            CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, 3)
            client.start()
            val active = client.connect(c.username, c.host, c.port)
                .verify(timeouts.connectMs).session
            session = active
            val trustDeadline = System.nanoTime() + timeouts.trustMs * 1_000_000
            awaitMinaTrust(active, trustDeadline)
            active.resetAuthTimeout()
            CoreModuleProperties.AUTH_TIMEOUT.set(active, Duration.ofMillis(authWait))
            when (val auth = c.auth) {
                is SshAuthMethod.Password -> {
                    active.userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
                    val password = String(auth.password)
                    active.addPasswordIdentity(password)
                    try { active.auth().verify(authWait) }
                    finally { active.removePasswordIdentity(password) }
                }
                is SshAuthMethod.PublicKey -> {
                    active.userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
                    val publicBytes = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(auth.keyProvider.public).compactData
                    val publicKey = ByteArrayBuffer(publicBytes).rawPublicKey
                    val pair = KeyPair(publicKey, auth.keyProvider.private)
                    active.addPublicKeyIdentity(pair)
                    try { active.auth().verify(authWait) }
                    finally { active.removePublicKeyIdentity(pair) }
                }
                is SshAuthMethod.KeyboardInteractive -> {
                    active.userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
                    val challenges = InteractiveChallenges("${c.username}@${c.host}:${c.port}", auth.respond)
                    active.userInteraction = object : UserInteraction {
                        override fun isInteractionAllowed(session: ClientSession?) = true
                        override fun getUpdatedPassword(session: ClientSession?, prompt: String?, lang: String?): String? = null
                        override fun interactive(session: ClientSession?, name: String?, instruction: String?, lang: String?,
                            prompts: Array<out String>, echo: BooleanArray): Array<String> {
                            try {
                                if (prompts.size != echo.size) throw SshFatalException("Malformed authentication challenge")
                                challenges.init(null, name, instruction)
                                return Array(prompts.size) { i ->
                                    val answer = challenges.getResponse(prompts[i], echo[i])
                                    try { String(answer) } finally { answer.fill('\u0000') }
                                }
                            } catch (e: Exception) {
                                authDenied.set(true)
                                session?.close(true)
                                throw SshFatalException("Interactive authentication cancelled or unavailable", e)
                            }
                        }
                    }
                    try { active.auth().verify(authWait) }
                    finally { active.userInteraction = UserInteraction.NONE }
                }
            }
            if (!active.isAuthenticated) throw SshFatalException("MINA authentication failed")
            val kex = active.getNegotiatedKexParameter(KexProposalOption.ALGORITHMS)
            if (hybrids.none { it.getName() == kex }) throw SshFatalException("MINA did not negotiate hybrid key exchange")
            return MinaConnection(client, active, kex, tunnel)
        } catch (e: Exception) {
            runCatching { session?.close(true)?.await(CONNECT_MS) }
            runCatching { client.stop() }
            if (e is InterruptedException) { Thread.currentThread().interrupt(); throw e }
            // Failed authentication/trust is terminal for the reconnect controller.
            val code = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<org.apache.sshd.common.SshException>().firstOrNull()?.disconnectCode
            if (e is SshFatalException || trustFailed.get() || authDenied.get() || code in setOf(
                    org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE,
                    org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
                    org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE)) {
                throw SshFatalException("MINA authentication, trust, or hybrid negotiation failed", e)
            }
            throw e
        }
    }
}

internal class MinaConnection(
    private val client: SshClient,
    private val session: ClientSession,
    override val negotiatedHybrid: String,
    private val tunnel: org.apache.sshd.client.channel.ChannelDirectTcpip?,
) : SshTransportConnection {
    private val closed = AtomicBoolean(false)
    private val remoteTargets = java.util.concurrent.ConcurrentHashMap<SshdSocketAddress, Int>()
    init {
        // A remote peer may connect only to destinations of explicitly registered -R rules.
        // Unsolicited direct-tcpip requests and server-side listeners remain denied.
        client.forwardingFilter = org.apache.sshd.server.forward.ForwardingFilter.asForwardingFilter(null, null, object : org.apache.sshd.server.forward.TcpForwardingFilter {
            override fun canListen(address: SshdSocketAddress?, session: org.apache.sshd.common.session.Session?) = false
            override fun canConnect(type: org.apache.sshd.server.forward.TcpForwardingFilter.Type?, address: SshdSocketAddress?,
                session: org.apache.sshd.common.session.Session?) =
                type == org.apache.sshd.server.forward.TcpForwardingFilter.Type.Forwarded && address != null && remoteTargets.containsKey(address)
        })
    }
    override val isAuthenticated get() = session.isAuthenticated && session.isOpen
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { session.close(true).await(15_000) }
        finally { try { client.stop() } finally { tunnel?.close(true) } }
    }
    internal fun openJumpChannel(host: String, port: Int, timeoutMs: Long): org.apache.sshd.client.channel.ChannelDirectTcpip {
        check(isAuthenticated)
        val channel = session.createDirectTcpipChannel(SshdSocketAddress("127.0.0.1", 0), SshdSocketAddress(host, port))
        try { channel.open().verify(timeoutMs); return channel }
        catch (e: Exception) { channel.close(true); throw e }
    }

    override fun openShell(term: String, columns: Int, rows: Int, width: Int, height: Int): SshShell {
        check(isAuthenticated)
        val channel = session.createShellChannel().apply {
            isUsePty = true
            ptyType = term; ptyColumns = columns; ptyLines = rows; ptyWidth = width; ptyHeight = height
            isRedirectErrorStream = true
        }
        try {
            channel.open().verify(15_000)
            return object : SshShell {
                override val inputStream get() = channel.invertedOut
                override val outputStream get() = channel.invertedIn
                override val exitStatus: Int? get() {
                    channel.waitFor(setOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED,
                        org.apache.sshd.client.channel.ClientChannelEvent.EXIT_STATUS,
                        org.apache.sshd.client.channel.ClientChannelEvent.EXIT_SIGNAL), 2000)
                    return channel.exitStatus
                }
                override val termination: SshTermination get() {
                    val status = exitStatus
                    return SshTermination.from(status, channel.exitSignal)
                }
                override fun changeWindowDimensions(columns: Int, rows: Int, width: Int, height: Int) =
                    channel.sendWindowChange(columns, rows, width, height)
                override fun close() { channel.close(true).await(15_000) }
            }
        } catch (e: Exception) { channel.close(true); throw e }
    }
    override fun startForward(spec: ForwardSpec, onClosed: (String?) -> Unit): SshForward {
        check(isAuthenticated)
        val local = spec.direction == ForwardDirection.LOCAL
        val bind = SshdSocketAddress(spec.bindHost, spec.bindPort)
        val target = SshdSocketAddress(spec.targetHost, spec.targetPort)
        if (!local) remoteTargets.merge(target, 1, Int::plus)
        val address = try {
            if (local) session.startLocalPortForwarding(bind, target) else session.startRemotePortForwarding(bind, target)
        } catch (e: Exception) {
            if (!local) remoteTargets.computeIfPresent(target) { _, count -> (count - 1).takeIf { it > 0 } }
            throw e
        }
        return object : SshForward {
            private val stopped = AtomicBoolean(false)
            override val boundPort get() = address.port
            override fun close() {
                if (stopped.compareAndSet(false, true)) {
                    try {
                        if (session.isOpen) {
                            if (local) session.stopLocalPortForwarding(address) else session.stopRemotePortForwarding(address)
                        }
                    } finally {
                        if (!local) remoteTargets.computeIfPresent(target) { _, count -> (count - 1).takeIf { it > 0 } }
                    }
                }
            }
        }
    }
}

/** Check the deadline after reading state as well, including a successful WAIT_AUTH observation. */
internal fun awaitMinaTrust(
    session: ClientSession,
    deadlineNanos: Long,
    now: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(10) },
) {
    while (true) {
        val ready = org.apache.sshd.client.session.ClientSession.ClientSessionEvent.WAIT_AUTH in session.sessionState
        if (!session.isOpen || now() - deadlineNanos >= 0) {
            throw SshFatalException("MINA key exchange or host verification failed or timed out")
        }
        if (ready) return
        // Unlike MINA waitFor(), this wait honors connector-thread cancellation.
        pause()
    }
}
