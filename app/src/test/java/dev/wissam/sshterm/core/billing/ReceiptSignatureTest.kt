package dev.wissam.sshterm.core.billing

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ReceiptSignatureTest {
    @Test fun signedReceiptRequiredAndTamperingRejected() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifier = ReceiptSignature(Base64.getEncoder().encodeToString(pair.public.encoded))
        val receipt = "{\"productId\":\"test_only\"}"
        val signed = Signature.getInstance("SHA1withRSA").run {
            initSign(pair.private)
            update(receipt.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        assertTrue(verifier.verify(receipt, signed))
        assertFalse(verifier.verify(receipt + " ", signed))
        assertFalse(verifier.verify(receipt, "invalid"))
        assertFalse(verifier.verify(receipt, ""))
    }

    @Test fun unconfiguredAndMalformedKeysFailClosed() {
        for (key in listOf("", "not-a-key", "YWJj")) {
            val verifier = ReceiptSignature(key)
            assertFalse(verifier.configured)
            assertFalse(verifier.verify("{}", ""))
        }
    }
}
