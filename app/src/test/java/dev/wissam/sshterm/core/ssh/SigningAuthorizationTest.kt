package dev.wissam.sshterm.core.ssh

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.signature.Signature
import org.junit.Assert.*
import org.junit.Test

class SigningAuthorizationTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
    private val request = SigningRequest("target", 22, "alice", "rsa-sha2-256", SigningProtection.SOFTWARE_APP_GATE)
    private class Probe : Signature {
        var initialized = 0
        var signatures = 0
        val bytes = java.io.ByteArrayOutputStream()
        override fun getSignatureName() = "rsa-sha2-256"
        override fun initSign(key: PrivateKey) { initialized++ }
        override fun initVerify(key: PublicKey) {}
        override fun update(data: ByteArray) = update(data, 0, data.size)
        override fun update(data: ByteArray, off: Int, len: Int) { bytes.write(data, off, len) }
        override fun sign(): ByteArray { signatures++; return bytes.toByteArray() }
        override fun encode(data: ByteArray) = data
        override fun verify(data: ByteArray) = true
    }
    @Test fun `every signature including new attempts needs fresh approval`() {
        var prompts = 0
        val authorize = SigningAuthorizer { prompts++; assertEquals("target", it.host); true }
        repeat(3) {
            val probe = Probe()
            val signature = AuthorizedSignature(probe, request, authorize)
            signature.initSign(key)
            signature.update(byteArrayOf(1, 2, 3), 1, 2)
            assertEquals(it, prompts)
            assertEquals(0, probe.initialized)
            assertArrayEquals(byteArrayOf(2, 3), signature.sign())
            assertEquals(it + 1, prompts)
            assertThrows(IllegalStateException::class.java) { signature.sign() }
            assertEquals(1, probe.signatures)
        }
    }
    @Test fun `denial consumes operation without initializing private signing`() {
        val probe = Probe()
        val signature = AuthorizedSignature(probe, request, SigningAuthorizer.Deny)
        signature.initSign(key)
        signature.update(byteArrayOf(9))
        assertThrows(SigningAuthorizationException::class.java) { signature.sign() }
        assertThrows(IllegalStateException::class.java) { signature.sign() }
        assertEquals(0, probe.initialized)
        assertEquals(0, probe.signatures)
    }
    @Test fun `prompt failure fails closed`() {
        val probe = Probe()
        val signature = AuthorizedSignature(probe, request, SigningAuthorizer { throw IllegalStateException("UI gone") })
        signature.initSign(key)
        assertThrows(SigningAuthorizationException::class.java) { signature.sign() }
        assertEquals(0, probe.signatures)
    }
    @Test fun `host verification never prompts`() {
        val signature = AuthorizedSignature(Probe(), request, SigningAuthorizer { error("unexpected prompt") })
        signature.initVerify(KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public)
        signature.update(byteArrayOf(1))
        assertTrue(signature.verify(byteArrayOf(1)))
    }
    @Test fun `opaque software provider decrypts once per actual signature and never for feelers`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        var loads = 0
        val provider = dev.wissam.sshterm.core.keys.DeferredKeyProvider(pair.public) { loads++; pair.private }
        assertEquals(net.schmizz.sshj.common.KeyType.RSA, provider.type)
        assertEquals(net.schmizz.sshj.common.KeyType.RSA, net.schmizz.sshj.common.KeyType.fromKey(provider.private))
        assertNull(provider.private.encoded)
        assertEquals(0, loads)
        repeat(3) {
            val signature = AuthorizedSignature(Probe(), request, SigningAuthorizer { true })
            signature.initSign(provider.private)
            signature.update(byteArrayOf(1))
            assertEquals(it, loads)
            signature.sign()
            assertEquals(it + 1, loads)
        }
        val denied = AuthorizedSignature(Probe(), request, SigningAuthorizer.Deny)
        denied.initSign(provider.private)
        assertThrows(SigningAuthorizationException::class.java) { denied.sign() }
        assertEquals(3, loads)
    }

    @Test fun `opaque EC key retains SSHJ curve classification without scalar material`() {
        for ((curve, type) in listOf("secp256r1" to net.schmizz.sshj.common.KeyType.ECDSA256,
            "secp384r1" to net.schmizz.sshj.common.KeyType.ECDSA384,
            "secp521r1" to net.schmizz.sshj.common.KeyType.ECDSA521)) {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec(curve)) }.generateKeyPair()
            val provider = dev.wissam.sshterm.core.keys.DeferredKeyProvider(pair.public) { error("Must remain deferred") }
            assertEquals(type, net.schmizz.sshj.common.KeyType.fromKey(provider.private))
            assertNull(provider.private.encoded)
        }
    }

    @Test fun `lease cancellation denies standalone reader authorization`() {
        val lease = kotlinx.coroutines.Job()
        val entered = java.util.concurrent.CountDownLatch(1)
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = Probe()
        val signature = AuthorizedSignature(probe, request, SigningAuthorizer {
            kotlinx.coroutines.withContext(lease) {
                entered.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
        })
        signature.initSign(key)
        val reader = Thread {
            try { signature.sign() } catch (e: SigningAuthorizationException) { denied.set(true) }
        }
        reader.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        lease.cancel()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertTrue(denied.get())
        assertEquals(0, probe.signatures)
    }

    @Test fun `reader interruption cancels suspended prompt preserves interrupt and never signs`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = Probe()
        val signature = AuthorizedSignature(probe, request, SigningAuthorizer {
            try {
                entered.countDown()
                kotlinx.coroutines.awaitCancellation()
            } finally { cancelled.countDown() }
        })
        signature.initSign(key)
        val reader = Thread {
            try { signature.sign() }
            catch (e: SigningAuthorizationException) {
                denied.set(true)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        reader.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        reader.interrupt()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertTrue(cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(denied.get())
        assertTrue(interrupted.get())
        assertEquals(0, probe.signatures)
    }

    @Test fun `configured factories preserve retry names and gate every created signer`() {
        var prompts = 0
        val factories = listOf("rsa-sha2-512", "rsa-sha2-256").map { name ->
            object : net.schmizz.sshj.common.Factory.Named<com.hierynomus.sshj.key.KeyAlgorithm> {
                override fun getName() = name
                override fun create() = object : com.hierynomus.sshj.key.KeyAlgorithm {
                    override fun getKeyAlgorithm() = name
                    override fun getKeyFormat() = net.schmizz.sshj.common.KeyType.RSA
                    override fun newSignature(): Signature = Probe()
                    override fun readPubKeyFromBuffer(buf: net.schmizz.sshj.common.Buffer<*>): PublicKey = error("unused")
                    override fun putPubKeyIntoBuffer(key: PublicKey, buf: net.schmizz.sshj.common.Buffer<*>) {}
                }
            }
        }
        val wrapped = authorizedAlgorithms(factories, request, SigningAuthorizer { prompts++; true })
        assertEquals(factories.map { it.name }, wrapped.map { it.name })
        repeat(2) { // A fresh config/algorithm use on another hop or reconnect has no reusable grant.
            wrapped.forEach { factory ->
                val signature = factory.create().newSignature()
                signature.initSign(key)
                signature.update(byteArrayOf(1))
                signature.sign()
            }
        }
        assertEquals(4, prompts)
    }

    @Test fun `per operation uses exact initialized crypto signature and SSHJ encoder`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        var seen: java.security.Signature? = null
        val probe = Probe()
        val signature = AuthorizedSignature(probe, request.copy(protection = SigningProtection.KEYSTORE_PER_OPERATION),
            SigningAuthorizer { seen = it.cryptoSignature; true })
        signature.initSign(pair.private)
        signature.update(byteArrayOf(4, 5))
        val result = signature.sign()
        assertNotNull(seen)
        assertEquals(0, probe.signatures)
        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update(byteArrayOf(4, 5))
        assertTrue(verifier.verify(result))
    }
}
