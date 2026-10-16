package dev.wissam.sshterm.security.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.core.keys.KeyFileAuthorizationDeniedException
import java.security.InvalidAlgorithmParameterException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the parent's current no-PIN/no-enrollment emulator before the enrolled suite. */
@RunWith(AndroidJUnit4::class)
class UnenrolledKeystoreRuntimeTest : BiometricKeystoreFixture() {
    override val mode = "noEnrollment"

    @Test fun productionStrongPromptDeniesWithoutEnrollment() = runBlocking {
        assertFalse(actualPrompt(purpose = "Unavailable strong authentication"))
        assertEquals(0, approvals.get())
    }

    @Test fun unavailablePerOperationMigrationPreservesLegacyCiphertext() = runBlocking {
        val (file, plaintext) = newLegacyFile()
        val before = file.readBytes()
        val failure = try {
            files.migrateLegacyAuthorized(file, realCipherAuthorizer())
            null
        } catch (e: Exception) { e }
        assertNotNull("No enrollment must not permit migration", failure)
        assertTrue("Expected unavailable secure-key generation or real prompt denial, got $failure",
            generateSequence<Throwable>(failure) { it.cause }.any {
                it is InvalidAlgorithmParameterException || it is KeyFileAuthorizationDeniedException
            })
        assertEquals(0, approvals.get())
        assertFalse(files.hasAuthBoundEnvelope(file))
        assertFalse(files.hasLegacyRecovery(file))
        assertArrayEquals(before, file.readBytes())
        assertArrayEquals(plaintext, files.decryptFromFile(file))
        plaintext.fill(0)
    }
}
