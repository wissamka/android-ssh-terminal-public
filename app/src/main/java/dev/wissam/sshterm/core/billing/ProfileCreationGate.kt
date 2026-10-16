package dev.wissam.sshterm.core.billing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EntitlementLimitException(message: String) : IllegalStateException(message)

/** Shared by the singleton repository: the authoritative count and insert cannot race. */
class ProfileCreationGate {
    private val mutex = Mutex()

    suspend fun <T> create(
        entitlement: () -> Entitlement,
        enforceLimits: Boolean,
        limits: FreeTierLimits,
        count: suspend () -> Int,
        insert: suspend () -> T,
    ): T = mutex.withLock {
        if (enforceLimits && !EntitlementPolicy.canCreateProfile(entitlement(), count(), limits)) {
            throw EntitlementLimitException(EntitlementPolicy.profileLimitMessage(limits))
        }
        insert()
    }
}
