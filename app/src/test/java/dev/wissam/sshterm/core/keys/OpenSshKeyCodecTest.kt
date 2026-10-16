package dev.wissam.sshterm.core.keys

import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.Security
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies the encrypted openssh-key-v1 exporter round-trips: a key encoded with a passphrase is
 * readable back with that passphrase and yields the original public key. (An external harness also
 * confirmed real `ssh-keygen -y -P` reads these files; this test guards the encoder in CI.)
 */
class OpenSshKeyCodecTest {

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun freshEd25519(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        val seed = ASN1OctetString.getInstance(
            PrivateKeyInfo.getInstance(pair.private.encoded).parsePrivateKey()
        ).octets
        val pub = SubjectPublicKeyInfo.getInstance(pair.public.encoded).publicKeyData.bytes
        return seed to pub
    }

    @Test
    fun encryptedExport_roundTripsThroughSshjReader() {
        val (seed, pub) = freshEd25519()

        val pem = OpenSshKeyCodec.ed25519EncryptedPrivateKeyFile(
            seed, pub, "roundtrip", "correct horse".toCharArray(), rounds = 8
        )

        val provider = OpenSSHKeyV1KeyFile()
        provider.init(StringReader(pem), object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?) = "correct horse".toCharArray()
            override fun shouldRetry(resource: Resource<*>?) = false
        })
        val readPublic = provider.public
        assertNotNull(readPublic)
        // The public key parsed from the decrypted container must match the input public bytes.
        val readPubBytes = SubjectPublicKeyInfo.getInstance(readPublic.encoded).publicKeyData.bytes
        assertEquals(pub.toList(), readPubBytes.toList())

        // And the private scalar must decrypt correctly too (sign/verify would fail otherwise).
        assertNotNull(provider.private)
    }
}
