package dev.wissam.sshterm.core.ssh

import dev.wissam.sshterm.core.ssh.recovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundLifecycleTest {
    @Test fun timeoutStopsPlatformServiceBeforeTransportCleanup() {
        val events = mutableListOf<String>()
        dev.wissam.sshterm.service.shutdownSessionService(
            { events.add("notification") }, { events.add("stopSelf") }, { events.add("close sessions") },
        )
        assertEquals(listOf("notification", "stopSelf", "close sessions"), events)
    }

    @Test fun timeoutStillStopsAndClosesIfNotificationRemovalFails() {
        val events = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            dev.wissam.sshterm.service.shutdownSessionService(
                { error("notification failure") }, { events.add("stopSelf") }, { events.add("close sessions") },
            )
        }
        assertEquals(listOf("stopSelf", "close sessions"), events)
    }

    @Test fun closeWhileConnectRejectsAndClosesLateTransport() {
        val owner = LifecycleResources()
        val connecting = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val closes = AtomicInteger()
        val worker = Thread {
            connecting.countDown()
            check(connected.await(2, TimeUnit.SECONDS))
            assertFalse(owner.own { closes.incrementAndGet() })
        }
        worker.start()
        assertTrue(connecting.await(2, TimeUnit.SECONDS))
        owner.seal()
        owner.releaseAll(permanently = true)
        connected.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(1, closes.get())
        assertFalse(owner.ifOpen { fail("Cannot publish CONNECTED after close") })
    }

    @Test fun teardownClosesAllResourcesOnceEvenWhenOneFails() {
        val owner = LifecycleResources()
        val order = mutableListOf<String>()
        owner.own { order.add("chain") }
        owner.own { order.add("forward"); error("stop failed") }
        owner.releaseAll(permanently = true)
        owner.releaseAll(permanently = true)
        assertEquals(listOf("forward", "chain"), order)
    }

    @Test fun reconnectCanAcquireAfterOrdinaryTeardown() {
        val owner = LifecycleResources()
        val closes = AtomicInteger()
        owner.own { closes.incrementAndGet() }
        owner.releaseAll()
        assertTrue(owner.own { closes.incrementAndGet() })
        owner.releaseAll(permanently = true)
        assertEquals(2, closes.get())
    }

    private class Storage(var content: String = "") : RecoveryStorage {
        override fun read() = content
        override fun write(value: String) { content = value }
    }

    @Test fun processDeathProducesEndedMetadataWithoutCredentialsOrAutomaticConnection() {
        val storage = Storage()
        val firstProcess = RecoveryJournal(storage)
        firstProcess.record(EndedSession("profile-session", 42))
        firstProcess.record(EndedSession("unsaved-session", null))
        assertEquals("profile-session|42\nunsaved-session|", storage.content)
        val secondProcess = RecoveryJournal(storage)
        assertEquals(firstProcess.snapshot(), secondProcess.snapshot())
        secondProcess.dismiss("profile-session")
        assertEquals(listOf(EndedSession("unsaved-session", null)), RecoveryJournal(storage).snapshot())
    }

    @Test fun invalidJournalRowsAreIgnored() {
        val journal = RecoveryJournal(Storage("a|2\na|2\nb|-1\nc|bogus\nsecret|1|extra\n|4"))
        assertEquals(listOf(EndedSession("a", 2)), journal.snapshot())
    }

    @Test fun failedPersistenceDoesNotMutateInMemoryJournal() {
        val journal = RecoveryJournal(object : RecoveryStorage {
            override fun read() = "a|2"
            override fun write(value: String) { throw java.io.IOException("disk unavailable") }
        })
        assertThrows(java.io.IOException::class.java) { journal.dismiss("a") }
        assertEquals(listOf(EndedSession("a", 2)), journal.snapshot())
    }
}
