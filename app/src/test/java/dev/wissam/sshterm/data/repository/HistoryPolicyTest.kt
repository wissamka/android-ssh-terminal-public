package dev.wissam.sshterm.data.repository

import org.junit.Assert.*
import org.junit.Test

class HistoryPolicyTest {
    @Test fun savedProfilesCanRecordWithoutGlobalConsent() {
        assertTrue(HistoryPolicy.shouldRecord(1L, true, false))
    }

    @Test fun noRecordOverridesEveryScopeAndGlobalConsent() {
        for (profile in listOf(null, 1L)) {
            for (global in listOf(false, true)) {
                assertFalse(HistoryPolicy.shouldRecord(profile, false, global))
            }
        }
    }

    @Test fun unscopedSessionsRequireExplicitConsent() {
        assertFalse(HistoryPolicy.shouldRecord(null, true, false))
        assertTrue(HistoryPolicy.shouldRecord(null, true, true))
    }

    @Test fun blankCommandsAreNeverRecorded() {
        assertNull(HistoryPolicy.commandToRecord(" \n\t"))
        assertEquals("ls -la", HistoryPolicy.commandToRecord("  ls -la  "))
    }

    @Test fun oldResultCannotPublishAfterScopeOrPrivacyInvalidation() {
        val policy = HistorySuggestionPolicy()
        val profileARequest = policy.invalidate()
        assertTrue(policy.isCurrent(profileARequest))
        val profileBRequest = policy.invalidate()
        assertFalse(policy.isCurrent(profileARequest))
        assertTrue(policy.isCurrent(profileBRequest))
        policy.invalidate() // clear history or turn global fallback off
        assertFalse(policy.isCurrent(profileARequest))
        assertFalse(policy.isCurrent(profileBRequest))
    }
}
