package dev.wissam.sshterm

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.core.keys.Ed25519BcRoutingProvider
import java.io.StringReader
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the "Don't know how to encode key: …OpenSslEdDsaPublicKey" bug on real
 * Android. Two code paths break without the fix — both exercised here:
 *
 *   (a) parsing a client OpenSSH Ed25519 private key (auth), and
 *   (b) sshj's [Ed25519KeyFactory] used to reconstruct the server's Ed25519 host key during KEX.
 *
 * Both funnel into `KeyFactory.getInstance("Ed25519")`, which on real Android resolves to
 * Conscrypt (returns `OpenSslEdDsaPublicKey`, classified UNKNOWN by sshj → encode throws). The
 * fix is [Ed25519BcRoutingProvider], installed at position 1 so that lookup routes to BC — every
 * Ed25519 key sshj sees is then a `BCEdDSAPublicKey` it recognises.
 */
@RunWith(AndroidJUnit4::class)
class Ed25519BcRoutingProviderTest {

    companion object {
        // Generate an ephemeral fixture for each test process; no private key is committed.
        private val fixture by lazy {
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(java.security.SecureRandom())
        }
        private val FIXTURE_PRIVATE_KEY: String by lazy {
            val seed = fixture.encoded
            try {
                dev.wissam.sshterm.core.keys.OpenSshKeyCodec.ed25519PrivateKeyFile(
                    seed, fixture.generatePublicKey().encoded, "ephemeral-test-fixture",
                )
            } finally {
                seed.fill(0)
            }
        }
        private val FIXTURE_HOST_KEY_RAW: ByteArray get() = fixture.generatePublicKey().encoded

        @JvmStatic
        @BeforeClass
        fun setUpProviders() {
            // Mirror SshTermApplication's provider setup (the test's Application isn't started).
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            if (Security.getProvider(Ed25519BcRoutingProvider.NAME) == null) {
                Security.insertProviderAt(Ed25519BcRoutingProvider(), 1)
            }
        }
    }

    @Test
    fun ed25519KeyFactory_resolvesToOurRouter_notConscrypt() {
        // The whole fix hinges on this: with the provider installed at position 1, the plain
        // (no-arg) lookup that sshj uses internally must land on our router. If a future Android
        // update quietly starts routing to Conscrypt again, this test fails loudly.
        val kf = KeyFactory.getInstance("Ed25519")
        assertEquals(Ed25519BcRoutingProvider.NAME, kf.provider.name)
    }

    @Test
    fun clientEd25519_parsesAsEncodableKey() {
        // Path (a): an OpenSSH v1 Ed25519 private key file. Before the fix, on real Android this
        // parsed to a Conscrypt key and encoding the publickey-auth packet threw.
        val provider = OpenSSHKeyV1KeyFile().apply {
            init(StringReader(FIXTURE_PRIVATE_KEY), null as net.schmizz.sshj.userauth.password.PasswordFinder?)
        }
        val pub = provider.public

        assertEquals(KeyType.ED25519, KeyType.fromKey(pub))
        // The exact call site that used to throw: build the publickey wire blob.
        val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
        assertTrue("encoded public key blob should be non-empty", blob.isNotEmpty())

        // Signing works end-to-end (defence against a partially-broken KeyFactory routing).
        val challenge = "sshterm-ed25519-roundtrip".toByteArray()
        val signer = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        signer.initSign(provider.private)
        signer.update(challenge)
        val sig = signer.sign()
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(pub)
        verifier.update(challenge)
        assertTrue("signature must verify against the parsed public key", verifier.verify(sig))
    }

    @Test
    fun hostEd25519_reconstructsAsEncodableKey() {
        // Path (b): sshj rebuilds a server's Ed25519 host key from 32 raw bytes during KEX.
        // Before the fix this returned a Conscrypt key; host-key verification then failed and
        // KeyExchanger's error-message path called Buffer.putPublicKey on it → same throw.
        val hostKey = Ed25519KeyFactory.getPublicKey(FIXTURE_HOST_KEY_RAW)
        assertNotNull(hostKey)
        assertNotEquals(KeyType.UNKNOWN, KeyType.fromKey(hostKey))
        assertEquals(KeyType.ED25519, KeyType.fromKey(hostKey))

        // The exact fingerprint/known-hosts encoding path that previously threw.
        val blob = Buffer.PlainBuffer().putPublicKey(hostKey).compactData
        assertTrue("encoded host key blob should be non-empty", blob.isNotEmpty())
    }
}
