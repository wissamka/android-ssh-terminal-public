package dev.wissam.sshterm.security.keystore

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.core.keys.GeneratedKeyAlgorithm
import dev.wissam.sshterm.core.keys.KeyFileAuthorizationDeniedException
import dev.wissam.sshterm.core.keys.KeyFileAuthorizationRequiredException
import dev.wissam.sshterm.core.keys.KeyManager
import dev.wissam.sshterm.core.keys.KeystoreKeyProvider
import dev.wissam.sshterm.core.keys.KeystoreMetadataInspector
import dev.wissam.sshterm.core.keys.VersionedKeyFile
import dev.wissam.sshterm.core.ssh.AuthorizedSignature
import dev.wissam.sshterm.core.ssh.SigningAuthorizationException
import dev.wissam.sshterm.core.ssh.SigningAuthorizer
import dev.wissam.sshterm.core.ssh.SigningProtection
import dev.wissam.sshterm.core.ssh.SigningRequest
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Parent/operator must approve each APPROVE prompt using the enrolled emulator fingerprint.
 * No mocked BiometricPrompt callbacks, fake authorizer success, shell command, or app unlock hook.
 */
@RunWith(AndroidJUnit4::class)
class ApprovedKeystoreRuntimeTest : BiometricKeystoreFixture() {
    override val mode = "enrolled"

    @Test fun ecSignaturesRequireActualApprovalForEachOperation() = signTwice(ec = true)
    @Test fun rsaSignaturesRequireActualApprovalForEachOperation() = signTwice(ec = false)

    private fun newSigningProvider(ec: Boolean): KeystoreKeyProvider {
        val manager = KeyManager(context, files, KeystoreMetadataInspector())
        val entity = manager.generate("Disposable runtime key",
            if (ec) GeneratedKeyAlgorithm.ECDSA_P256 else GeneratedKeyAlgorithm.RSA_3072)
        ownNewAlias(entity.storageRef)
        return KeystoreKeyProvider(entity.storageRef).also {
            assertEquals(SigningProtection.KEYSTORE_PER_OPERATION, it.signingProtection())
            assertNull(it.private.encoded)
        }
    }

    private fun signTwice(ec: Boolean) {
        val provider = newSigningProvider(ec)
        val jca = if (ec) "SHA256withECDSA" else "SHA256withRSA"
        val ssh = if (ec) "ecdsa-sha2-nistp256" else "rsa-sha2-256"
        val publicBefore = provider.public.encoded
        repeat(2) { attempt ->
            // This is a distinct initialized operation. Even approval moments ago cannot unlock it.
            assertUnapprovedOperationFails { rawSign(provider.private, jca) }
            val delegate = if (ec) SignatureECDSA(jca, ssh) else SignatureRSA(jca, KeyType.RSA, ssh)
            val signer = AuthorizedSignature(delegate,
                SigningRequest("disposable.test", 22, "audit", ssh, SigningProtection.KEYSTORE_PER_OPERATION),
                SigningAuthorizer { request ->
                    val signature = checkNotNull(request.cryptoSignature)
                    actualPrompt(BiometricPrompt.CryptoObject(signature), "${if (ec) "EC" else "RSA"} signature ${attempt + 1}/2")
                })
            val payload = "runtime signed payload $attempt".toByteArray()
            signer.initSign(provider.private)
            signer.update(payload)
            val signed = signer.sign() // The actual production signing boundary + JCA operation.
            assertEquals(attempt + 1, approvals.get())
            val verify = Signature.getInstance(jca).apply { initVerify(provider.public); update(payload) }
            assertTrue("Real Keystore signature must verify", verify.verify(signed))
            assertTrue("SSHJ must encode the actual signature", signer.encode(signed).isNotEmpty())
            assertThrows(IllegalStateException::class.java) { signer.sign() }
            assertUnapprovedOperationFails { rawSign(provider.private, jca) }
        }
        assertArrayEquals(publicBefore, provider.public.encoded)
    }

    @Test fun actualCipherApprovalsMigrateDecryptAndFinalizeWithoutReusableGrant() = runBlocking {
        val (file, plaintext) = newLegacyFile()
        val original = file.readBytes()
        val recovery = files.migrateLegacyAuthorized(file, realCipherAuthorizer())
        assertEquals("Encryption and independent verification each require a platform approval", 2, approvals.get())
        assertTrue(files.hasAuthBoundEnvelope(file))
        assertTrue(files.hasLegacyRecovery(file))
        assertArrayEquals(original, recovery.readBytes())
        files.validateAuthBoundPolicy(file)
        assertThrows(KeyFileAuthorizationRequiredException::class.java) { files.decryptFromFile(file) }
        val key = store.getKey("sshterm-key-file-auth-v2", null) as SecretKey
        assertUnapprovedOperationFails {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(plaintext)
        }
        val decrypted = files.decryptAuthorized(file, realCipherAuthorizer())
        assertArrayEquals(plaintext, decrypted)
        decrypted.fill(0)
        assertEquals(3, approvals.get())
        val encrypted = file.readBytes()
        assertUnapprovedOperationFails {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, VersionedKeyFile.iv(encrypted)))
            }
            VersionedKeyFile.bind(cipher, file.name)
            VersionedKeyFile.decrypt(cipher, encrypted)
        }
        files.retireLegacyRecoveryAuthorized(file, realCipherAuthorizer())
        assertEquals(4, approvals.get())
        assertFalse(files.hasLegacyRecovery(file))
        assertArrayEquals(encrypted, file.readBytes())
        plaintext.fill(0)
    }

    @Test fun cancellingActualMigrationPromptPreservesLegacyBytes() = runBlocking {
        val (file, plaintext) = newLegacyFile()
        val original = file.readBytes()
        try {
            files.migrateLegacyAuthorized(file, realCipherAuthorizer(cancel = true))
            fail("Cancelled prompt must not migrate")
        } catch (_: KeyFileAuthorizationDeniedException) { }
        assertEquals(1, prompts.get())
        assertEquals("Cancellation must target an actual pending platform attempt", 1, pendingPlatformAttempts.get())
        assertEquals(0, approvals.get())
        assertFalse(files.hasAuthBoundEnvelope(file))
        assertFalse(files.hasLegacyRecovery(file))
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(plaintext, files.decryptFromFile(file))
        plaintext.fill(0)
    }

    @Test fun cancellingFinalizationRetainsVerifiedPrimaryAndLegacyRecovery() = runBlocking {
        val (file, plaintext) = newLegacyFile()
        val original = file.readBytes()
        val recovery = files.migrateLegacyAuthorized(file, realCipherAuthorizer()) // Two real approvals.
        val primary = file.readBytes()
        try {
            files.retireLegacyRecoveryAuthorized(file, realCipherAuthorizer(cancel = true))
            fail("Cancelled verification must retain recovery")
        } catch (_: KeyFileAuthorizationDeniedException) { }
        assertEquals(2, approvals.get())
        assertEquals(3, pendingPlatformAttempts.get())
        assertArrayEquals(primary, file.readBytes())
        assertArrayEquals(original, recovery.readBytes())
        plaintext.fill(0)
    }

    @Test fun cancellingActualSignaturePromptDoesNotAuthorizePrivateOperation() {
        val provider = newSigningProvider(ec = true)
        val signer = AuthorizedSignature(SignatureECDSA("SHA256withECDSA", "ecdsa-sha2-nistp256"),
            SigningRequest("disposable.test", 22, "audit", "ecdsa-sha2-nistp256", SigningProtection.KEYSTORE_PER_OPERATION),
            SigningAuthorizer { request -> actualPrompt(
                BiometricPrompt.CryptoObject(checkNotNull(request.cryptoSignature)), "Cancel signature", cancel = true,
            ) })
        signer.initSign(provider.private)
        signer.update(byteArrayOf(1, 2, 3))
        assertThrows(SigningAuthorizationException::class.java) { signer.sign() }
        assertEquals(0, approvals.get())
        assertEquals(1, pendingPlatformAttempts.get())
        assertUnapprovedOperationFails { rawSign(provider.private, "SHA256withECDSA") }
    }

    private fun rawSign(key: PrivateKey, algorithm: String): ByteArray = Signature.getInstance(algorithm).run {
        initSign(key)
        update(byteArrayOf(7))
        sign()
    }

    private fun assertUnapprovedOperationFails(operation: () -> Any) {
        val failure = try { operation(); null } catch (e: Exception) { e }
        assertNotNull("A distinct operation without its own biometric approval must be rejected", failure)
        assertTrue("Expected JCA/Keystore rejection, got $failure",
            failure is GeneralSecurityException || failure is ProviderException)
        assertTrue("Rejection must specifically require user authentication, not a broken algorithm/key: $failure",
            generateSequence<Throwable>(failure) { it.cause }.any { cause ->
                cause is android.security.keystore.UserNotAuthenticatedException ||
                    (android.os.Build.VERSION.SDK_INT >= 33 && cause is android.security.KeyStoreException &&
                        cause.requiresUserAuthentication())
            })
    }
}
