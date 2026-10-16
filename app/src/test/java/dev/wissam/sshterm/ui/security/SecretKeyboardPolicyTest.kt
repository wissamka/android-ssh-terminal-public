package dev.wissam.sshterm.ui.security

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.*
import org.junit.Test

class SecretKeyboardPolicyTest {
    @Test fun secretsDefaultToPasswordInputWithoutCorrectionOrCapitalization() {
        // No caller flag is necessary for passwords, import text, or export passphrases.
        val options = secretKeyboardOptions()
        assertEquals(KeyboardType.Password, options.keyboardType)
        assertEquals(false, options.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
    }

    @Test fun nonEchoChallengeUsesTheSameSecretPolicy() {
        assertEquals(secretKeyboardOptions(), secretKeyboardOptions(echo = false))
        assertEquals(KeyboardType.Password, secretKeyboardOptions(echo = false).keyboardType)
    }

    @Test fun onlyExplicitEchoUsesOrdinaryKeyboardDefaults() {
        assertEquals(KeyboardOptions.Default, secretKeyboardOptions(echo = true))
        assertNotEquals(KeyboardType.Password, secretKeyboardOptions(echo = true).keyboardType)
    }
}
