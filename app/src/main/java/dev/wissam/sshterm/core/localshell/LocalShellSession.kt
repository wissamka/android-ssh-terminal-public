package dev.wissam.sshterm.core.localshell

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.IOException

/**
 * Deliberately minimal local fallback shell: /system/bin/sh with plain pipes, no PTY.
 *
 * Known limitation (documented, not solved, per project scope): without a pty there is no job
 * control, no line editing from the shell itself, and full-screen programs won't render. The
 * primary path is SSH, where the pty lives on the server.
 */
class LocalShellSession(
    transcriptRows: Int,
    client: TerminalSessionClient,
) : TerminalSession(transcriptRows, client) {

    val displayName: String = "local sh"

    @Volatile private var process: Process? = null

    override fun startTransport(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val proc = try {
            ProcessBuilder("/system/bin/sh", "-i")
                .redirectErrorStream(true)
                .apply {
                    environment()["TERM"] = "dumb"
                    environment()["HOME"] = System.getenv("HOME") ?: "/"
                }
                .start()
        } catch (e: IOException) {
            val msg = "\r\n[Failed to start /system/bin/sh: ${e.message}]\r\n".toByteArray()
            emitOutput(msg, 0, msg.size)
            reportTransportExited(1)
            return
        }
        process = proc

        Thread({
            val buffer = ByteArray(4096)
            try {
                val stream = proc.inputStream
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (!emitOutput(buffer, 0, read)) break
                }
            } catch (e: IOException) {
                // Shutting down.
            }
            val exit = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                1
            }
            reportTransportExited(exit)
        }, "LocalShellReader").start()

        Thread({
            val buffer = ByteArray(4096)
            try {
                val stream = proc.outputStream
                while (true) {
                    val length = readInput(buffer)
                    if (length == -1) return@Thread
                    // Terminal keyboards send CR for Enter; a real pty's ICRNL would translate
                    // it, but this is a plain pipe, so translate here or the shell never sees
                    // a completed line.
                    for (i in 0 until length) {
                        if (buffer[i] == '\r'.code.toByte()) buffer[i] = '\n'.code.toByte()
                    }
                    stream.write(buffer, 0, length)
                    stream.flush()
                }
            } catch (e: IOException) {
                // Shutting down.
            }
        }, "LocalShellWriter").start()
    }

    override fun onTerminalResized(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // No pty, so nothing to inform. Programs that query terminal size get stty defaults.
    }

    override fun terminateTransport() {
        process?.destroyForcibly()
    }

    override fun getExitDescription(exitCode: Int): String =
        if (exitCode == 0) "\r\n[Shell exited]" else "\r\n[Shell exited (code $exitCode)]"
}
