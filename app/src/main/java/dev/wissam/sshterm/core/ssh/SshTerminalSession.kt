package dev.wissam.sshterm.core.ssh

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.wissam.sshterm.core.ssh.SshEngine.Chain
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A [TerminalSession] whose transport is an SSH shell channel with a remote pty (allocated via the
 * protocol's pty-req, so vim/htop/tmux fidelity is the server's job, not ours).
 *
 * Survives network flaps: when the link dies without a clean remote exit, the connector loop keeps
 * the emulator (and thus scrollback) alive and transparently reconnects with backoff. Only a clean
 * remote exit, a fatal error (auth/host-key refusal), exhausted retries, or the user closing the
 * tab ends the session.
 */
class SshTerminalSession(
    private val engine: SshEngine,
    private val conn: ResolvedConnection,
    private val knownHostsFile: File,
    private val prompter: HostKeyPrompter,
    transcriptRows: Int,
    client: TerminalSessionClient,
    private val onConnected: () -> Unit = {},
) : TerminalSession(transcriptRows, client) {

    private val _state = MutableStateFlow(SessionState.CONNECTING)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Per-forward status, keyed by the rule id; repopulated on each (re)connect. */
    private val _forwardStatus = MutableStateFlow<Map<Long, PortForwardManager.ForwardStatus>>(emptyMap())
    val forwardStatus: StateFlow<Map<Long, PortForwardManager.ForwardStatus>> = _forwardStatus.asStateFlow()

    val displayName: String = "${conn.username}@${conn.host}"

    private val resources = LifecycleResources()
    private val promptsCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val cancellationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var closedByUser = false
    @Volatile private var connectorThread: Thread? = null
    @Volatile private var chain: Chain? = null
    @Volatile private var shell: SshShell? = null
    @Volatile private var shellOut: OutputStream? = null
    @Volatile private var forwards: PortForwardManager? = null

    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var widthPx = 0
    @Volatile private var heightPx = 0

    /** Serializes channel resize operations off the main thread. */
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SshControl[$displayName]").apply { isDaemon = true }
    }

    override fun startTransport(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (closedByUser) return
        this.cols = columns
        this.rows = rows
        this.widthPx = columns * cellWidthPixels
        this.heightPx = rows * cellHeightPixels
        connectorThread = Thread({
            try { connectorLoop() } finally { finish(if (closedByUser) 0 else 1) }
        }, "SshConnector[$displayName]").also { it.start() }
        Thread({ writerLoop() }, "SshWriter[$displayName]").start()
    }

    override fun onTerminalResized(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        this.cols = columns
        this.rows = rows
        this.widthPx = columns * cellWidthPixels
        this.heightPx = rows * cellHeightPixels
        val s = shell ?: return
        // execute() throws RejectedExecutionException once the executor is shut down (after
        // finish()); a late resize/teardown submission is a no-op, not a crash.
        runCatching {
            controlExecutor.execute {
                runCatching { s.changeWindowDimensions(columns, rows, this.widthPx, this.heightPx) }
            }
        }
    }

    override fun finishIfRunning() { terminateTransport() }

    override fun terminateTransport() {
        if (!cancellationStarted.compareAndSet(false, true)) return
        closedByUser = true
        cancelConnectionPrompts()
        // If the connector is blocked in a host-key prompt (runBlocking), interrupting it cancels
        // that prompt and aborts engine.connect(), which closes any half-open clients itself.
        // Otherwise the thread stays parked on the dialog until the user answers it.
        connectorThread?.interrupt()
        resources.seal()
        // Seal ownership synchronously; blocking resource closes run off the main thread.
        Thread({ resources.releaseAll(permanently = true); finish(0) }, "SshClose").apply {
            isDaemon = true
            start()
        }
    }

    override fun getExitDescription(exitCode: Int): String {
        return if (exitCode == 0) "\r\n[Connection closed]" else "\r\n[Connection closed (code $exitCode)]"
    }

    private fun connectorLoop() {
        var everConnected = false
        var attempt = 0

        while (true) {
            if (closedByUser) {
                finish(0)
                return
            }

            val currentChain: Chain
            val currentShell: SshShell
            try {
                currentChain = engine.connect(conn, knownHostsFile, prompter)
                if (!resources.own { currentChain.closeAll() }) { finish(0); return }
                if (closedByUser) { teardownConnection(); finish(0); return }
                currentShell = currentChain.target.openShell(conn.termType, cols, rows, widthPx, heightPx)
                if (!resources.own { currentShell.close() }) { finish(0); return }
            } catch (e: SshFatalException) {
                teardownConnection()
                emitStatus("${e.message}")
                finish(1)
                return
            } catch (e: Exception) {
                teardownConnection()
                if (closedByUser) {
                    finish(0)
                    return
                }
                if (!everConnected) {
                    emitStatus("Connection failed: ${e.message}")
                    finish(1)
                    return
                }
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    emitStatus("Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts: ${e.message}")
                    finish(1)
                    return
                }
                resources.ifOpen { _state.value = SessionState.RECONNECTING }
                emitStatus("Connection lost, reconnecting (attempt $attempt)…")
                try {
                    Thread.sleep(backoffMillis(attempt))
                } catch (ie: InterruptedException) {
                    finish(0)
                    return
                }
                continue
            }

            if (closedByUser) { teardownConnection(); finish(0); return }
            if (!resources.ifOpen {
                chain = currentChain
                shell = currentShell
                shellOut = currentShell.outputStream
                _state.value = SessionState.CONNECTED
            }) { teardownConnection(); finish(0); return }
            currentChain.target.negotiatedHybrid?.let { emitStatus("MINA authenticated hybrid KEX: $it") }
            if (!everConnected) runCatching(onConnected)
            if (everConnected) emitStatus("Reconnected.")
            everConnected = true
            attempt = 0

            if (conn.forwards.isNotEmpty()) {
                startForwards(currentChain)
            }

            // Pump remote output into the emulator until the channel dies.
            val buffer = ByteArray(8192)
            try {
                val input = currentShell.inputStream
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (!emitOutput(buffer, 0, read)) break
                }
            } catch (e: IOException) {
                // Link dropped mid-read; handled below.
            }

            val termination = runCatching { currentShell.termination }.getOrDefault(SshTermination.TransportLost)
            val exitStatus = (termination as? SshTermination.Exited)?.code
            shellOut = null
            shell = null
            teardownConnection()

            if (closedByUser) {
                finish(exitStatus ?: 0)
                return
            }
            if (termination is SshTermination.Signalled) {
                emitStatus("Remote shell terminated by signal: ${termination.signal}")
                finish(1)
                return
            }
            if (!termination.shouldReconnect) {
                // Clean remote exit (user typed `exit`, shell died, server closed the channel
                // in an orderly way): do not reconnect.
                finish(exitStatus ?: 1)
                return
            }
            // No exit status: the transport died underneath us (wifi<->cellular handoff, NAT
            // timeout). Loop and reconnect, preserving emulator state and scrollback.
            resources.ifOpen { _state.value = SessionState.RECONNECTING }
        }
    }

    /** Forwards user keystrokes to whatever shell channel is currently live. */
    private fun writerLoop() {
        val buffer = ByteArray(4096)
        while (true) {
            val length = readInput(buffer)
            if (length == -1) return // Session closed.
            val out = shellOut ?: continue // Disconnected: drop input rather than block.
            try {
                out.write(buffer, 0, length)
                out.flush()
            } catch (e: IOException) {
                // Channel died between the null-check and the write; connector loop handles it.
            }
        }
    }

    private fun startForwards(chain: Chain) {
        val manager = PortForwardManager(chain.target) { spec, status, error ->
            _forwardStatus.update { it + (spec.key to status) }
            when (status) {
                PortForwardManager.ForwardStatus.ACTIVE -> emitStatus("Forward active: ${spec.label}")
                PortForwardManager.ForwardStatus.FAILED ->
                    emitStatus("Forward failed: ${spec.label}${error?.let { " ($it)" } ?: ""}")
                PortForwardManager.ForwardStatus.CLOSED -> {}
            }
        }
        // Publish the manager before starting so a concurrent close (control executor) can't
        // start-then-orphan it; PortForwardManager.start() itself bails if stop() already ran.
        if (!resources.own { manager.stop() }) return
        forwards = manager
        manager.start(conn.forwards)
    }

    private fun teardownConnection() {
        resources.releaseAll()
        forwards = null
        _forwardStatus.value = emptyMap()
        chain = null
        shellOut = null
        shell = null
    }

    private fun finish(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        closedByUser = true
        cancelConnectionPrompts()
        resources.seal()
        teardownConnection()
        _state.value = SessionState.DISCONNECTED
        // A tab can be closed before a view ever creates its emulator.
        if (emulator != null) reportTransportExited(exitCode)
        controlExecutor.shutdown()
    }

    private fun cancelConnectionPrompts() {
        if (!promptsCancelled.compareAndSet(false, true)) return
        var connection: ResolvedConnection? = conn
        while (connection != null) {
            val current = connection
            runCatching { current.cancelPrompts() }
            connection = current.jumpHost
        }
    }

    private fun emitStatus(message: String) {
        val bytes = "\r\n\u001B[90m[SSH Term: $message]\u001B[0m\r\n".toByteArray(StandardCharsets.UTF_8)
        emitOutput(bytes, 0, bytes.size)
    }

    private fun backoffMillis(attempt: Int): Long = minOf(30_000L, 1_000L shl minOf(attempt, 5))

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 8
    }
}
