package dev.wissam.sshterm.core.ssh

import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.*
import org.junit.Test

/** Real loopback SSH handshakes. No app, phone, user key, or external server is accessed. */
abstract class MinaProtocolContract {
    protected open fun newTrustFile(): java.io.File = Files.createTempFile("sshterm-protocol", ".known_hosts").toFile()
    private fun pair(algorithm: String = "RSA"): KeyPair = KeyPairGenerator.getInstance(algorithm).apply {
        if (algorithm == "RSA") initialize(2048)
        if (algorithm == "EC") initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun provider(pair: KeyPair) = object : KeyProvider {
        override fun getPrivate() = pair.private
        override fun getPublic() = pair.public
        override fun getType() = KeyType.fromKey(pair.public)
    }
    private fun server(key: KeyPair): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        keyPairProvider = org.apache.sshd.common.keyprovider.KeyPairProvider { listOf(key) }
        passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ -> user == "test" && password == "temporary" }
        start()
    }
    private val refuseNew = object : HostKeyPrompter {
        override suspend fun onUnknownHost(info: HostKeyInfo) = false
        override suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String) = false
    }
    private fun trust(vararg servers: Pair<SshServer, KeyPair>) = newTrustFile().apply {
        writeText(servers.joinToString("\n", postfix = "\n") { (s, key) ->
            "[127.0.0.1]:${s.port} ${org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key.public)}"
        })
    }
    private fun connection(server: SshServer, auth: SshAuthMethod, jump: ResolvedConnection? = null) =
        ResolvedConnection("127.0.0.1", server.port, "test", auth, jumpHost = jump)

    @Test(timeout = 30000) fun rsaEcAndEd25519SignaturesAuthenticateAndReconnectRequiresFreshAuthorization() {
        for (algorithm in listOf("RSA", "EC", "Ed25519")) {
            val hostKey = pair()
            val userKey = if (algorithm == "Ed25519") {
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
            } else pair(algorithm)
            server(hostKey).use { server ->
                server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, publicKey, _ -> publicKey.encoded.contentEquals(userKey.public.encoded) }
                val known = trust(server to hostKey)
                try {
                    val requests = CopyOnWriteArrayList<SigningRequest>()
                    val auth = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer { requests.add(it); true })
                    repeat(2) {
                        val chain = SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, auth), known, refuseNew)
                        assertTrue(chain.target.isAuthenticated); assertNotNull(chain.target.negotiatedHybrid); chain.closeAll()
                    }
                    assertEquals(2, requests.size)
                    assertTrue(requests.all { it.port == server.port && it.username == "test" })
                    val denied = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer.Deny)
                    assertThrows(SshFatalException::class.java) { SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, denied), known, refuseNew) }
                } finally { known.delete() }
            }
        }
    }

    @Test(timeout = 30000) fun changedHostKeyFailsBeforeAnyPrivateSignature() {
        val hostKey = pair(); val userKey = pair()
        server(hostKey).use { server ->
            val known = trust(server to pair())
            try {
                var signatures = 0
                val auth = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer { signatures++; true })
                assertThrows(Exception::class.java) { SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, auth), known, refuseNew) }
                assertEquals(0, signatures)
            } finally { known.delete() }
        }
    }

    @Test(timeout = 30000) fun interactiveServerReceivesDistinctEchoAndOtpReplies() {
        val key = pair()
        server(key).use { server ->
            server.keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(session: ServerSession, username: String, lang: String, subMethods: String) =
                    org.apache.sshd.server.auth.keyboard.InteractiveChallenge().apply {
                        interactionName = "Two factors"; interactionInstruction = "Type user and OTP"; languageTag = ""
                        addPrompt("User", true); addPrompt("One-time token", false)
                    }
                override fun authenticate(session: ServerSession, username: String, responses: MutableList<String>) =
                    username == "test" && responses == listOf("test", "123456")
            }
            val known = trust(server to key)
            try {
                val observed = CopyOnWriteArrayList<InteractiveChallenge>()
                val auth = SshAuthMethod.KeyboardInteractive { c -> observed.add(c); (if (c.echo) "test" else "123456").toCharArray() }
                val chain = SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, auth), known, refuseNew)
                assertTrue(chain.target.isAuthenticated); assertNotNull(chain.target.negotiatedHybrid); chain.closeAll()
                assertEquals(listOf(true, false), observed.map { it.echo })
                assertEquals("Type user and OTP", observed.last().instructions)
            } finally { known.delete() }
        }
    }

    @Test(timeout = 30000) fun jumpHostAndTargetEachRequireTheirOwnSignature() {
        val bastionKey = pair(); val targetKey = pair(); val userKey = pair()
        server(bastionKey).use { bastion -> server(targetKey).use { target ->
            bastion.forwardingFilter = AcceptAllForwardingFilter.INSTANCE // disposable server permits loopback ProxyJump
            listOf(bastion, target).forEach { it.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ -> key.encoded.contentEquals(userKey.public.encoded) } }
            val known = trust(bastion to bastionKey, target to targetKey)
            try {
                val ports = CopyOnWriteArrayList<Int>()
                val auth = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer { ports.add(it.port); true })
                val chain = SshEngine(SshBackendChoice.MINA_PQ).connect(connection(target, auth, connection(bastion, auth)), known, refuseNew)
                assertEquals(2, chain.clients.size); assertTrue(chain.target.isAuthenticated)
                chain.closeAll()
                assertEquals(listOf(bastion.port, target.port), ports)
            } finally { known.delete() }
        } }
    }

    @Test(timeout = 30000) fun bothBackendsSupportPtyResizeAndCleanExit() {
        for (backend in SshBackendChoice.entries) {
            val key = pair()
            server(key).use { server ->
                val observed = java.util.concurrent.atomic.AtomicReference<org.apache.sshd.server.Environment>()
                val resized = java.util.concurrent.CountDownLatch(1)
                server.shellFactory = org.apache.sshd.server.shell.ShellFactory {
                    object : org.apache.sshd.server.command.Command {
                        lateinit var input: java.io.InputStream
                        lateinit var output: java.io.OutputStream
                        lateinit var callback: org.apache.sshd.server.ExitCallback
                        override fun setInputStream(value: java.io.InputStream) { input = value }
                        override fun setOutputStream(value: java.io.OutputStream) { output = value }
                        override fun setErrorStream(value: java.io.OutputStream) {}
                        override fun setExitCallback(value: org.apache.sshd.server.ExitCallback) { callback = value }
                        override fun start(channel: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) {
                            observed.set(env)
                            env.addSignalListener(org.apache.sshd.server.SignalListener { _, _ -> resized.countDown() }, org.apache.sshd.server.Signal.WINCH)
                            output.write("ready\n".toByteArray()); output.flush()
                            Thread {
                                try {
                                    if (input.read() == 42) { output.write(42); output.flush() }
                                    callback.onExit(0)
                                } catch (_: Exception) { }
                            }.apply { isDaemon = true }.start()
                        }
                        override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) {}
                    }
                }
                val known = trust(server to key)
                try {
                    val chain = SshEngine(backend).connect(connection(server, SshAuthMethod.Password("temporary".toCharArray())), known, refuseNew)
                    try {
                        val shell = chain.target.openShell("xterm-256color", 80, 24, 800, 480)
                        shell.use {
                            val ready = ByteArray(6)
                            java.io.DataInputStream(it.inputStream).readFully(ready)
                            assertEquals("ready\n", String(ready))
                            assertEquals("xterm-256color", observed.get().env["TERM"])
                            assertEquals("80", observed.get().env["COLUMNS"])
                            it.changeWindowDimensions(100, 40, 1000, 800)
                            assertTrue(resized.await(3, java.util.concurrent.TimeUnit.SECONDS))
                            assertEquals("100", observed.get().env["COLUMNS"])
                            assertEquals("40", observed.get().env["LINES"])
                            it.outputStream.write(42); it.outputStream.flush()
                            assertEquals(42, it.inputStream.read())
                            while (it.inputStream.read() != -1) { }
                            assertEquals("$backend exit", 0, it.exitStatus)
                        }
                    } finally { chain.closeAll(); chain.closeAll() }
                } finally { known.delete() }
            }
        }
    }

    @Test(timeout = 30000) fun localAndRemoteForwardsCarryTrafficAndReleaseListenersOnBothBackends() {
        for (backend in SshBackendChoice.entries) {
            val key = pair()
            server(key).use { server ->
                server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE // disposable loopback fixture only
                val known = trust(server to key)
                try {
                    val chain = SshEngine(backend).connect(connection(server, SshAuthMethod.Password("temporary".toCharArray())), known, refuseNew)
                    try {
                        for (direction in ForwardDirection.entries) {
                            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { echo ->
                                val worker = Thread { echo.accept().use { sock ->
                                    sock.soTimeout = 3000
                                    sock.getOutputStream().write(sock.getInputStream().read())
                                } }.apply { isDaemon = true; start() }
                                val forward = chain.target.startForward(ForwardSpec(direction, "127.0.0.1", 0, "127.0.0.1", echo.localPort))
                                val port = forward.boundPort
                                try {
                                    java.net.Socket("127.0.0.1", port).use { socket ->
                                        socket.soTimeout = 3000
                                        socket.getOutputStream().write(42)
                                        assertEquals("$backend $direction", 42, socket.getInputStream().read())
                                    }
                                    worker.join(3000); assertFalse(worker.isAlive)
                                } finally { forward.close(); forward.close() }
                                val deadline = System.nanoTime() + 3_000_000_000L
                                var released = false
                                while (!released && System.nanoTime() < deadline) {
                                    try { java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("127.0.0.1", port)); released = true } }
                                    catch (_: java.io.IOException) { Thread.sleep(10) }
                                }
                                assertTrue("$backend $direction listener not released", released)
                            }
                        }
                    } finally { chain.closeAll() }
                } finally { known.delete() }
            }
        }
    }

    @Test(timeout = 30000) fun badPasswordAndDeniedInteractiveAreFatal() {
        val key = pair()
        server(key).use { server ->
            val known = trust(server to key)
            try {
                assertThrows(SshFatalException::class.java) {
                    SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, SshAuthMethod.Password("wrong".toCharArray())), known, refuseNew)
                }
                server.keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                    override fun generateChallenge(session: ServerSession, username: String, lang: String, subMethods: String) =
                        org.apache.sshd.server.auth.keyboard.InteractiveChallenge().apply { addPrompt("OTP", false) }
                    override fun authenticate(session: ServerSession, username: String, responses: MutableList<String>) = false
                }
                assertThrows(SshFatalException::class.java) {
                    SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, SshAuthMethod.KeyboardInteractive { null }), known, refuseNew)
                }
            } finally { known.delete() }
        }
    }

    @Test(timeout = 30000) fun classicalOnlyPeerCannotDowngradeMina() {
        val key = pair(); val userKey = pair()
        server(key).use { server ->
            server.keyExchangeFactories = listOf(org.apache.sshd.server.ServerBuilder.DH2KEX.apply(org.apache.sshd.common.kex.BuiltinDHFactories.curve25519))
            val known = trust(server to key)
            try {
                var signs = 0
                val auth = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer { signs++; true })
                assertThrows(SshFatalException::class.java) { SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, auth), known, refuseNew) }
                assertEquals(0, signs)
            } finally { known.delete() }
        }
    }

    @Test(timeout = 30000) fun failedTargetTrustReleasesPartialJumpChain() {
        val bastionKey = pair(); val targetKey = pair()
        server(bastionKey).use { bastion -> server(targetKey).use { target ->
            bastion.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            val known = trust(bastion to bastionKey, target to pair())
            try {
                val auth = SshAuthMethod.Password("temporary".toCharArray())
                assertThrows(SshFatalException::class.java) {
                    SshEngine(SshBackendChoice.MINA_PQ).connect(connection(target, auth, connection(bastion, auth)), known, refuseNew)
                }
                val end = System.nanoTime() + 3_000_000_000L
                while (bastion.activeSessions.isNotEmpty() && System.nanoTime() < end) Thread.sleep(10)
                assertTrue(bastion.activeSessions.isEmpty())
            } finally { known.delete() }
        } }
    }

    @Test fun flagRejectsTyposAndDefaultEngineRemainsSshj() {
        assertEquals(SshBackendChoice.SSHJ, SshBackendChoice.parse("sshj"))
        assertEquals(SshBackendChoice.MINA_PQ, SshBackendChoice.parse("mina-pq"))
        assertThrows(IllegalArgumentException::class.java) { SshBackendChoice.parse("mina") }
    }

    @Test(timeout = 30000) fun deferredSoftwareMaterialLoadsOnlyAfterFreshApprovalOnEveryMinaConnect() {
        for (algorithm in listOf("RSA", "EC", "Ed25519")) {
        val hostKey = pair()
        val userKey = if (algorithm == "Ed25519") {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        } else pair(algorithm)
        server(hostKey).use { server ->
            server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ -> key.encoded.contentEquals(userKey.public.encoded) }
            val known = trust(server to hostKey)
            val events = CopyOnWriteArrayList<String>()
            try {
                val deferred = dev.wissam.sshterm.core.keys.DeferredKeyProvider(userKey.public) {
                    events.add("load"); userKey.private
                }
                val auth = SshAuthMethod.PublicKey(deferred, SigningAuthorizer { events.add("approve"); true })
                repeat(2) { SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, auth), known, refuseNew).closeAll() }
                assertEquals(listOf("approve", "load", "approve", "load"), events)
                events.clear()
                val denied = SshAuthMethod.PublicKey(deferred, SigningAuthorizer { events.add("deny"); false })
                assertThrows(SshFatalException::class.java) {
                    SshEngine(SshBackendChoice.MINA_PQ).connect(connection(server, denied), known, refuseNew)
                }
                assertEquals(listOf("deny"), events)
            } finally { known.delete() }
        }
        }
    }
}
