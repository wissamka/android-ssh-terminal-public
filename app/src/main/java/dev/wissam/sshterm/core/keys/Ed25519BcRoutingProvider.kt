package dev.wissam.sshterm.core.keys

import java.security.Key
import java.security.KeyFactory
import java.security.KeyFactorySpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * One-service JCA provider that owns `KeyFactory.Ed25519` and delegates to BouncyCastle.
 *
 * Installed at position 1 so sshj's internal `Ed25519KeyFactory` — which does
 * `KeyFactory.getInstance("Ed25519")` with no provider argument — picks this up instead of
 * Android's Conscrypt. Conscrypt returns `OpenSslEdDsaPublicKey`, which sshj's
 * [net.schmizz.sshj.common.KeyType.fromKey] classifies as `UNKNOWN`, so any attempt to encode it
 * (client publickey-auth packet, host-key wire form, fingerprint) throws
 * *"Don't know how to encode key: …OpenSslEdDsaPublicKey"*. That kills both software Ed25519 client
 * auth AND connecting to any host whose host key is Ed25519 (the OpenSSH default), before
 * authentication.
 *
 * We can't pin BC as sshj's SecurityProvider (breaks AndroidKeyStore hardware signing — see
 * [dev.wissam.sshterm.SshTermApplication]) or globally prepend the whole BouncyCastleProvider
 * (would shadow Conscrypt for TLS/EC/etc.). This provider only advertises the single Ed25519
 * KeyFactory service, so every other algorithm lookup falls through to Conscrypt / AndroidKeyStore
 * as before.
 */
class Ed25519BcRoutingProvider : Provider(
    NAME,
    1.0,
    "Routes KeyFactory.Ed25519 to BouncyCastle (sshj-on-Android Conscrypt shim)",
) {
    init {
        // The OID alias mirrors BC/Conscrypt so callers that resolve by RFC 8410 OID land here too.
        putService(object : Service(
            this,
            "KeyFactory",
            "Ed25519",
            DelegatingSpi::class.java.name,
            listOf("OID.1.3.101.112", "1.3.101.112"),
            null,
        ) {
            override fun newInstance(constructorParameter: Any?): Any = DelegatingSpi()
        })
    }

    private class DelegatingSpi : KeyFactorySpi() {
        private val delegate: KeyFactory =
            KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)

        @Throws(InvalidKeySpecException::class)
        override fun engineGeneratePublic(keySpec: KeySpec): PublicKey =
            delegate.generatePublic(keySpec)

        @Throws(InvalidKeySpecException::class)
        override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey =
            delegate.generatePrivate(keySpec)

        @Throws(InvalidKeySpecException::class)
        override fun <T : KeySpec> engineGetKeySpec(key: Key, keySpec: Class<T>): T =
            delegate.getKeySpec(key, keySpec)

        override fun engineTranslateKey(key: Key): Key = delegate.translateKey(key)
    }

    companion object {
        const val NAME = "SSHTermEd25519BcRouter"
    }
}
