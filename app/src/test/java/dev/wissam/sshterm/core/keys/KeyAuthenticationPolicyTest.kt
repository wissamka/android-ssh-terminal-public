package dev.wissam.sshterm.core.keys

import org.junit.Assert.*
import org.junit.Test

class KeyAuthenticationPolicyTest {
    @Test fun recognizesBothPerOperationRepresentations() {
        assertTrue(KeyAuthenticationPolicy.isPerOperation(true, -1))
        assertTrue(KeyAuthenticationPolicy.isPerOperation(true, 0))
    }
    @Test fun neverUpgradesWindowsAbsentAuthenticationOrUnknownValues() {
        for (seconds in listOf(-2, 1, 60, Int.MAX_VALUE))
            assertFalse(KeyAuthenticationPolicy.isPerOperation(true, seconds))
        for (seconds in listOf(-1, 0, 60))
            assertFalse(KeyAuthenticationPolicy.isPerOperation(false, seconds))
    }
}
