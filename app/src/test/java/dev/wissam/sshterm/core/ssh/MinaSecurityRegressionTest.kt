package dev.wissam.sshterm.core.ssh

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.SshConstants
import org.apache.sshd.server.SshServer
import org.junit.Assert.*
import org.junit.Test

/** Disposable loopback peers exercise the production transport, with shortened timing budgets. */
class MinaSecurityRegressionTest {
    private fun pair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun server() = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        val key = pair()
        keyPairProvider = org.apache.sshd.common.keyprovider.KeyPairProvider { listOf(key) }
        passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == "temporary" }
        start()
    }
    private val accept = object : HostKeyPrompter {
        override suspend fun onUnknownHost(info: HostKeyInfo) = true
        override suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String) = false
    }
    private fun conn(server: SshServer, jump: ResolvedConnection? = null) = ResolvedConnection(
        "127.0.0.1", server.port, "test", SshAuthMethod.Password("temporary".toCharArray()), jumpHost = jump)
    private fun session(connection: SshTransportConnection): ClientSession =
        MinaConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.get(connection) as ClientSession

    @Test(timeout = 30000) fun nestedJumpUsesOnlyInternalChannelsAndClosesEveryHop() {
        val known = Files.createTempFile("mina-jump", ".known_hosts").toFile()
        try { server().use { first -> server().use { second -> server().use { target ->
            listOf(first, second).forEach { it.forwardingFilter = org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE }
            val chain = MinaBackend.connect(conn(target, conn(second, conn(first))), known, accept)
            val sessions = chain.clients.map(::session)
            try {
                assertEquals(3, sessions.size)
                assertTrue(chain.target.isAuthenticated)
                // The old loopback bridge registered an acceptor on each bastion's forwarder.
                sessions.forEach { session ->
                    val forwarder = session.getService(org.apache.sshd.client.session.ClientConnectionService::class.java).forwarder
                    for (name in listOf("localAcceptor", "dynamicAcceptor")) {
                        val acceptor = org.apache.sshd.common.forward.DefaultForwarder::class.java
                            .getDeclaredField(name).apply { isAccessible = true }.get(forwarder)
                        assertNull("Jump must never allocate a forwarding acceptor", acceptor)
                    }
                }
                assertTrue(known.readText().contains("[127.0.0.1]:${target.port}"))
            } finally { chain.closeAll(); chain.closeAll() }
            assertTrue(sessions.all { it.isClosed })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (listOf(first, second, target).any { it.activeSessions.isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(listOf(first, second, target).all { it.activeSessions.isEmpty() })
        } } } } finally { known.delete() }
    }

    @Test(timeout = 30000) fun hostApprovalDoesNotSpendPublicKeySigningBudget() {
        val known = Files.createTempFile("mina-budget", ".known_hosts").toFile()
        try { server().use { server ->
            val userKey = pair()
            server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ -> key.encoded.contentEquals(userKey.public.encoded) }
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            val prompter = object : HostKeyPrompter {
                override suspend fun onUnknownHost(info: HostKeyInfo): Boolean {
                    events.add("trust"); delay(3000); events.add("trusted"); return true
                }
                override suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String) = false
            }
            val provider = object : KeyProvider {
                override fun getPublic() = userKey.public
                override fun getPrivate() = userKey.private
                override fun getType() = KeyType.RSA
            }
            val auth = SshAuthMethod.PublicKey(provider, SigningAuthorizer {
                events.add("sign"); delay(1200); true
            })
            val connection = conn(server).copy(auth = auth)
            // Trust alone exceeds this auth budget; signing still has its own full interval.
            val chain = MinaBackend.connect(connection, known, prompter,
                MinaTimeouts(trustMs = 6000, publicKeyAuthMs = 2500))
            try { assertTrue(chain.target.isAuthenticated) } finally { chain.closeAll() }
            assertEquals(listOf("trust", "trusted", "sign"), events)
            assertEquals(210_000L, MinaTimeouts().publicKeyAuthMs)
        } } finally { known.delete() }
    }

    @Test(timeout = 30000) fun signalWithoutExitStatusIsTerminal() {
        val known = Files.createTempFile("mina-signal", ".known_hosts").toFile()
        try { server().use { server ->
            server.shellFactory = org.apache.sshd.server.shell.ShellFactory {
                object : org.apache.sshd.server.command.Command {
                    override fun setInputStream(value: java.io.InputStream) {}
                    override fun setOutputStream(value: java.io.OutputStream) {}
                    override fun setErrorStream(value: java.io.OutputStream) {}
                    override fun setExitCallback(value: org.apache.sshd.server.ExitCallback) {}
                    override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) {}
                    override fun start(channel: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) {
                        val packet = channel.session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST)
                        packet.putUInt(channel.recipient)
                        packet.putString("exit-signal"); packet.putBoolean(false)
                        packet.putString("TERM"); packet.putBoolean(false)
                        packet.putString(""); packet.putString("")
                        channel.writePacket(packet).addListener { channel.close(false) }
                    }
                }
            }
            val chain = MinaBackend.connect(conn(server), known, accept)
            try { chain.target.openShell("xterm", 80, 24, 800, 480).use { shell ->
                while (shell.inputStream.read() != -1) { }
                assertNull(shell.exitStatus)
                assertEquals(SshTermination.Signalled("TERM"), shell.termination)
                assertFalse(shell.termination.shouldReconnect)
            } } finally { chain.closeAll() }
        } } finally { known.delete() }
    }

    @Test fun onlyMissingRemoteTerminationReconnects() {
        assertTrue(SshTermination.from(null, null).shouldReconnect)
        assertFalse(SshTermination.from(0, null).shouldReconnect)
        assertFalse(SshTermination.from(1, null).shouldReconnect)
        assertFalse(SshTermination.from(null, "KILL").shouldReconnect)
        assertFalse(SshTermination.from(137, "KILL").shouldReconnect)
    }

    private class TestJumpChannel : org.apache.sshd.client.channel.ChannelDirectTcpip(
        org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 0),
        org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 22),
    ) {
        val reading = java.util.concurrent.CountDownLatch(1)
        private val stopped = java.util.concurrent.CountDownLatch(1)
        override fun getInvertedOut(): java.io.InputStream = object : java.io.InputStream() {
            override fun read(): Int { reading.countDown(); stopped.await(); return -1 }
        }
        override fun preClose() { stopped.countDown() }
        private val closer = object : org.apache.sshd.common.util.closeable.AbstractCloseable() {}
        override fun getInnerCloseable(): org.apache.sshd.common.Closeable = closer
    }
    private open class TestJumpHandler : org.apache.sshd.common.io.IoHandler {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        override fun sessionCreated(session: org.apache.sshd.common.io.IoSession) {
            session.setAttribute("attached", true); created.incrementAndGet()
        }
        override fun sessionClosed(session: org.apache.sshd.common.io.IoSession) {
            check(session.getAttribute("attached") == true) { "Missing attached session" }
            closed.incrementAndGet()
        }
        override fun exceptionCaught(session: org.apache.sshd.common.io.IoSession, cause: Throwable) {}
        override fun messageReceived(session: org.apache.sshd.common.io.IoSession, message: org.apache.sshd.common.util.Readable) {}
    }

    @Test(timeout = 10000) fun cancellationBeforeAttachmentCompletesWithoutCloseCallback() {
        val channel = TestJumpChannel()
        val handler = TestJumpHandler()
        val published = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val connector = JumpConnector(channel, handler) {
            published.countDown(); check(resume.await(3, TimeUnit.SECONDS))
        }
        val result = connector.connect(java.net.InetSocketAddress("127.0.0.1", 22), null, null)
        try {
            assertTrue(published.await(3, TimeUnit.SECONDS))
            assertEquals(1, connector.managedSessions.size)
            val io = connector.managedSessions.values.single()
            result.cancel()
            assertTrue(connector.close(true).await(1000))
            assertTrue(io.close(true).await(1000))
            assertTrue(channel.isClosed)
            assertEquals(0, handler.closed.get())
        } finally { resume.countDown(); connector.close(true) }
        assertTrue(result.await(1000))
        assertEquals(0, handler.created.get())
    }

    @Test(timeout = 10000) fun cancellationDuringAttachmentWaitsForAttachedSession() {
        val channel = TestJumpChannel()
        val creating = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val handler = object : TestJumpHandler() {
            override fun sessionCreated(session: org.apache.sshd.common.io.IoSession) {
                creating.countDown(); check(resume.await(3, TimeUnit.SECONDS))
                super.sessionCreated(session)
            }
        }
        val connector = JumpConnector(channel, handler)
        val result = connector.connect(java.net.InetSocketAddress("127.0.0.1", 22), null, null)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(creating.await(3, TimeUnit.SECONDS))
            val closing = executor.submit<Boolean> { connector.close(true).await(1000) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!channel.isClosed && System.nanoTime() < deadline) Thread.yield()
            assertTrue(channel.isClosed)
            assertEquals(0, handler.closed.get())
            resume.countDown()
            assertTrue(closing.get(3, TimeUnit.SECONDS))
            assertTrue(result.await(1000))
            assertEquals(1, handler.created.get())
            assertEquals(1, handler.closed.get())
        } finally { resume.countDown(); connector.close(true); executor.shutdownNow() }
    }

    @Test(timeout = 10000) fun throwingCloseCallbackStillCompletesConnectorAndSessionFutures() {
        val channel = TestJumpChannel()
        val handler = object : TestJumpHandler() {
            override fun sessionClosed(session: org.apache.sshd.common.io.IoSession) {
                super.sessionClosed(session)
                throw IllegalStateException("Injected close failure")
            }
        }
        val connector = JumpConnector(channel, handler)
        val result = connector.connect(java.net.InetSocketAddress("127.0.0.1", 22), null, null)
        assertTrue(result.await(3000))
        val io = result.session
        assertTrue(channel.reading.await(3, TimeUnit.SECONDS))
        // Callback exceptions must not strand either future in the closing state.
        runCatching { connector.close(true) }
        assertTrue(connector.close(true).await(1000))
        assertTrue(io.close(true).await(1000))
        assertTrue(channel.isClosed)
        assertEquals(1, handler.closed.get())
        assertTrue(connector.managedSessions.isEmpty())
    }

    @Test(timeout = 10000) fun reentrantCloseDuringCreationDefersCallbackUntilAttachment() {
        val channel = TestJumpChannel()
        val captured = java.util.concurrent.atomic.AtomicReference<org.apache.sshd.common.io.IoSession>()
        val handler = object : TestJumpHandler() {
            override fun sessionCreated(session: org.apache.sshd.common.io.IoSession) {
                captured.set(session)
                session.close(true)
                assertEquals(0, closed.get())
                super.sessionCreated(session)
            }
        }
        val connector = JumpConnector(channel, handler)
        val result = connector.connect(java.net.InetSocketAddress("127.0.0.1", 22), null, null)
        try {
            assertTrue(result.await(3000))
            assertFalse(result.isConnected)
            assertNotNull(result.exception)
            assertEquals(1, handler.created.get())
            assertEquals(1, handler.closed.get())
            assertTrue(captured.get().close(true).await(1000))
        } finally { assertTrue(connector.close(true).await(1000)) }
    }

    @Test fun waitAuthObservedAtOrAfterDeadlineIsRejected() {
        for (late in listOf(100L, 101L)) {
            var clock = 99L
            val session = java.lang.reflect.Proxy.newProxyInstance(ClientSession::class.java.classLoader,
                arrayOf(ClientSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getSessionState" -> {
                        clock = late // The successful state observation itself crosses the deadline.
                        setOf(ClientSession.ClientSessionEvent.WAIT_AUTH)
                    }
                    "isOpen" -> true
                    else -> error("Unexpected call: ${method.name}")
                }
            } as ClientSession
            assertThrows(SshFatalException::class.java) {
                awaitMinaTrust(session, 100L, now = { clock }, pause = { fail("Already ready") })
            }
        }
    }

}
