package dev.wissam.sshterm.core.keys

import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * sshj [KeyProvider] bridging to a non-exportable Android Keystore key pair. getPrivate() hands
 * sshj an opaque PrivateKey handle; the actual signing happens inside secure hardware when sshj
 * calls Signature.initSign/sign with it. This only works because sshj's BC auto-registration is
 * disabled at startup (see SshTermApplication) so JCA delayed provider selection routes the
 * operation to the AndroidKeyStore provider.
 */
class KeystoreKeyProvider(private val alias: String) : KeyProvider {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    override fun getPrivate(): PrivateKey =
        keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Keystore entry '$alias' missing or not a private key")

    override fun getPublic(): PublicKey =
        keyStore.getCertificate(alias)?.publicKey
            ?: throw IllegalStateException("Keystore entry '$alias' has no certificate/public key")

    /** Inspect the existing key policy; never infer per-use protection from alias or API level. */
    fun signingProtection(): dev.wissam.sshterm.core.ssh.SigningProtection {
        val key = private
        val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        return if (KeyAuthenticationPolicy.isPerOperation(info.isUserAuthenticationRequired, info.userAuthenticationValidityDurationSeconds)) {
            dev.wissam.sshterm.core.ssh.SigningProtection.KEYSTORE_PER_OPERATION
        } else {
            dev.wissam.sshterm.core.ssh.SigningProtection.LEGACY_KEYSTORE_WINDOW
        }
    }

    override fun getType(): KeyType = KeyType.fromKey(public)
}
