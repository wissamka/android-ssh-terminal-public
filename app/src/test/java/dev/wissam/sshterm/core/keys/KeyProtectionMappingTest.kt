package dev.wissam.sshterm.core.keys

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyProtectionMappingTest {
    @Test fun modernLevelsDistinguishStrongBoxTeeAndSoftware() {
        assertEquals(KeyProtectionMetadata.STRONGBOX,
            KeyProtectionMapping.securityLevel(KeyProperties.SECURITY_LEVEL_STRONGBOX))
        assertEquals(KeyProtectionMetadata.TEE,
            KeyProtectionMapping.securityLevel(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT))
        assertEquals(KeyProtectionMetadata.KEYSTORE_SOFTWARE,
            KeyProtectionMapping.securityLevel(KeyProperties.SECURITY_LEVEL_SOFTWARE))
    }

    @Test fun unknownSecureDoesNotInventHardwareType() {
        assertEquals(KeyProtectionMetadata.HARDWARE_UNSPECIFIED,
            KeyProtectionMapping.securityLevel(KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE))
    }

    @Test fun unknownAndFutureLevelsNeverClaimHardwareOrSoftware() {
        for (level in listOf(KeyProperties.SECURITY_LEVEL_UNKNOWN, 3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(KeyProtectionMetadata.UNKNOWN, KeyProtectionMapping.securityLevel(level))
        }
    }

    @Test fun legacyHardwareCannotDistinguishStrongBoxFromTee() {
        assertEquals(KeyProtectionMetadata.HARDWARE_UNSPECIFIED, KeyProtectionMapping.legacy(true))
    }

    @Test fun legacySoftwareRemainsKeystoreSoftware() {
        assertEquals(KeyProtectionMetadata.KEYSTORE_SOFTWARE, KeyProtectionMapping.legacy(false))
    }
}
