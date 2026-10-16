package dev.wissam.sshterm.security

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.ui.security.showAuthPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowAndLockRuntimeTest : SecurityRuntimeFixture() {
    @Test fun initialLockAndUnavailableAuthenticationRemainFailClosed() {
        assertActivitySecure()
        scenario.onActivity { assertTrue(it.appLock.locked.value) }
        compose.onNodeWithContentDescription("SSH keys").assertDoesNotExist()
        compose.onNodeWithContentDescription("Quick connect").assertDoesNotExist()
        compose.onNodeWithText("Get started").assertDoesNotExist()
        repeat(2) { compose.onNodeWithText("Unlock").performClick() }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        val results = mutableListOf<Boolean>()
        scenario.onActivity {
            val operation = showAuthPrompt(it, "Audit unavailable authentication") { result ->
                results += result
            }
            assertTrue(operation.isComplete)
            assertTrue(it.appLock.locked.value)
        }
        assertEquals(listOf(false), results)
        assertActivitySecure()
    }

    @Test fun secureFlagSurvivesUnlockedUiRecreationAndInstantBackgroundLock() {
        assertActivitySecure()
        enterAppForUiTest()
        assertActivitySecure()
        scenario.recreate()
        scenario.onActivity { activity = it }
        assertActivitySecure()
        backgroundAndReturnWithInstantLock()
        compose.onNodeWithContentDescription("SSH keys").assertDoesNotExist()
        compose.onNodeWithContentDescription("New profile").assertDoesNotExist()
    }

    @Test fun importDialogIsSecureAndItsWindowIsDisposedOnLock() {
        enterAppForUiTest()
        compose.onNodeWithContentDescription("SSH keys").performClick()
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Paste key (OpenSSH / PEM / PuTTY)")
            .performTextInput("audit-only unsaved sensitive text")
        val window = assertDialogSecure()
        backgroundAndReturnWithInstantLock()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Import private key").assertDoesNotExist()
        compose.onNodeWithText("audit-only unsaved sensitive text").assertDoesNotExist()
        compose.runOnIdle { assertFalse("Dialog window must be detached, not covered", window.isAttachedToWindow) }
        // Reopening cannot recover the disposed plaintext editor via remembered navigation.
        enterAppForUiTest()
        compose.onNodeWithContentDescription("SSH keys").performClick()
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("audit-only unsaved sensitive text").assertDoesNotExist()
        assertDialogSecure()
    }

    @Test fun realConnectionPasswordDialogIsSecureAndDisposedBeforeAnyConnection() {
        enterAppForUiTest()
        compose.onNodeWithContentDescription("Quick connect").performClick()
        compose.onNodeWithText("user@host or user@host:port")
            .performTextInput("audit@127.0.0.1:1")
        assertDialogSecure()
        // This only enqueues the production password prompt. Never submit its Connect action:
        // no credentials, socket, server, or VPS is needed or contacted by this test.
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Password", substring = false).assertIsDisplayed()
        val window = assertDialogSecure()
        backgroundAndReturnWithInstantLock()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Password", substring = false).assertDoesNotExist()
        compose.runOnIdle { assertFalse(window.isAttachedToWindow) }
    }
}
