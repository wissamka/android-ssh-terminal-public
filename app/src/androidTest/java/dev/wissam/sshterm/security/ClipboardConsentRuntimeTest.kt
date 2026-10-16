package dev.wissam.sshterm.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalSession
import dev.wissam.sshterm.ui.terminal.UiPrompt
import dev.wissam.sshterm.ui.terminal.TerminalViewModel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardConsentRuntimeTest : SecurityRuntimeFixture() {
    private lateinit var vm: TerminalViewModel
    private lateinit var clipboard: ClipboardManager
    private var previousClip: ClipData? = null
    private var clipboardCaptured = false
    private val clipboardChanges = AtomicInteger()
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipboardChanges.incrementAndGet()
    }
    private val ownedSessions = mutableListOf<Long>()

    private fun openTerminal(): TerminalSession {
        enterAppForUiTest()
        compose.onNodeWithText("Open local shell").performClick()
        scenario.onActivity {
            // AppNav obtains this same activity-scoped production Hilt ViewModel.
            vm = ViewModelProvider(it)[TerminalViewModel::class.java]
            clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            previousClip = clipboard.primaryClip
            clipboardCaptured = true
            clipboard.setPrimaryClip(ClipData.newPlainText("audit sentinel", SENTINEL))
            ownedSessions += vm.sessions.value.map { session -> session.id }
        }
        compose.waitUntil(5_000) {
            vm.sessions.value.singleOrNull()?.session?.emulator != null
        }
        val session = vm.sessions.value.single().session
        compose.runOnIdle {
            assertTrue("Local transport must be running", session.isRunning)
            clipboard.addPrimaryClipChangedListener(clipboardListener)
        }
        return session
    }

    /** Actual OSC parser -> session client -> ViewModel -> consent UI. Never write to stdin. */
    private fun offer(session: TerminalSession, text: String, expectConsent: Boolean = true) {
        val trace = mutableListOf<String>()
        var stage = "before OSC input"
        fun sample(label: String) {
            val state = "request=${vm.clipboardRequest.value}, active=${vm.activeSessionId.value}, " +
                "offeredHandle=${session.mHandle}, running=${session.isRunning}, " +
                "emulator=${session.emulator != null}, ended=${vm.endedSessions.value}, " +
                "prompt=${vm.prompt.value?.javaClass?.simpleName}, writes=${clipboardChanges.get()}"
            val entry = "$label: $state"
            if (trace.lastOrNull() != entry && trace.size < 30) trace += entry
        }
        fun declineBatteryIfShown() {
            if (vm.prompt.value is UiPrompt.BatteryOptimization) {
                sample("battery prompt")
                compose.waitUntil(3_000) { compose.onNodeWithText("Not now").isDisplayed() }
                compose.onNodeWithText("Not now").performClick()
                compose.waitUntil(3_000) { vm.prompt.value !is UiPrompt.BatteryOptimization }
            }
        }
        try {
            declineBatteryIfShown()
            val bytes = ("\u001b]52;c;" + Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP) + "\u0007")
                .toByteArray()
            compose.runOnIdle {
                sample("before append")
                checkNotNull(session.emulator).append(bytes, bytes.size)
                sample("after append")
            }
            // An inactive session is intentionally rejected; never wait for it to gain consent.
            if (!expectConsent) return
            stage = "waiting for production clipboardRequest"
            compose.waitUntil(2_000) {
                declineBatteryIfShown()
                sample("request wait")
                vm.clipboardRequest.value != null
            }
            stage = "waiting for actual Copy snackbar"
            compose.waitUntil(3_000) {
                declineBatteryIfShown()
                sample("snackbar wait")
                compose.onNodeWithText("Copy", substring = false).isDisplayed()
            }
            compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        } catch (failure: Throwable) {
            sample("failure")
            val runtime = runCatching {
                var result = "Activity callback unavailable"
                scenario.onActivity { live ->
                    result = "activitySame=${live === activity}, lifecycle=${live.lifecycle.currentState}, " +
                        "focus=${live.hasWindowFocus()}, locked=${live.appLock.locked.value}, " +
                        "vm=${System.identityHashCode(vm)}, " +
                        "sessions=${vm.sessions.value.map { managed ->
                            "id=${managed.id}/handle=${managed.session.mHandle}/running=${managed.session.isRunning}"
                        }}"
                }
                result
            }.getOrElse { "Runtime snapshot failed: $it" }
            val tree = runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
            }.getOrElse { "Semantics dump failed: $it" }
            throw AssertionError(
                "OSC consent failed while $stage; expectConsent=$expectConsent; chars=${text.length}\n" +
                    "$runtime\nClock autoAdvance=${compose.mainClock.autoAdvance}, " +
                    "time=${compose.mainClock.currentTime}\nTrace:\n${trace.joinToString("\n")}\n" +
                    "Full unmerged semantics:\n$tree",
                failure,
            )
        }
    }

    private fun assertClipboard(text: String) {
        compose.runOnIdle {
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            if (text == SENTINEL) {
                assertEquals("No transient clipboard write/undo is allowed before consent",
                    0, clipboardChanges.get())
            }
        }
    }

    @After fun closeOwnedSessionsAndRestoreClipboard() {
        if (!::vm.isInitialized) return
        scenario.onActivity {
            ownedSessions.forEach(vm::closeSession)
            if (clipboardCaptured) {
                clipboard.removePrimaryClipChangedListener(clipboardListener)
                previousClip?.let(clipboard::setPrimaryClip) ?: run {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    @Test fun osc52CannotMutateClipboardUntilUserPressesCopy() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Server requests copying ${PAYLOAD.length} characters to clipboard")
            .assertIsDisplayed()
        assertClipboard(SENTINEL)
        // A server burst cannot replace the pending payload under the visible consent action.
        offer(session, "second unapproved payload")
        assertClipboard(SENTINEL)
        compose.onNodeWithText("Copy", substring = false).performClick()
        assertClipboard(PAYLOAD)
        compose.runOnIdle { assertNull(vm.clipboardRequest.value) }
    }

    @Test fun explicitDismissalInvalidatesConsentWithoutClipboardMutation() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        val request = checkNotNull(vm.clipboardRequest.value)
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Copy", substring = false).assertDoesNotExist()
        compose.runOnIdle { vm.acceptRemoteClipboardRequest(request.id) }
        assertClipboard(SENTINEL)
    }

    @Test fun snackbarTimeoutNeverMutatesClipboardAndRejectsLateCopy() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        val request = checkNotNull(vm.clipboardRequest.value)
        compose.waitUntil(12_000) { vm.clipboardRequest.value == null }
        compose.runOnIdle { vm.acceptRemoteClipboardRequest(request.id) }
        assertClipboard(SENTINEL)
    }

    @Test fun lockingDisposesConsentAndRejectsItsStaleAction() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        val request = checkNotNull(vm.clipboardRequest.value)
        assertClipboard(SENTINEL)
        backgroundAndReturnWithInstantLock()
        compose.onNodeWithText("Copy", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(vm.clipboardRequest.value)
            vm.acceptRemoteClipboardRequest(request.id)
        }
        assertClipboard(SENTINEL)
    }

    @Test fun switchingActiveSessionRejectsOldConsentAndInactiveOsc52() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        val request = checkNotNull(vm.clipboardRequest.value)
        compose.runOnIdle {
            vm.openLocalShell()
            ownedSessions += vm.sessions.value.last().id
        }
        compose.waitForIdle()
        compose.runOnIdle { vm.acceptRemoteClipboardRequest(request.id) }
        assertClipboard(SENTINEL)
        offer(session, "inactive session attack", expectConsent = false)
        compose.runOnIdle { assertNull(vm.clipboardRequest.value) }
        compose.onNodeWithText("Copy", substring = false).assertDoesNotExist()
        assertClipboard(SENTINEL)
    }

    @Test fun closingSessionRejectsConsentEvenIfActionArrivesLate() {
        val session = openTerminal()
        offer(session, PAYLOAD)
        compose.onNodeWithText("Copy", substring = false).assertIsDisplayed()
        val request = checkNotNull(vm.clipboardRequest.value)
        compose.runOnIdle { vm.closeSession(vm.sessions.value.single().id) }
        compose.waitForIdle()
        compose.runOnIdle { vm.acceptRemoteClipboardRequest(request.id) }
        assertClipboard(SENTINEL)
        compose.runOnIdle { assertNull(vm.clipboardRequest.value) }
    }

    companion object {
        private const val SENTINEL = "existing audit clipboard must survive without consent"
        private const val PAYLOAD = "server-controlled audit clipboard text"
    }
}
