package dev.wissam.sshterm.ui.keys

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ExportHandoffTest {
    @Test fun delayedExportAfterHomeCannotCreateFileOrShareEvenAfterResume() {
        val handoff = ExportHandoff()
        handoff.invalidate() // ON_PAUSE, before Compose disposal
        assertNoExport(handoff, resumed = false, unlocked = true)
        assertNoExport(handoff, resumed = true, unlocked = true)
    }

    @Test fun instantLockBeforeRecompositionRejectsLateExport() {
        assertNoExport(ExportHandoff(), resumed = true, unlocked = false)
    }

    @Test fun lifecycleCheckRejectsBackgroundEvenWithoutDeliveredPauseEvent() {
        assertNoExport(ExportHandoff(), resumed = false, unlocked = true)
    }

    @Test fun disposalRejectsOldResultWithoutInvalidatingNewAttempt() {
        val old = ExportHandoff()
        assertTrue(old.invalidate())
        assertFalse(old.invalidate()) // Later disposal must not cancel a successor operation.
        assertNoExport(old, resumed = true, unlocked = true)
        var shares = 0
        assertTrue(ExportHandoff().deliver(true, true) { shares++ })
        assertEquals(1, shares)
    }

    @Test fun successfulHandoffCreatesUniqueFileOnceDespiteDuplicateCallback() {
        val directory = Files.createTempDirectory("export-handoff").toFile()
        try {
            val handoff = ExportHandoff()
            var shares = 0
            val share = {
                createKeyExportFile(directory, "key").writeText("encrypted-test-fixture")
                shares++
                Unit
            }
            assertTrue(handoff.deliver(true, true, share))
            assertFalse(handoff.deliver(true, true, share))
            assertEquals(1, shares)
            assertFalse(handoff.invalidate()) // Successful handoff already released ownership.
            assertEquals(1, directory.resolve("exports").listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    private fun assertNoExport(handoff: ExportHandoff, resumed: Boolean, unlocked: Boolean) {
        val directory = Files.createTempDirectory("denied-export").toFile()
        try {
            var shared = false
            assertFalse(handoff.deliver(resumed, unlocked) {
                createKeyExportFile(directory, "key").writeText("must-not-exist")
                shared = true
            })
            assertFalse(shared)
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}
