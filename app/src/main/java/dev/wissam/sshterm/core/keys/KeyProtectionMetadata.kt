package dev.wissam.sshterm.core.keys

import android.security.keystore.KeyProperties

/** Runtime metadata only: never persist these values over the legacy storage-routing enum. */
enum class KeyProtectionMetadata(val label: String, val description: String) {
    STRONGBOX(
        "StrongBox-backed",
        "Android reports StrongBox protection for this non-exportable Keystore private key.",
    ),
    TEE(
        "TEE-backed",
        "Android reports trusted execution environment (TEE) protection for this non-exportable Keystore private key.",
    ),
    HARDWARE_UNSPECIFIED(
        "Hardware-backed (type unknown)",
        "Android reports secure hardware protection but does not identify StrongBox or TEE. The Keystore private key is non-exportable.",
    ),
    KEYSTORE_SOFTWARE(
        "Keystore (software-backed)",
        "Android reports software protection. The Keystore private key is non-exportable through the app, but is not hardware-backed.",
    ),
    UNKNOWN(
        "Keystore (protection unknown)",
        "This entry uses Android Keystore, but its protection could not be verified. The key may be missing or unavailable; hardware backing is not established.",
    ),
    ENCRYPTED_FILE(
        "Software key (encrypted at rest)",
        "The SSH private key is stored in an encrypted file and decrypted into app memory for use. A Keystore encryption key does not make the SSH key hardware-backed.",
    ),
}

/** Pure mapping; Android constants are compile-time integers, with no provider calls. */
internal object KeyProtectionMapping {
    fun securityLevel(level: Int): KeyProtectionMetadata = when (level) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtectionMetadata.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtectionMetadata.TEE
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtectionMetadata.KEYSTORE_SOFTWARE
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyProtectionMetadata.HARDWARE_UNSPECIFIED
        else -> KeyProtectionMetadata.UNKNOWN
    }

    fun legacy(insideSecureHardware: Boolean): KeyProtectionMetadata =
        if (insideSecureHardware) KeyProtectionMetadata.HARDWARE_UNSPECIFIED
        else KeyProtectionMetadata.KEYSTORE_SOFTWARE
}
