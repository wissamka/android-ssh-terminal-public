package dev.wissam.sshterm.core.billing

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReceiptPersistenceGuardTest {
    @Test fun successfulAcknowledgementSurvivesFailedRefreshAndOfflineRestart() {
        val directory = Files.createTempDirectory("receipt-guard").toFile()
        try {
            val file = directory.resolve("receipt")
            val guard = ReceiptPersistenceGuard()
            val pendingAck = guard.confirmedOwnership("purchase-A")!!
            // A new query starts and fails: it has no confirmed ownership result to apply.
            assertTrue(guard.persistIfCurrent(pendingAck) { file.writeText("verified-receipt-A") })
            // Fresh process reads the durable result, not the prior in-memory entitlement.
            assertEquals("verified-receipt-A", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun repeatedSameOwnershipDoesNotDiscardPendingAcknowledgement() {
        val guard = ReceiptPersistenceGuard()
        val first = guard.confirmedOwnership("A")!!
        guard.confirmedOwnership("A")
        var writes = 0
        assertTrue(guard.persistIfCurrent(first) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun refundOrPendingOnlyOwnershipCannotBeResurrectedByLateAck() {
        val guard = ReceiptPersistenceGuard()
        val first = guard.confirmedOwnership("A")!!
        guard.confirmedOwnership(null) // complete query: no purchased entitlement
        var written = false
        assertFalse(guard.persistIfCurrent(first) { written = true })
        assertFalse(written)
    }

    @Test fun accountChangeCannotCacheOldAccountsReceipt() {
        val guard = ReceiptPersistenceGuard()
        val first = guard.confirmedOwnership("A")!!
        val second = guard.confirmedOwnership("B")!!
        var saved = ""
        assertFalse(guard.persistIfCurrent(first) { saved = "A" })
        assertTrue(guard.persistIfCurrent(second) { saved = "B" })
        assertEquals("B", saved)
    }

    @Test fun returningToSameTokenAfterRevocationDoesNotReviveOldCallback() {
        val guard = ReceiptPersistenceGuard()
        val first = guard.confirmedOwnership("A")!!
        guard.confirmedOwnership(null)
        val restored = guard.confirmedOwnership("A")!!
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(restored))
    }
}
