package dev.wissam.sshterm.core.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only in a disposable test installation. Each test owns just its random alias; no app
 * records, production aliases, global provider settings, or authentication settings are changed.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreMetadataInspectorTest {
    private val inspector = KeystoreMetadataInspector()

    @Test fun ordinaryEcKeystoreReportsActualProtectionAndPreservesKey() = withAlias { alias, store ->
        generate(alias, ec = true)
        verifyMetadataAndPreservation(alias, store)
    }

    @Test fun ordinaryRsaKeystoreReportsActualProtectionAndPreservesKey() = withAlias { alias, store ->
        generate(alias, ec = false)
        verifyMetadataAndPreservation(alias, store)
    }

    @Test fun strongBoxRequestOrUnavailableFallbackUsesActualMetadata() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        withAlias { alias, store ->
            var usedStrongBox = true
            try {
                generate(alias, ec = true, strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                usedStrongBox = false
                generate(alias, ec = true)
            }
            verifyMetadataAndPreservation(alias, store)
            if (usedStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertEquals(KeyProtectionMetadata.STRONGBOX, inspector.inspect(alias))
            }
        }
    }

    @Test fun missingAliasIsUnknownAndIsNotCreated() = withAlias { alias, store ->
        assertFalse(store.containsAlias(alias))
        assertEquals(KeyProtectionMetadata.UNKNOWN, inspector.inspect(alias))
        assertFalse(store.containsAlias(alias))
    }

    @Test fun deletedAliasDoesNotRetainPreviouslyInspectedProtection() = withAlias { alias, store ->
        generate(alias, ec = true)
        verifyMetadataAndPreservation(alias, store)
        store.deleteEntry(alias) // Only this test's disposable key.
        assertEquals(KeyProtectionMetadata.UNKNOWN, inspector.inspect(alias))
        assertFalse(store.containsAlias(alias))
    }

    @Test fun secretKeyAliasIsUnknownWithoutReplacingIt() = withAlias { alias, store ->
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
        assertEquals(KeyProtectionMetadata.UNKNOWN, inspector.inspect(alias))
        assertTrue(store.containsAlias(alias))
        assertEquals(KeyProperties.KEY_ALGORITHM_AES, store.getKey(alias, null).algorithm)
    }

    private fun verifyMetadataAndPreservation(alias: String, store: KeyStore) {
        val before = store.getCertificate(alias).publicKey.encoded
        val key = store.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java)
        // Independently derive the expected tier from Android, never from requested generation
        // flags, device features, the algorithm, or the non-exportable private-key handle.
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtectionMetadata.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtectionMetadata.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtectionMetadata.KEYSTORE_SOFTWARE
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyProtectionMetadata.HARDWARE_UNSPECIFIED
                else -> KeyProtectionMetadata.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyProtectionMetadata.HARDWARE_UNSPECIFIED
            else KeyProtectionMetadata.KEYSTORE_SOFTWARE
        }
        repeat(2) { assertEquals(expected, inspector.inspect(alias)) }
        assertNotEquals(KeyProtectionMetadata.ENCRYPTED_FILE, inspector.inspect(alias))
        assertTrue(store.containsAlias(alias))
        assertArrayEquals(before, store.getCertificate(alias).publicKey.encoded)
        assertNull((store.getKey(alias, null) as PrivateKey).encoded)
    }

    private fun generate(alias: String, ec: Boolean, strongBox: Boolean = false) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (ec) spec.setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        else spec.setKeySize(2048).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(strongBox)
        KeyPairGenerator.getInstance(
            if (ec) KeyProperties.KEY_ALGORITHM_EC else KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore",
        ).apply { initialize(spec.build()) }.generateKeyPair()
    }

    private fun withAlias(test: (String, KeyStore) -> Unit) {
        val alias = "sshterm-metadata-test-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!store.containsAlias(alias))
        try {
            test(alias, store)
        } finally {
            store.deleteEntry(alias)
        }
    }
}
