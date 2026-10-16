package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/*
 * MODIFIED FILE NOTICE (Apache License 2.0, Section 4b):
 * Added onServerRequestedClipboardWrite so callers can distinguish user-initiated
 * selection copies from remote OSC 52 writes (server-driven) and apply different
 * policy to the latter (e.g. show a snackbar with undo).
 */
/** A client which receives callbacks from events triggered by feeding input to a {@link TerminalEmulator}. */
public abstract class TerminalOutput {

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /**
     * Notify the client that the remote end asked to overwrite the system clipboard (OSC 52).
     * Distinct from {@link #onCopyTextToClipboard(String)}, which is used for user-initiated
     * selection copies — server-driven writes are a known attack vector and clients typically
     * must obtain consent before writing. The default rejects the request.
     */
    public void onServerRequestedClipboardWrite(String text) {
        // Fail closed: only an explicit consent-aware client may handle remote writes.
    }

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

}
