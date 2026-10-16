package dev.wissam.sshterm.core.billing

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CreationEnforcementTest {
    @Test fun concurrentNewProfilesCannotBypassFreeCap() = runBlocking {
        val gate = ProfileCreationGate()
        val count = AtomicInteger(0)
        val results = (1..30).map {
            async(Dispatchers.Default) {
                try {
                    gate.create({ Entitlement() }, true, FreeTierLimits(), { count.get() }) {
                        delay(1)
                        count.incrementAndGet()
                    }
                    true
                } catch (_: EntitlementLimitException) { false }
            }
        }.awaitAll()
        assertEquals(3, results.count { it })
        assertEquals(3, count.get())
    }

    @Test fun unconfiguredBuildDoesNotRestrictOrGrantPro() = runBlocking {
        val free = Entitlement()
        val gate = ProfileCreationGate()
        assertEquals(51, gate.create({ free }, false, FreeTierLimits(), { 50 }) { 51 })
        val sessions = SessionAdmissionGate()
        repeat(8) { assertTrue(sessions.tryAcquire(it.toString(), free, enforceLimits = false)) }
        assertFalse(free.pro)
        // Re-enabling restrictions keeps existing reservations usable, but refuses new work.
        assertTrue(sessions.tryAcquire("0", free))
        assertFalse(sessions.tryAcquire("new", free))
    }

    @Test fun failedInsertDoesNotConsumeCapacity() = runBlocking {
        val gate = ProfileCreationGate()
        try {
            gate.create({ Entitlement() }, true, FreeTierLimits(), { 2 }) { error("disk failure") }
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(3, gate.create({ Entitlement() }, true, FreeTierLimits(), { 2 }) { 3 })
    }

    @Test fun localAndSshShareCapacityAndReconnectRetainsReservation() {
        val gate = SessionAdmissionGate()
        assertTrue(gate.tryAcquire("local", Entitlement()))
        assertFalse(gate.tryAcquire("ssh", Entitlement()))
        gate.release("local")
        assertTrue(gate.tryAcquire("ssh", Entitlement()))
        assertTrue(gate.tryAcquire("ssh", Entitlement()))
        assertFalse(gate.tryAcquire("local", Entitlement()))
    }

    @Test fun ownerIsDebugOnlyAndNeverChangesPurchaseState() {
        assertTrue(DevelopmentAccess.ownerPro(true, true))
        assertFalse(DevelopmentAccess.ownerPro(false, true))
        assertFalse(DevelopmentAccess.ownerPro(true, false))
        assertFalse(DevelopmentAccess.ownerPro(false, false))
        val purchase = Entitlement()
        val owner = DevelopmentAccess.ownerPro(true, true)
        val gate = SessionAdmissionGate()
        repeat(10) { assertTrue(gate.tryAcquire("$it", purchase, enforceLimits = !owner)) }
        assertFalse(purchase.pro)
        assertFalse(purchase.pending)
        assertEquals(BillingAvailability.UNCONFIGURED, purchase.availability)
    }
}
