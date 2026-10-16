package dev.wissam.sshterm.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.common.Factory

/** These describe enforcement, not whether a key is actually hardware-backed. */
enum class SigningProtection { SOFTWARE_APP_GATE, LEGACY_KEYSTORE_WINDOW, KEYSTORE_PER_OPERATION }

data class SigningRequest(
    val host: String,
    val port: Int,
    val username: String,
    val algorithm: String,
    val protection: SigningProtection,
    /** Pass this exact initialized object to BiometricPrompt.CryptoObject; require BIOMETRIC_STRONG. */
    val cryptoSignature: java.security.Signature? = null,
)

fun interface SigningAuthorizer {
    /** Fresh prompt on EVERY invocation. Never satisfy this from app unlock or cached approval.
     * False/cancellation/error denies signing. Must cancel/dismiss the prompt on coroutine cancellation.
     * Runs from an SSHJ worker, so marshal UI work to Main. No private material is included.
     */
    suspend fun authorize(request: SigningRequest): Boolean

    companion object {
        val Deny = SigningAuthorizer { false }
    }
}

class SigningAuthorizationException(cause: Throwable? = null) :
    RuntimeException("Fresh authorization for SSH signing was denied or unavailable", cause)

/** Wrap all configured algorithms, including retry choices; verification remains unchanged. */
internal fun authorizedAlgorithms(
    algorithms: List<Factory.Named<KeyAlgorithm>>,
    request: SigningRequest,
    authorizer: SigningAuthorizer,
): List<Factory.Named<KeyAlgorithm>> = algorithms.map { factory ->
    object : Factory.Named<KeyAlgorithm> {
        override fun getName(): String = factory.name
        override fun create(): KeyAlgorithm {
            val algorithm = factory.create()
            return object : KeyAlgorithm by algorithm {
                override fun newSignature(): Signature = AuthorizedSignature(
                    algorithm.newSignature(), request.copy(algorithm = algorithm.keyAlgorithm), authorizer,
                )
            }
        }
    }
}

/** One-shot signature state. Private signing initialization is deferred until sign(), not provider load.
 * The SSHJ 0.40.0 KeyedAuthMethod supplies session ID + full auth packet before calling sign().
 */
internal class AuthorizedSignature(
    private val delegate: Signature,
    private val request: SigningRequest,
    private val authorizer: SigningAuthorizer,
) : Signature by delegate {
    private var privateKey: PrivateKey? = null
    private var signing = false
    private var consumed = false
    private val payload = ByteArrayOutputStream()

    override fun initSign(key: PrivateKey) {
        check(!signing && !consumed) { "Signature instances are single use" }
        privateKey = key
        signing = true
    }

    override fun initVerify(key: PublicKey) {
        check(!signing && !consumed)
        delegate.initVerify(key)
    }

    override fun update(data: ByteArray) = update(data, 0, data.size)

    override fun update(data: ByteArray, off: Int, len: Int) {
        if (signing) {
            check(!consumed)
            require(len >= 0 && payload.size().toLong() + len <= 1024 * 1024) { "Auth payload too large" }
            payload.write(data, off, len)
        } else delegate.update(data, off, len)
    }

    override fun sign(): ByteArray {
        check(signing && !consumed) { "Signature is not initialized or already consumed" }
        consumed = true // Denial/error also consumes this operation; retries need fresh objects/prompts.
        val key = checkNotNull(privateKey)
        val data = payload.toByteArray()
        try {
            val crypto = if (request.protection == SigningProtection.KEYSTORE_PER_OPERATION) {
                java.security.Signature.getInstance(when (delegate.signatureName) {
                    "ecdsa-sha2-nistp256" -> "SHA256withECDSA"
                    "rsa-sha2-256" -> "SHA256withRSA"
                    "rsa-sha2-512" -> "SHA512withRSA"
                    else -> error("Unsupported per-operation signing algorithm: ${delegate.signatureName}")
                }).apply { initSign(key) }
            } else null
            val allowed = try {
                runBlocking {
                    withTimeout(90_000) {
                        val result = authorizer.authorize(request.copy(cryptoSignature = crypto))
                        currentCoroutineContext().ensureActive()
                        result
                    }
                }
            } catch (e: InterruptedException) {
                // runBlocking cancels its coroutine on reader interruption but clears the flag.
                // Preserve the transport thread's interrupt and never proceed to private signing.
                Thread.currentThread().interrupt()
                throw SigningAuthorizationException(e)
            } catch (e: Exception) {
                throw SigningAuthorizationException(e)
            }
            if (!allowed || Thread.currentThread().isInterrupted) throw SigningAuthorizationException()
            return if (crypto != null) {
                crypto.update(data)
                crypto.sign()
            } else {
                val material = if (key is dev.wissam.sshterm.core.keys.DeferredSigningKey) {
                    try {
                        runBlocking { withTimeout(90_000) {
                            key.loadForSignature().also { currentCoroutineContext().ensureActive() }
                        } }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw SigningAuthorizationException(e)
                    } catch (e: Exception) { throw SigningAuthorizationException(e) }
                } else key
                if (Thread.currentThread().isInterrupted) throw SigningAuthorizationException()
                delegate.initSign(material)
                delegate.update(data)
                delegate.sign()
            }
        } finally {
            data.fill(0)
            payload.reset()
            privateKey = null
        }
    }
}
