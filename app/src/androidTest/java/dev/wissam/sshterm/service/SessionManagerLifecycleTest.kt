package dev.wissam.sshterm.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wissam.sshterm.core.billing.PlayBillingRepository
import dev.wissam.sshterm.core.ssh.SshBackendChoice
import dev.wissam.sshterm.core.ssh.SshEngine
import dev.wissam.sshterm.core.ssh.SshSessionManager
import dev.wissam.sshterm.core.ssh.recovery.RecoveryStorage
import dev.wissam.sshterm.core.ssh.recovery.SessionRecoveryRepository
import dev.wissam.sshterm.core.terminal.BaseTerminalSessionClient
import dev.wissam.sshterm.data.db.AppDatabase
import dev.wissam.sshterm.data.repository.ProfileRepository
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real manager and repository, isolated storage, intercepted service intents, lazy (unstarted) shells.
 * Does not launch a service/process or touch any existing app database, keys, or recovery journal.
 */
@RunWith(AndroidJUnit4::class)
class SessionManagerLifecycleTest {
    private class Storage : RecoveryStorage {
        var content = ""
        var fail = false
        override fun read() = content
        override fun write(value: String) {
            if (fail) throw IOException("disk full")
            content = value
        }
    }

    private class ServiceContext(base: Context, private val directory: File) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        var stops = 0
        override fun getNoBackupFilesDir() = directory
        override fun getFilesDir() = directory
        override fun startForegroundService(service: Intent): ComponentName {
            starts += Intent(service)
            return ComponentName(this, SshForegroundService::class.java)
        }
        override fun startService(service: Intent): ComponentName = startForegroundService(service)
        override fun stopService(service: Intent): Boolean { stops++; return true }
    }

    private fun withManager(test: (SshSessionManager, SessionRecoveryRepository, Storage, ServiceContext) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "session-lifecycle-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            instrumentation.runOnMainSync {
                val context = ServiceContext(base, directory)
                val database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
                val storage = Storage()
                val recovery = SessionRecoveryRepository(storage)
                val billing = PlayBillingRepository(context)
                val manager = SshSessionManager(context, SshEngine(SshBackendChoice.SSHJ), billing,
                    ProfileRepository(database.profileDao(), billing), recovery)
                try { test(manager, recovery, storage, context) }
                finally { storage.fail = false; manager.closeAll(); database.close() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun delayedServiceDestructionLeavesReplacementSessionAdmitted() = withManager { manager, _, _, context ->
        val client = object : BaseTerminalSessionClient() {}
        val first = manager.openLocalShellSession(client)
        val oldGeneration = context.starts.last().getLongExtra(SshForegroundService.EXTRA_GENERATION, 0)
        manager.close(first.id)
        assertTrue(context.stops > 0)
        val replacement = manager.openLocalShellSession(client)
        val newGeneration = context.starts.last().getLongExtra(SshForegroundService.EXTRA_GENERATION, 0)
        assertNotEquals(oldGeneration, newGeneration)
        manager.serviceStopped(oldGeneration) // old service's queued onDestroy
        assertSame(replacement, manager.get(replacement.id))
        assertEquals(1, manager.liveSessionCount)
        assertTrue(manager.ownsServiceGeneration(newGeneration))
        manager.serviceStopped(newGeneration) // genuine timeout must still end its own sessions
        assertEquals(0, manager.liveSessionCount)
        val afterTimeout = manager.openLocalShellSession(client)
        manager.serviceStopped(newGeneration) // duplicate destroy after timeout
        assertSame(afterTimeout, manager.get(afterTimeout.id))
    }

    @Test fun failedRecoveryRemovalCannotAbortCloseOrCloseAll() = withManager { manager, recovery, storage, context ->
        val client = object : BaseTerminalSessionClient() {}
        val first = manager.openLocalShellSession(client)
        storage.fail = true
        manager.close(first.id)
        assertNull(manager.get(first.id))
        assertEquals(0, manager.liveSessionCount)
        assertTrue(context.stops > 0)
        assertNotNull(recovery.persistenceError.value)
        manager.dismissRecovery(first.recoveryToken) // repeated failing UI dismissal is safe
        storage.fail = false
        val second = manager.openLocalShellSession(client) // admission released and retries old removal
        assertFalse(storage.content.contains(first.recoveryToken))
        storage.fail = true
        manager.closeAll()
        assertTrue(manager.sessions.value.isEmpty())
        assertNull(manager.activeSessionId.value)
        assertTrue(storage.content.contains(second.recoveryToken))
        storage.fail = false
        recovery.retryPendingRemovals()
        assertEquals("", storage.content)
        assertTrue(recovery.ended.value.isEmpty())
        assertNull(recovery.persistenceError.value)
    }
}
