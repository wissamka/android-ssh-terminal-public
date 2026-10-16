package dev.wissam.sshterm.ui.security

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** Visual masking alone does not mark input as a password for the Android IME.
 * Use for passphrases and private-key text as well as non-echo server challenges.
 * These hints protect against ordinary keyboard learning, not a malicious keyboard.
 */
internal fun secretKeyboardOptions(echo: Boolean = false): KeyboardOptions =
    if (echo) KeyboardOptions.Default else KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )
