package dev.wissam.sshterm.ui.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext

/** Shared by one target and its jump chain, including callbacks running on SSHJ reader threads. */
internal class InteractionLease {
    private val lifetime = SupervisorJob()
    suspend fun <T> request(block: suspend () -> T): T {
        val task = CoroutineScope(currentCoroutineContext() + lifetime).async { block() }
        return try { task.await() } finally { task.cancel() }
    }
    fun cancel() { lifetime.cancel() }
}
