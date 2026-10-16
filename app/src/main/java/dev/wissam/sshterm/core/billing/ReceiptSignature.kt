package dev.wissam.sshterm.core.billing

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Play's RSA purchase signature. Public key only; never embed service-account credentials. */
internal class ReceiptSignature(encodedPublicKey: String) {
    private val key = runCatching {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey)),
        )
    }.getOrNull()
    val configured: Boolean get() = key != null

    fun verify(json: String, signature: String): Boolean = runCatching {
        val publicKey = key ?: return false
        Signature.getInstance("SHA1withRSA").run {
            initVerify(publicKey)
            update(json.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)
}
