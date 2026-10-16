package dev.wissam.sshterm.security

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real production import dialog -> focused Compose view -> Android input connection.
 * Uses the existing disposable-emulator fixture; no real keys or passwords are entered.
 */
@RunWith(AndroidJUnit4::class)
class SecretEditorInfoRuntimeTest : SecurityRuntimeFixture() {
    @Test fun focusedImportPassphraseAdvertisesPasswordWithoutAutoCorrectToIme() {
        enterAppForUiTest()
        compose.onNodeWithContentDescription("SSH keys").performClick()
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Passphrase (if the key has one)")
            .performClick()
            .assertIsFocused()
        compose.waitForIdle()

        // The dialog has its own window. Reading the activity decor or policy helper would
        // miss the actual editor, so inspect the focused view in the dialog's Android root.
        onView(isRoot()).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Inspect focused secret editor's Android EditorInfo"
            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
                val focused = view.findFocus()
                assertNotNull("Production dialog must own a focused Android view", focused)
                val info = EditorInfo()
                val connection = focused!!.onCreateInputConnection(info)
                assertNotNull("Focused field must expose a real input connection", connection)
                // Do not close this connection: it belongs to the live editor/IME lifecycle.
                assertEquals("IME must receive text input", InputType.TYPE_CLASS_TEXT,
                    info.inputType and InputType.TYPE_MASK_CLASS)
                assertEquals("Visual masking alone is insufficient", InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    info.inputType and InputType.TYPE_MASK_VARIATION)
                assertEquals("Secrets must not request IME autocorrection", 0,
                    info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            }
        })
        compose.onNodeWithText("Cancel").performClick()
    }
}
