package dev.wissam.sshterm.core.ssh

import android.content.Context
import android.content.Intent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wissam.sshterm.core.localshell.LocalShellSession
import dev.wissam.sshterm.service.SshForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped registry of live terminal sessions (the tab list). Sessions outlive any particular
 * screen; the foreground service keeps the process (and thus the SSH sockets) alive while at
 * least one session is open.
 */
@Singleton
class SshSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SshEngine,
    private val billing: dev.wissam.sshterm.core.billing.PlayBillingRepository,
    private val profiles: dev.wissam.sshterm.data.repository.ProfileRepository,
    private val recovery: dev.wissam.sshterm.core.ssh.recovery.SessionRecoveryRepository,
) {

    class ManagedSession(
        val id: Long,
        val session: TerminalSession,
        /** Profile this session was opened from; null for the local shell. */
        val profileId: Long?,
        val displayName: String,
        val recoveryToken: String = java.util.UUID.randomUUID().toString(),
        var ended: Boolean = false,
        val serviceGeneration: Long = 0,
    )

    val endedSessions = recovery.ended
    val recoveryPersistenceError = recovery.persistenceError
    fun dismissRecovery(token: String) = recovery.dismiss(token)
    val liveSessionCount: Int get() = _sessions.value.count { !it.ended }

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serviceGeneration = dev.wissam.sshterm.service.ServiceGeneration()
    fun ownsServiceGeneration(generation: Long): Boolean = serviceGeneration.isCurrent(generation)

    private val nextId = AtomicLong(1)
    private val admission = dev.wissam.sshterm.core.billing.SessionAdmissionGate(limits = billing.freeLimits)

    private val _sessions = MutableStateFlow<List<ManagedSession>>(emptyList())
    val sessions: StateFlow<List<ManagedSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    val knownHostsFile: File
        get() = File(context.filesDir, "known_hosts")

    /** Must be called on the main thread (TerminalSession binds to the calling looper). */
    fun openSshSession(
        conn: ResolvedConnection,
        profileId: Long?,
        prompter: HostKeyPrompter,
        client: TerminalSessionClient,
        transcriptRows: Int = TRANSCRIPT_ROWS,
    ): ManagedSession = createSession(profileId) {
        SshTerminalSession(
            engine, conn, knownHostsFile, prompter, transcriptRows, lifecycleClient(client),
            onConnected = {
                if (profileId != null) persistenceScope.launch {
                    // Failure to update recency must not terminate an established connection.
                    runCatching { profiles.touchLastConnected(profileId) }
                }
            },
        )
    }

    fun openLocalShellSession(client: TerminalSessionClient): ManagedSession = createSession(null) {
        LocalShellSession(TRANSCRIPT_ROWS, lifecycleClient(client))
    }

    /** Reserve before construction/registration; neither transport can start before admission.
     * In-place SSH reconnect retains the same reservation until onSessionFinished or close.
     */
    private inline fun createSession(profileId: Long?, factory: () -> TerminalSession): ManagedSession {
        val id = nextId.getAndIncrement()
        if (!admission.tryAcquire(id.toString(), billing.state.value.entitlement, billing.enforcesFreeLimits)) {
            throw dev.wissam.sshterm.core.billing.EntitlementLimitException(
                dev.wissam.sshterm.core.billing.EntitlementPolicy.sessionLimitMessage(billing.freeLimits),
            )
        }
        var session: TerminalSession? = null
        try {
            session = factory()
            return register(id, session, profileId, if (profileId == null) "Terminal" else "SSH")
        } catch (failure: Throwable) {
            admission.release(id.toString())
            session?.finishIfRunning()
            throw failure
        }
    }

    private fun register(id: Long, session: TerminalSession, profileId: Long?, displayName: String): ManagedSession {
        val managed = ManagedSession(id, session, profileId, when (session) {
            is SshTerminalSession -> session.displayName
            is LocalShellSession -> session.displayName
            else -> displayName
        }, serviceGeneration = serviceGeneration.acquire())
        // Commit before transport starts so abrupt process death can be explained on next launch.
        recovery.opened(dev.wissam.sshterm.core.ssh.recovery.EndedSession(managed.recoveryToken, profileId))
        _sessions.value = _sessions.value + managed
        _activeSessionId.value = managed.id
        try { updateService(starting = true) } catch (e: RuntimeException) {
            close(managed.id)
            throw e
        }
        return managed
    }

    fun setActive(id: Long) {
        if (_sessions.value.any { it.id == id }) _activeSessionId.value = id
    }

    fun get(id: Long): ManagedSession? = _sessions.value.firstOrNull { it.id == id }

    fun activeSession(): ManagedSession? = _activeSessionId.value?.let { get(it) }

    /** Terminates the transport and removes the tab. */
    fun close(id: Long) {
        val managed = get(id) ?: return
        managed.session.finishIfRunning()
        remove(id)
    }

    /** Removes a tab whose session has already finished. */
    fun remove(id: Long) {
        admission.release(id.toString())
        get(id)?.let { recovery.closed(it.recoveryToken) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.lastOrNull()?.id
        }
        updateService()
    }

    fun closeAll() {
        _sessions.value.forEach { admission.release(it.id.toString()); it.session.finishIfRunning(); recovery.closed(it.recoveryToken) }
        _sessions.value = emptyList()
        _activeSessionId.value = null
        updateService()
    }

    /** Service destruction/timeout invalidates transports, retaining only reconnect metadata. */
    fun serviceStopped(generation: Long) {
        serviceGeneration.retire(generation)
        val previous = _sessions.value.filter { !it.ended && it.serviceGeneration == generation }
        _sessions.value = _sessions.value.filterNot { !it.ended && it.serviceGeneration == generation }
        if (get(_activeSessionId.value ?: -1) == null) _activeSessionId.value = _sessions.value.lastOrNull()?.id
        previous.forEach {
            admission.release(it.id.toString())
            recovery.ended(it.recoveryToken)
            it.session.finishIfRunning()
        }
    }

    private fun lifecycleClient(client: TerminalSessionClient) = object : TerminalSessionClient by client {
        // Kotlin delegation does not forward this Java default method. Keep OSC 52 on
        // the consent path instead of inheriting a default clipboard implementation.
        override fun onServerRequestedClipboardWrite(session: TerminalSession, text: String) {
            client.onServerRequestedClipboardWrite(session, text)
        }

        override fun onSessionFinished(session: TerminalSession) {
            // TerminalSession delivers this on its main-thread handler.
            _sessions.value.firstOrNull { it.session === session }?.let {
                admission.release(it.id.toString())
                it.ended = true
                recovery.ended(it.recoveryToken)
                updateService()
            }
            client.onSessionFinished(session)
        }
    }

    private fun updateService(starting: Boolean = false) {
        val intent = Intent(context, SshForegroundService::class.java)
        if (liveSessionCount == 0) {
            // A queued onDestroy must never own sessions admitted after this stop request.
            serviceGeneration.retireCurrent()
            context.stopService(intent)
        } else {
            intent.putExtra(SshForegroundService.EXTRA_SESSION_COUNT, liveSessionCount)
            intent.putExtra(SshForegroundService.EXTRA_GENERATION, serviceGeneration.acquire())
            if (starting) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 4000
    }
}
