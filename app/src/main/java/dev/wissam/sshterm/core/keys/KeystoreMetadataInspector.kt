package dev.wissam.sshterm.core.keys

import android.os.Build
import android.security.keystore.KeyInfo
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import javax.inject.Inject

/** Read-only inspection of an existing alias. Never generates, replaces, or deletes material. */
class KeystoreMetadataInspector @Inject constructor() {
    fun inspect(alias: String): KeyProtectionMetadata = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? PrivateKey
        if (key == null) {
            KeyProtectionMetadata.UNKNOWN
        } else {
            val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                KeyProtectionMapping.securityLevel(info.securityLevel)
            } else {
                @Suppress("DEPRECATION")
                KeyProtectionMapping.legacy(info.isInsideSecureHardware)
            }
        }
    } catch (_: Exception) {
        // Missing, invalidated, unsupported, or temporarily inaccessible metadata is not proof
        // of either software or hardware protection. Keep the original alias available for retry.
        KeyProtectionMetadata.UNKNOWN
    }
}
