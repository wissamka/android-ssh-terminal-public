package dev.wissam.sshterm.ui.terminal

import org.junit.Assert.*
import org.junit.Test

class CommandInputPolicyTest {
    @Test fun terminalFocusNeverRoutesToDropdown() {
        listOf("Up", "Down", "Enter").forEach {
            assertEquals(CommandInputAction.PASS, commandInputAction(it, false, true, 0, 3))
        }
    }
    @Test fun enterCommitsOnlyVisibleValidSelection() {
        assertEquals(CommandInputAction.COMMIT, commandInputAction("Enter", true, true, 1, 3))
        assertEquals(CommandInputAction.SEND, commandInputAction("Enter", true, false, 1, 3))
        assertEquals(CommandInputAction.SEND, commandInputAction("Enter", true, true, -1, 3))
        assertEquals(CommandInputAction.SEND, commandInputAction("Enter", true, true, 3, 3))
    }
    @Test fun arrowsNavigateAndOtherKeysPassThrough() {
        assertEquals(CommandInputAction.PREVIOUS, commandInputAction("Up", true, false, -1, 3))
        assertEquals(CommandInputAction.NEXT, commandInputAction("Down", true, true, 0, 3))
        assertEquals(CommandInputAction.PASS, commandInputAction("Left", true, true, 0, 3))
    }
    @Test fun selectionStartsAtEndsAndWraps() {
        assertEquals(2, nextSuggestionIndex(-1, -1, 3))
        assertEquals(0, nextSuggestionIndex(-1, 1, 3))
        assertEquals(2, nextSuggestionIndex(0, -1, 3))
        assertEquals(0, nextSuggestionIndex(2, 1, 3))
        assertEquals(-1, nextSuggestionIndex(-1, 1, 0))
    }
    @Test fun armedModifiersAffectFirstCodePointOnly() {
        assertEquals("\u001b\u0003at", modifiedTerminalText("cat", StickyState.ARMED, StickyState.ARMED))
        assertEquals("\u001b😀x", modifiedTerminalText("😀x", StickyState.OFF, StickyState.ARMED))
    }
    @Test fun lockedModifiersPersistAndCombine() {
        assertEquals("\u001b\u0003\u001b\u0001", modifiedTerminalText("Ca", StickyState.LOCKED, StickyState.LOCKED))
        assertEquals("\u001b\u0003\u001bA", modifiedTerminalText("cA", StickyState.ARMED, StickyState.LOCKED))
    }
    @Test fun symbolsMatchTerminalControlMapping() {
        val symbols = listOf(" ", "2", "[", "3", "\\", "4", "]", "5", "^", "6", "_", "7", "/", "8")
        val codes = listOf(0, 0, 27, 27, 28, 28, 29, 29, 30, 30, 31, 31, 31, 127)
        symbols.zip(codes).forEach { (symbol, code) ->
            assertEquals(code.toChar().toString(), modifiedTerminalText(symbol, StickyState.ARMED, StickyState.OFF))
        }
        assertEquals("-", modifiedTerminalText("-", StickyState.ARMED, StickyState.OFF))
        assertEquals("", modifiedTerminalText("", StickyState.LOCKED, StickyState.LOCKED))
        assertEquals("é😀", modifiedTerminalText("é😀", StickyState.OFF, StickyState.OFF))
    }
    @Test fun stickyInsertionSendsOnlyNewCharacterAndPreservesExistingBuffer() {
        assertEquals("\u0003", stickyCommandInsertion("sleep 10", "sleep 10c", StickyState.ARMED, StickyState.OFF))
        assertEquals("\u001b\u0003", stickyCommandInsertion("echo hi", "echco hi", StickyState.ARMED, StickyState.LOCKED))
        assertEquals("\u0003", stickyCommandInsertion("ccc", "cccc", StickyState.LOCKED, StickyState.OFF))
        assertNull(stickyCommandInsertion("sleep 10", "sleep 10c", StickyState.OFF, StickyState.OFF))
    }

    @Test fun pasteReplacementDeletionAndCompositionDoNotBecomeControlStreams() {
        for (after in listOf("bufferpaste", "replacement", "buffe", "buffer\n", "buffer")) {
            assertNull(stickyCommandInsertion("buffer", after, StickyState.ARMED, StickyState.LOCKED))
        }
        assertEquals("\u0003", stickyCommandInsertion("buffer", "bufferc", StickyState.ARMED, StickyState.OFF, composing = true))
        assertNull(stickyCommandInsertion("buffer", "bufferé", StickyState.ARMED, StickyState.OFF, composing = true))
        assertNull(stickyCommandInsertion("", "e\u0301", StickyState.ARMED, StickyState.LOCKED))
    }

    @Test fun unicodeInsertionUsesWholeCodePointAndRejectsPartialSurrogates() {
        assertEquals("\u001b😀", stickyCommandInsertion("a😀b", "a😀😀b", StickyState.OFF, StickyState.ARMED))
        assertEquals("é", stickyCommandInsertion("", "é", StickyState.ARMED, StickyState.OFF))
        assertNull(stickyCommandInsertion("", "\uD83D", StickyState.OFF, StickyState.ARMED))
        assertNull(stickyCommandInsertion("", "😀x", StickyState.OFF, StickyState.LOCKED))
    }

    @Test fun nativeCodePointsMatchSoftInsertionAndDeadKeysAreNotSent() {
        assertEquals("\u001b\u0003", modifiedCommandCodePoint('c'.code, StickyState.ARMED, StickyState.LOCKED))
        assertEquals("\u001b😀", modifiedCommandCodePoint(0x1f600, StickyState.OFF, StickyState.LOCKED))
        for (cp in listOf(0, -1, 0xD800, 0x110000, Int.MIN_VALUE or 0x60)) {
            assertNull(modifiedCommandCodePoint(cp, StickyState.ARMED, StickyState.ARMED))
        }
    }
}
