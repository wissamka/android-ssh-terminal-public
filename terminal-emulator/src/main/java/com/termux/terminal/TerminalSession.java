package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/*
 * MODIFIED FILE NOTICE (Apache License 2.0, Section 4b):
 * This file was modified from the original Termux terminal-emulator library
 * (https://github.com/termux/termux-app, commit 401bbe54, Apache-2.0).
 * The original class coupled the terminal to a local subprocess via a JNI
 * forkpty() shim. That native layer has been removed entirely; the class is
 * now an abstract, transport-agnostic session. Concrete subclasses provide
 * the byte transport (e.g. an SSH shell channel or a local ProcessBuilder
 * pipe) by implementing startTransport()/onTerminalResized()/terminateTransport()
 * and pumping bytes through emitOutput()/readInput()/reportTransportExited().
 */

/**
 * A terminal session, consisting of an emulator state machine coupled to a byte transport.
 * <p>
 * When the terminal size is made known by a call to {@link #updateSize(int, int, int, int)} terminal emulation
 * begins and {@link #startTransport(int, int, int, int)} is invoked so the subclass can spawn its I/O threads.
 * All terminal emulation and callback methods are performed on the main thread.
 * <p>
 * The transport may be terminated forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public abstract class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_SESSION_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the transport produces output, and read by main thread to
     * process by terminal emulator.
     */
    final ByteQueue mTransportToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the transport.
     */
    final ByteQueue mTerminalToTransportIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToTransportIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Whether the transport has been started by {@link #initializeEmulator(int, int, int, int)}. */
    private boolean mStarted;
    /** Whether the transport has exited. */
    private boolean mExited;

    /** The exit status of the transport. Only valid if {@link #isRunning()} is false. */
    int mExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached transport of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            onTerminalResized(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        synchronized (this) {
            mStarted = true;
        }
        startTransport(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    /**
     * Start the byte transport. Called on the main thread once the emulator is initialized and the terminal size is
     * known. Implementations should spawn their own threads for blocking I/O: read transport output and hand it to
     * {@link #emitOutput(byte[], int, int)}, and loop on {@link #readInput(byte[])} forwarding user input to the
     * transport. When the transport dies, call {@link #reportTransportExited(int)}.
     */
    protected abstract void startTransport(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Inform the transport (e.g. the remote pty via SSH window-change request) of a new terminal size. */
    protected abstract void onTerminalResized(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Forcefully terminate the transport (close the channel / kill the subprocess). */
    protected abstract void terminateTransport();

    /**
     * Queue transport output to be processed by the terminal emulator on the main thread.
     *
     * @return false if the session is closed and the transport read loop should stop.
     */
    protected final boolean emitOutput(byte[] buffer, int offset, int length) {
        if (!mTransportToTerminalIOQueue.write(buffer, offset, length)) return false;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        return true;
    }

    /**
     * Blocking read of user input destined for the transport. Returns the number of bytes read into the buffer, or
     * -1 if the session is closed and the transport write loop should stop.
     */
    protected final int readInput(byte[] buffer) {
        return mTerminalToTransportIOQueue.read(buffer, true);
    }

    /** Report from any thread that the transport has exited, with an exit code (0 for a clean close). */
    protected final void reportTransportExited(int exitCode) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_SESSION_EXITED, exitCode));
    }

    /** The message appended to the terminal when the transport exits. Subclasses may override. */
    protected String getExitDescription(int exitCode) {
        String exitDescription = "\r\n[Session finished";
        if (exitCode > 0) {
            exitDescription += " (code " + exitCode + ")";
        } else if (exitCode < 0) {
            exitDescription += " (signal " + (-exitCode) + ")";
        }
        exitDescription += "]";
        return exitDescription;
    }

    /** Write data to the transport. */
    @Override
    public void write(byte[] data, int offset, int count) {
        synchronized (this) {
            if (!mStarted || mExited) return;
        }
        mTerminalToTransportIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by terminating the transport. */
    public void finishIfRunning() {
        if (isRunning()) {
            terminateTransport();
        }
    }

    /** Cleanup resources when the transport exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mExited = true;
            mExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O queues.
        mTerminalToTransportIOQueue.close();
        mTransportToTerminalIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return !mExited;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onServerRequestedClipboardWrite(String text) {
        mClient.onServerRequestedClipboardWrite(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mTransportToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_SESSION_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                byte[] bytesToWrite = getExitDescription(exitCode).getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
