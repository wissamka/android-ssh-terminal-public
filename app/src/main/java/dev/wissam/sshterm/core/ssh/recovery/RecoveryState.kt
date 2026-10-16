package dev.wissam.sshterm.core.ssh.recovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread facade: failed removals retain durable metadata and are retried on later actions. */
internal class RecoveryState(private val journal: RecoveryJournal) {
    private val pendingRemovals = linkedSetOf<String>()
    private val mutableEnded = MutableStateFlow(journal.snapshot())
    val ended = mutableEnded.asStateFlow()
    private val mutableError = MutableStateFlow<Exception?>(null)
    val persistenceError = mutableError.asStateFlow()

    fun opened(entry: EndedSession) {
        // Admission still requires durable recording. Only removals are best effort.
        journal.record(entry)
        if (entry.profileId != null) {
            pendingRemovals += mutableEnded.value.filter { it.profileId == entry.profileId }.map { it.token }
        }
        retryPendingRemovals()
    }

    fun ended(token: String) {
        mutableEnded.value = (mutableEnded.value + journal.snapshot().filter { it.token == token }).distinctBy { it.token }
        retryPendingRemovals()
    }

    fun dismiss(token: String) {
        pendingRemovals += token
        retryPendingRemovals()
    }

    fun retryPendingRemovals() {
        for (token in pendingRemovals.toList()) {
            try {
                journal.dismiss(token)
                pendingRemovals.remove(token)
                mutableEnded.value = mutableEnded.value.filterNot { it.token == token }
            } catch (failure: Exception) {
                // Keep the stale record visible and expose failure; cleanup callers must continue.
                mutableEnded.value = (mutableEnded.value + journal.snapshot().filter { it.token == token }).distinctBy { it.token }
                mutableError.value = failure
                return
            }
        }
        mutableError.value = null
    }
}
