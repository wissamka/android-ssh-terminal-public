package dev.wissam.sshterm.core.ssh

import dev.wissam.sshterm.core.ssh.recovery.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecoveryStateTest {
    private class Storage(var content: String = "") : RecoveryStorage {
        var fail = false
        override fun read() = content
        override fun write(value: String) {
            if (fail) throw IOException("disk full")
            content = value
        }
    }

    @Test fun failedDismissRetainsMetadataAndRetriesWithoutThrowing() {
        val storage = Storage("a|1\nb|2")
        val state = RecoveryState(RecoveryJournal(storage))
        storage.fail = true
        state.dismiss("a")
        state.dismiss("b")
        assertEquals(2, state.ended.value.size)
        assertNotNull(state.persistenceError.value)
        assertEquals("a|1\nb|2", storage.content)
        storage.fail = false
        state.retryPendingRemovals()
        assertTrue(state.ended.value.isEmpty())
        assertEquals("", storage.content)
        assertNull(state.persistenceError.value)
    }

    @Test fun subsequentOpenRetriesRemovalsWithoutDeletingTheNewSession() {
        val storage = Storage("old|1")
        val state = RecoveryState(RecoveryJournal(storage))
        storage.fail = true
        state.dismiss("old")
        storage.fail = false
        state.opened(EndedSession("new", 1))
        assertEquals("new|1", storage.content)
        assertTrue(state.ended.value.isEmpty())
    }

    @Test fun admissionStillFailsWhenInitialRecordCannotBePersisted() {
        val storage = Storage().apply { fail = true }
        val state = RecoveryState(RecoveryJournal(storage))
        assertThrows(IOException::class.java) { state.opened(EndedSession("new", 1)) }
        assertEquals("", storage.content)
    }
}
