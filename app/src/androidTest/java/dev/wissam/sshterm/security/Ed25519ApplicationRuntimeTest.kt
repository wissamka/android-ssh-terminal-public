package dev.wissam.sshterm.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wissam.sshterm.SshTermApplication
import dev.wissam.sshterm.core.keys.Ed25519BcRoutingProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unlike the older provider test, this never installs providers to repair Application setup. */
@RunWith(AndroidJUnit4::class)
class Ed25519ApplicationRuntimeTest {
    @Before fun requireProductionStartup() {
        requireDisposableAuditEmulator()
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            is SshTermApplication)
    }

    @Test fun applicationInstalledBundledBcAndEd25519RouterWithoutTestRepair() {
        assertTrue("Must replace Android's stripped BC with the bundled implementation",
            Security.getProvider("BC") is BouncyCastleProvider)
        assertEquals(Ed25519BcRoutingProvider.NAME, Security.getProviders().first().name)
        for (name in listOf("Ed25519", "1.3.101.112", "OID.1.3.101.112")) {
            assertEquals(Ed25519BcRoutingProvider.NAME, KeyFactory.getInstance(name).provider.name)
        }
    }

    @Test fun applicationRoutingProducesSshjEncodableClientAndHostKeys() {
        val pair = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        val clientKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(pair.public.encoded))
        val rawHostKey = SubjectPublicKeyInfo.getInstance(pair.public.encoded).publicKeyData.bytes
        val hostKey = Ed25519KeyFactory.getPublicKey(rawHostKey)
        for (key in listOf(clientKey, hostKey)) {
            assertEquals(KeyType.ED25519, KeyType.fromKey(key))
            assertTrue(Buffer.PlainBuffer().putPublicKey(key).compactData.isNotEmpty())
        }
        val message = "disposable application-provider runtime test".toByteArray()
        val signed = Signature.getInstance("Ed25519", "BC").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        assertTrue(Signature.getInstance("Ed25519", "BC").run {
            initVerify(hostKey)
            update(message)
            verify(signed)
        })
    }
}
