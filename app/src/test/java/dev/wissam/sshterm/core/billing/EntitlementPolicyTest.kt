package dev.wissam.sshterm.core.billing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EntitlementPolicyTest {
    private val free = Entitlement()
    private val pro = Entitlement(pro = true)

    @Test fun freeBoundariesAndOverLimitData() {
        assertTrue(EntitlementPolicy.canCreateProfile(free, 2))
        assertFalse(EntitlementPolicy.canCreateProfile(free, 3))
        assertFalse(EntitlementPolicy.canCreateProfile(free, 100))
        assertTrue(EntitlementPolicy.canEditExistingProfile())
        assertTrue(EntitlementPolicy.canAccessKeysAndExport())
        assertFalse(EntitlementPolicy.shouldTerminateExistingSession())
        assertTrue(EntitlementPolicy.canStartSession(free, 0))
        assertFalse(EntitlementPolicy.canStartSession(free, 1))
    }

    @Test fun proposedLimitsCanBeConfiguredWithoutGrantingPro() {
        val limits = FreeTierLimits(profiles = 5, sessions = 2)
        assertTrue(EntitlementPolicy.canCreateProfile(free, 4, limits))
        assertFalse(EntitlementPolicy.canCreateProfile(free, 5, limits))
        val gate = SessionAdmissionGate(limits = limits)
        assertTrue(gate.tryAcquire("one", free))
        assertTrue(gate.tryAcquire("two", free))
        assertFalse(gate.tryAcquire("three", free))
    }

    @Test fun proHasNoCountCap() {
        assertTrue(EntitlementPolicy.canCreateProfile(pro, Int.MAX_VALUE))
        assertTrue(EntitlementPolicy.canStartSession(pro, Int.MAX_VALUE))
    }

    @Test fun restoreGrantsOnlyVerifiedPurchased() {
        assertTrue(EntitlementPolicy.reconcile(free, true, true).pro)
        val pending = EntitlementPolicy.reconcile(free, true, false, true)
        assertFalse(pending.pro)
        assertTrue(pending.pending)
        assertFalse(EntitlementPolicy.reconcile(pending, true, false).pending)
    }

    @Test fun outagePreservesOfflineProButNeverGrantsFreeUsersPro() {
        val offline = EntitlementPolicy.reconcile(pro, false, false)
        assertTrue(offline.pro)
        assertEquals(BillingAvailability.UNAVAILABLE, offline.availability)
        assertFalse(EntitlementPolicy.reconcile(free, false, true).pro)
    }

    @Test fun successfulEmptyQueryRevokesCachedPro() {
        val revoked = EntitlementPolicy.reconcile(pro, true, false)
        assertFalse(revoked.pro)
        assertEquals(BillingAvailability.AVAILABLE, revoked.availability)
        assertFalse(EntitlementPolicy.shouldTerminateExistingSession())
    }

    @Test fun failedAndClosedConnectionsReleaseReservation() {
        val gate = SessionAdmissionGate()
        assertTrue(gate.tryAcquire("first", free))
        assertTrue(gate.tryAcquire("first", free)) // reconnect same session
        assertFalse(gate.tryAcquire("second", free))
        gate.release("first")
        assertTrue(gate.tryAcquire("second", free))
    }

    @Test fun downgradePreservesExistingReservations() {
        val gate = SessionAdmissionGate(setOf("one", "two"))
        assertTrue(gate.tryAcquire("one", free))
        assertTrue(gate.tryAcquire("two", free))
        assertFalse(gate.tryAcquire("three", free))
        gate.release("one")
        assertFalse(gate.tryAcquire("three", free))
        gate.release("two")
        assertTrue(gate.tryAcquire("three", free))
    }

    @Test fun racingConnectionsReserveOnlyOneFreeSlot() {
        val gate = SessionAdmissionGate()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { id -> pool.submit<Boolean> {
                start.await()
                gate.tryAcquire(id.toString(), free)
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }
}
