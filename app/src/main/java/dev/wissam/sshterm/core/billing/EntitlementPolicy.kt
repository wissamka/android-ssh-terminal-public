package dev.wissam.sshterm.core.billing

enum class BillingAvailability { UNCONFIGURED, CHECKING, AVAILABLE, UNAVAILABLE }

data class Entitlement(
    val pro: Boolean = false,
    val pending: Boolean = false,
    val availability: BillingAvailability = BillingAvailability.UNCONFIGURED,
)

data class FreeTierLimits(val profiles: Int = 3, val sessions: Int = 1) {
    init { require(profiles >= 0 && sessions >= 0) }
}

/** Only a successful, complete ownership query may remove cached ownership. */
object EntitlementPolicy {
    const val FREE_PROFILES = 3
    const val FREE_SESSIONS = 1

    fun reconcile(previous: Entitlement, querySucceeded: Boolean, verifiedPurchased: Boolean,
                  pending: Boolean = false): Entitlement =
        if (!querySucceeded) previous.copy(availability = BillingAvailability.UNAVAILABLE)
        else Entitlement(verifiedPurchased, pending && !verifiedPurchased, BillingAvailability.AVAILABLE)

    fun canCreateProfile(entitlement: Entitlement, existingCount: Int, limits: FreeTierLimits = FreeTierLimits()): Boolean =
        entitlement.pro || existingCount < limits.profiles

    fun canStartSession(entitlement: Entitlement, activeAndConnectingCount: Int, limits: FreeTierLimits = FreeTierLimits()): Boolean =
        entitlement.pro || activeAndConnectingCount < limits.sessions

    // Existing data and running sessions are never conditional on payment.
    fun canEditExistingProfile(): Boolean = true
    fun canAccessKeysAndExport(): Boolean = true
    fun shouldTerminateExistingSession(): Boolean = false

    fun profileLimitMessage(limits: FreeTierLimits) =
        "Free supports ${limits.profiles} saved profiles. Existing profiles remain usable, editable and exportable. Open Settings → Free & Pro to upgrade."
    fun sessionLimitMessage(limits: FreeTierLimits) =
        "Free supports ${limits.sessions} active session(s), including local shells. Return to or close a current session, or open Settings → Free & Pro to upgrade."

    const val PROFILE_LIMIT_MESSAGE =
        "Free supports 3 saved profiles. You can still edit, use or export existing profiles. Pro allows unlimited profiles."
    const val SESSION_LIMIT_MESSAGE =
        "Free supports 1 active session. Return to your current session or close it before starting another. Pro allows unlimited sessions."
}

/** One shared instance per session manager. Reserve BEFORE asynchronous connection begins.
 * Keep the reservation through connection and release on failure/close, never on billing changes.
 * Seed existing IDs when attaching to a manager that already has sessions. Reconnect uses same ID.
 */
class SessionAdmissionGate(
    existingSessionIds: Set<String> = emptySet(),
    private val limits: FreeTierLimits = FreeTierLimits(),
) {
    private val reservations = existingSessionIds.toMutableSet()

    @Synchronized
    fun tryAcquire(sessionId: String, entitlement: Entitlement, enforceLimits: Boolean = true): Boolean {
        require(sessionId.isNotBlank())
        if (sessionId in reservations) return true
        if (enforceLimits && !EntitlementPolicy.canStartSession(entitlement, reservations.size, limits)) return false
        reservations.add(sessionId)
        return true
    }

    @Synchronized
    fun release(sessionId: String) { reservations.remove(sessionId) }
}
