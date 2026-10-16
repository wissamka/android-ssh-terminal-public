package dev.wissam.sshterm.security

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.ui.terminal.StickyState
import dev.wissam.sshterm.ui.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production field and activity VM. Restricted by SecurityRuntimeFixture to disposable audit emulators. */
@RunWith(AndroidJUnit4::class)
class AndroidCommandInputRuntimeTest : SecurityRuntimeFixture() {
    private lateinit var vm: TerminalViewModel
    private val ownedSessions = mutableListOf<Long>()
    private fun field() = compose.onNode(hasSetTextAction())

    private fun openCommandField() {
        enterAppForUiTest()
        compose.onNodeWithText("Open local shell").apply {
            if (!isDisplayed()) performScrollTo()
        }.performClick()
        scenario.onActivity {
            vm = ViewModelProvider(it)[TerminalViewModel::class.java]
            ownedSessions += vm.sessions.value.map { session -> session.id }
        }
        compose.waitUntil(5_000) { vm.sessions.value.singleOrNull()?.session?.emulator != null }
        field().performClick()
    }

    @After fun closeOwnedSessions() {
        if (::vm.isInitialized) scenario.onActivity {
            vm.stickyCtrl.value = StickyState.OFF
            vm.stickyAlt.value = StickyState.OFF
            ownedSessions.forEach(vm::closeSession)
        }
    }

    @Test fun arrowsSelectAndPhysicalEnterCommitsWithoutSending() {
        openCommandField()
        field().performTextInput("p")
        compose.waitUntil(5_000) { vm.suggestions.value.isNotEmpty() }
        field().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertEquals(0, vm.selectedSuggestion.value) }
        field().performKeyInput { pressKey(Key.DirectionUp) }
        var selected = ""
        compose.runOnIdle {
            assertEquals(vm.suggestions.value.lastIndex, vm.selectedSuggestion.value)
            selected = vm.suggestions.value[vm.selectedSuggestion.value].command
        }
        field().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("Commit must retain command for editing, not send and clear it", selected, vm.inputText.value)
        }
        field().assertTextEquals(selected)
    }

    @Test fun imeSendCommitsSelectedSuggestionWithoutExecuting() {
        openCommandField()
        field().performTextInput("p")
        compose.waitUntil(5_000) { vm.suggestions.value.isNotEmpty() }
        field().performKeyInput { pressKey(Key.DirectionDown) }
        var selected = ""
        compose.runOnIdle { selected = vm.suggestions.value[vm.selectedSuggestion.value].command }
        field().performImeAction()
        compose.runOnIdle { assertEquals(selected, vm.inputText.value) }
    }

    @Test fun softStickyKeyConsumesArmedAndPreservesBufferedCommand() {
        openCommandField()
        field().performTextInput("# audit-buffer")
        compose.runOnIdle { vm.stickyCtrl.value = StickyState.ARMED }
        field().performTextInput("c")
        compose.runOnIdle {
            assertEquals("# audit-buffer", vm.inputText.value)
            assertEquals(StickyState.OFF, vm.stickyCtrl.value)
        }
        field().assertTextEquals("# audit-buffer")
        field().performTextInput("x")
        compose.runOnIdle { assertEquals("# audit-bufferx", vm.inputText.value) }
    }

    @Test fun nativeStickyKeyDoesNotAlsoInsertAndLockedSurvivesPaste() {
        openCommandField()
        field().performTextInput("# audit-buffer")
        compose.runOnIdle { vm.stickyAlt.value = StickyState.LOCKED }
        field().performKeyInput { pressKey(Key.C) }
        field().assertTextEquals("# audit-buffer")
        compose.runOnIdle {
            assertEquals("# audit-buffer", vm.inputText.value)
            assertEquals(StickyState.LOCKED, vm.stickyAlt.value)
        }
        field().performTextInput("😀paste")
        compose.runOnIdle {
            assertEquals("# audit-buffer😀paste", vm.inputText.value)
            assertEquals(StickyState.LOCKED, vm.stickyAlt.value)
        }
    }
}
