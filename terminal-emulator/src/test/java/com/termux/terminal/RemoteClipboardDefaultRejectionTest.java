package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests the two production Java defaults directly, without Android Base64, a Looper, or OSC
 * parsing. Android instrumentation separately covers the real SessionManager wrapper and UI.
 */
public class RemoteClipboardDefaultRejectionTest extends TestCase {
    public void testTerminalOutputDefaultRejectsRemoteWritesButPreservesManualCopy() {
        TerminalTestCase.MockTerminalOutput output = new TerminalTestCase.MockTerminalOutput();

        output.onServerRequestedClipboardWrite("remote before manual copy");
        assertTrue("The default must not forward remote writes to manual copy", output.clipboardPuts.isEmpty());

        output.onCopyTextToClipboard("explicit user selection");
        output.onServerRequestedClipboardWrite("remote after manual copy");
        assertEquals(Arrays.asList("explicit user selection"), output.clipboardPuts);
    }

    public void testSessionClientDefaultRejectsRemoteWritesButPreservesManualCopy() {
        ManualCopyClient client = new ManualCopyClient();
        // No TerminalSession is constructed: it requires Android's Looper. This unused token
        // lets the JVM exercise the real default method; neither callback dereferences it.
        client.onServerRequestedClipboardWrite(null, "remote before manual copy");
        assertTrue("The interface default must not invoke manual copy", client.copies.isEmpty());

        client.onCopyTextToClipboard(null, "explicit user selection");
        client.onServerRequestedClipboardWrite(null, "remote after manual copy");
        assertEquals(Arrays.asList("explicit user selection"), client.copies);
    }

    /** Deliberately does NOT override onServerRequestedClipboardWrite. */
    private static final class ManualCopyClient implements TerminalSessionClient {
        final List<String> copies = new ArrayList<>();
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) { copies.add(text); }
        @Override public void onTextChanged(TerminalSession session) {}
        @Override public void onTitleChanged(TerminalSession session) {}
        @Override public void onSessionFinished(TerminalSession session) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception exception) {}
        @Override public void logStackTrace(String tag, Exception exception) {}
    }
}
