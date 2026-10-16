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
class SshEngineProtocolTest {
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
    private fun trust(vararg servers: Pair<SshServer, KeyPair>) = Files.createTempFile("sshterm-protocol", ".known_hosts").toFile().apply {
        writeText(servers.joinToString("\n", postfix = "\n") { (s, key) ->
            "[127.0.0.1]:${s.port} ${org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key.public)}"
        })
    }
    private fun connection(server: SshServer, auth: SshAuthMethod, jump: ResolvedConnection? = null) =
        ResolvedConnection("127.0.0.1", server.port, "test", auth, jumpHost = jump)

    @Test(timeout = 30000) fun rsaAndEcSignaturesAuthenticateAndReconnectRequiresFreshAuthorization() {
        for (algorithm in listOf("RSA", "EC", "Ed25519")) {
            val hostKey = pair(); val userKey = pair(algorithm)
            server(hostKey).use { server ->
                server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, publicKey, _ -> publicKey.encoded.contentEquals(userKey.public.encoded) }
                val known = trust(server to hostKey)
                try {
                    val requests = CopyOnWriteArrayList<SigningRequest>()
                    val loads = java.util.concurrent.atomic.AtomicInteger()
                    val deferred = dev.wissam.sshterm.core.keys.DeferredKeyProvider(userKey.public) { loads.incrementAndGet(); userKey.private }
                    val auth = SshAuthMethod.PublicKey(deferred, SigningAuthorizer { requests.add(it); true })
                    repeat(2) {
                        val chain = SshEngine().connect(connection(server, auth), known, refuseNew)
                        assertTrue(chain.target.isAuthenticated); chain.closeAll()
                    }
                    assertEquals(2, requests.size)
                    assertEquals(2, loads.get())
                    assertTrue(requests.all { it.port == server.port && it.username == "test" })
                    val denied = SshAuthMethod.PublicKey(provider(userKey), SigningAuthorizer.Deny)
                    assertThrows(SshFatalException::class.java) { SshEngine().connect(connection(server, denied), known, refuseNew) }
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
                assertThrows(Exception::class.java) { SshEngine().connect(connection(server, auth), known, refuseNew) }
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
                val chain = SshEngine().connect(connection(server, auth), known, refuseNew)
                assertTrue(chain.target.isAuthenticated); chain.closeAll()
                assertEquals(listOf(true, false), observed.map { it.echo })
                assertEquals("Type user and OTP", observed.last().instructions)
                val cancelled = SshAuthMethod.KeyboardInteractive { null }
                assertThrows(SshFatalException::class.java) {
                    SshEngine().connect(connection(server, cancelled), known, refuseNew)
                }
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
                val chain = SshEngine().connect(connection(target, auth, connection(bastion, auth)), known, refuseNew)
                assertEquals(2, chain.clients.size); assertTrue(chain.target.isAuthenticated)
                chain.closeAll()
                assertEquals(listOf(bastion.port, target.port), ports)
            } finally { known.delete() }
        } }
    }
    @Test(timeout = 30000) fun sshjRemoteSignalEndsShellWithoutRequestingReconnect() {
        val key = pair()
        server(key).use { server ->
            server.shellFactory = org.apache.sshd.server.shell.ShellFactory {
                object : org.apache.sshd.server.command.Command {
                    override fun setInputStream(value: java.io.InputStream) {}
                    override fun setOutputStream(value: java.io.OutputStream) {}
                    override fun setErrorStream(value: java.io.OutputStream) {}
                    override fun setExitCallback(value: org.apache.sshd.server.ExitCallback) {}
                    override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) {}
                    override fun start(channel: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) {
                        val packet = channel.session.createBuffer(org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_REQUEST)
                        packet.putUInt(channel.recipient)
                        packet.putString("exit-signal"); packet.putBoolean(false)
                        packet.putString("TERM"); packet.putBoolean(false)
                        packet.putString(""); packet.putString("")
                        channel.writePacket(packet).addListener { channel.close(false) }
                    }
                }
            }
            val known = trust(server to key)
            try {
                val chain = SshEngine(SshBackendChoice.SSHJ).connect(
                    connection(server, SshAuthMethod.Password("temporary".toCharArray())), known, refuseNew,
                )
                try {
                    chain.target.openShell("xterm", 80, 24, 800, 480).use { shell ->
                        while (shell.inputStream.read() != -1) { }
                        assertNull(shell.exitStatus)
                        assertEquals(SshTermination.Signalled("TERM"), shell.termination)
                        assertFalse(shell.termination.shouldReconnect)
                    }
                } finally { chain.closeAll() }
            } finally { known.delete() }
        }
    }

}
