package dev.wissam.sshterm.core.keys

import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECKey
import java.security.spec.ECParameterSpec
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/** Public-only provider. Its opaque private handle can only be resolved by the signing boundary.
 * No parsed private key or passphrase is retained between signature attempts.
 */
class DeferredKeyProvider(private val publicKey: PublicKey, private val load: suspend () -> PrivateKey) : KeyProvider {
    override fun getPublic() = publicKey
    override fun getType(): KeyType = KeyType.fromKey(publicKey)
    override fun getPrivate(): PrivateKey = if (publicKey is ECKey) {
        object : DeferredSigningKey(publicKey.algorithm, load), ECKey {
            override fun getParams(): ECParameterSpec = publicKey.params
        }
    } else DeferredSigningKey(publicKey.algorithm, load)
}

open class DeferredSigningKey internal constructor(
    private val algorithmName: String,
    private val loader: suspend () -> PrivateKey,
) : PrivateKey {
    override fun getAlgorithm() = algorithmName
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
    internal suspend fun loadForSignature(): PrivateKey = loader()
}
