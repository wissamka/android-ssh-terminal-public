package dev.wissam.sshterm.ui.security

import org.junit.Assert.*
import org.junit.Test

class AppLockPolicyTest {
    @Test fun coldLaunchIsLockedEvenWithoutBackgroundHistory() {
        val policy = AppLockPolicy()
        policy.foreground(0)
        assertTrue(policy.locked)
    }

    @Test fun resumeBeforeTimeoutRetainsUnlock() {
        val policy = AppLockPolicy().apply { unlock(); timeoutMillis = 100 }
        policy.background(1000)
        policy.foreground(1099)
        assertFalse(policy.locked)
    }

    @Test fun exactTimeoutLocks() {
        val policy = AppLockPolicy().apply { unlock(); timeoutMillis = 100 }
        policy.background(1000)
        policy.foreground(1100)
        assertTrue(policy.locked)
    }

    @Test fun instantLocksEvenWithinSameClockTick() {
        val policy = AppLockPolicy().apply { unlock(); timeoutMillis = 0 }
        policy.background(1000)
        policy.foreground(1000)
        assertTrue(policy.locked)
    }

    @Test fun timeoutReductionAppliesWithoutRecreatingPolicy() {
        val policy = AppLockPolicy().apply { unlock(); timeoutMillis = 900_000 }
        policy.background(1000)
        policy.timeoutMillis = 0
        policy.foreground(1001)
        assertTrue(policy.locked)
    }

    @Test fun foregroundConsumesPreviousBackgroundTimestamp() {
        val policy = AppLockPolicy().apply { unlock(); timeoutMillis = 100 }
        policy.background(1000)
        policy.foreground(1001)
        policy.foreground(1200)
        assertFalse(policy.locked)
        policy.background(1300)
        policy.foreground(1400)
        assertTrue(policy.locked)
    }

    @Test fun timeoutCannotUnlockAnAlreadyLockedApp() {
        val policy = AppLockPolicy().apply { timeoutMillis = 900_000 }
        policy.background(1000)
        policy.foreground(1001)
        assertTrue(policy.locked)
    }

    @Test fun unavailableAuthenticationDeniesWithoutShowingPrompt() {
        var result: Boolean? = null
        requestAuthentication({ false }, { fail("Must not show prompt") }, { result = it })
        assertEquals(false, result)
    }

    @Test fun capabilityExceptionDenies() {
        var result: Boolean? = null
        requestAuthentication(
            { throw IllegalStateException("Unavailable") },
            { fail("Must not show prompt") },
            { result = it },
        )
        assertEquals(false, result)
    }

    @Test fun promptSetupExceptionDenies() {
        var result: Boolean? = null
        requestAuthentication({ true }, { throw IllegalArgumentException("Unsupported") }, { result = it })
        assertEquals(false, result)
    }

    @Test fun availabilityAloneDoesNotAuthorize() {
        var result: Boolean? = null
        var completion: ((Boolean) -> Unit)? = null
        requestAuthentication({ true }, { completion = it }, { result = it })
        assertNull(result)
        completion!!(true)
        assertEquals(true, result)
    }

    @Test fun cancellationDeniesAndLateSuccessCannotReverseIt() {
        val results = mutableListOf<Boolean>()
        var completion: ((Boolean) -> Unit)? = null
        requestAuthentication({ true }, { completion = it }, { results.add(it) })
        completion!!(false)
        completion!!(true)
        assertEquals(listOf(false), results)
    }

    @Test fun disposingPendingAttemptDeniesAndIgnoresLateSuccess() {
        val results = mutableListOf<Boolean>()
        var completion: ((Boolean) -> Unit)? = null
        var cancellations = 0
        val operation = requestAuthentication(
            { true }, { completion = it }, { results.add(it) }, { cancellations++ },
        )
        operation.cancel()
        completion!!(true)
        completion!!(false)
        operation.cancel()
        assertTrue(operation.isComplete)
        assertEquals(1, cancellations)
        assertEquals(listOf(false), results)
    }

    @Test fun disposalInvalidatesBeforeReentrantPlatformSuccess() {
        val results = mutableListOf<Boolean>()
        var completion: ((Boolean) -> Unit)? = null
        val operation = requestAuthentication(
            { true }, { completion = it }, { results.add(it) }, { completion!!(true) },
        )
        operation.cancel()
        assertEquals(listOf(false), results)
    }

    @Test fun platformCancellationExceptionStillDenies() {
        val results = mutableListOf<Boolean>()
        var completion: ((Boolean) -> Unit)? = null
        val operation = requestAuthentication(
            { true }, { completion = it }, { results.add(it) },
            { throw IllegalStateException("Platform already stopped") },
        )
        operation.cancel()
        completion!!(true)
        assertEquals(listOf(false), results)
    }

    @Test fun disposingCompletedAttemptDoesNotCancelNewPlatformPrompt() {
        var cancellations = 0
        val operation = requestAuthentication(
            { true }, { it(true) }, {}, { cancellations++ },
        )
        operation.cancel()
        assertEquals(0, cancellations)
    }

    @Test fun oldAttemptCannotAuthorizeReplacementAfterDisposal() {
        var oldCompletion: ((Boolean) -> Unit)? = null
        var newCompletion: ((Boolean) -> Unit)? = null
        val oldResults = mutableListOf<Boolean>()
        val newResults = mutableListOf<Boolean>()
        val old = requestAuthentication({ true }, { oldCompletion = it }, { oldResults.add(it) })
        old.cancel()
        val replacement = requestAuthentication({ true }, { newCompletion = it }, { newResults.add(it) })
        oldCompletion!!(true)
        old.cancel()
        assertEquals(listOf(false), oldResults)
        assertTrue(newResults.isEmpty())
        assertFalse(replacement.isComplete)
        newCompletion!!(true)
        assertEquals(listOf(true), newResults)
    }

    @Test fun credentialSuccessWaitsForOwnerResume() {
        var resumed = false
        val results = mutableListOf<Boolean>()
        val operation = requestAuthentication(
            { true }, { it(true) }, { results.add(it) }, ownerResumed = { resumed },
        )
        assertTrue(results.isEmpty())
        assertFalse(operation.isComplete)
        resumed = true
        operation.onOwnerResumed()
        assertEquals(listOf(true), results)
    }

    @Test fun disposedCredentialResultCannotAuthorizeOnResume() {
        var resumed = false
        val results = mutableListOf<Boolean>()
        val operation = requestAuthentication(
            { true }, { it(true) }, { results.add(it) }, ownerResumed = { resumed },
        )
        operation.cancel()
        resumed = true
        operation.onOwnerResumed()
        assertEquals(listOf(false), results)
    }

    @Test fun timeoutRelockRejectsDeferredCredentialSuccess() {
        var resumed = false
        val lock = AppLockPolicy().apply { unlock(); timeoutMillis = 0 }
        val results = mutableListOf<Boolean>()
        val operation = requestAuthentication(
            { true }, { it(true) }, { results.add(it) },
            ownerResumed = { resumed }, canAuthorize = { !lock.locked },
        )
        lock.background(1000)
        lock.foreground(1000)
        resumed = true
        operation.onOwnerResumed()
        assertEquals(listOf(false), results)
        assertTrue(lock.locked)
    }

    @Test fun resumeWithoutAuthenticationNeverAuthorizes() {
        val results = mutableListOf<Boolean>()
        val operation = requestAuthentication({ true }, {}, { results.add(it) })
        operation.onOwnerResumed()
        assertTrue(results.isEmpty())
        assertFalse(operation.isComplete)
        operation.cancel()
    }
}
