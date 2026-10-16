package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/*
 * MODIFIED FILE NOTICE (Apache License 2.0, Section 4b):
 * Modified from the original Termux terminal-emulator library: the
 * setTerminalShellPid() callback was removed along with the local-subprocess
 * PTY layer, since sessions are now transport-agnostic (see TerminalSession).
 * Also added onServerRequestedClipboardWrite so the app can distinguish
 * user-initiated selection copies from remote OSC 52 writes.
 */

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    /**
     * Fired when the remote end (OSC 52) asked to overwrite the system clipboard. Distinct from
     * {@link #onCopyTextToClipboard} which handles user-initiated selection copies. Default
     * behavior rejects the request unless a client explicitly provides a consent policy.
     */
    default void onServerRequestedClipboardWrite(@NonNull TerminalSession session, String text) {
        // Fail closed: never treat remote output as a user-initiated copy.
    }

    void onPasteTextFromClipboard(@Nullable TerminalSession session);

    void onBell(@NonNull TerminalSession session);

    void onColorsChanged(@NonNull TerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
