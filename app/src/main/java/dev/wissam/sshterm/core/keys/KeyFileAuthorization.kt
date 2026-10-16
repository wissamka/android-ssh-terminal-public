package dev.wissam.sshterm.core.keys

import javax.crypto.Cipher

/** Exact initialized cipher must be passed to a strong-biometric BiometricPrompt.CryptoObject. */
data class KeyFileAuthorizationRequest(
    val storageId: String,
    val purpose: Purpose,
    val cipher: Cipher,
) {
    enum class Purpose { ENCRYPT_MIGRATION, VERIFY_MIGRATION, DECRYPT }
}

fun interface KeyFileAuthorizer {
    /** A fresh prompt per call; false/cancellation/error must deny. The owner must bind this
     * request to its lifecycle lease and dismiss its prompt on coroutine cancellation.
     * Never consume/replace the cipher: the store uses this exact operation after success.
     */
    suspend fun authorize(request: KeyFileAuthorizationRequest): Boolean
}

class KeyFileAuthorizationRequiredException : IllegalStateException("This key file requires per-operation biometric authorization")
class KeyFileAuthorizationDeniedException : IllegalStateException("Key-file authorization denied")
