package dev.wissam.sshterm.data.repository

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KeyRegistrationTest {
    private data class Key(val storage: String, val id: Long = 0)

    @Test(timeout = 10000) fun cancellationAfterMaterialCreationStillRegistersIt() = runBlocking {
        val created = CountDownLatch(1)
        val finishCreation = CountDownLatch(1)
        val saved = CompletableDeferred<Key>()
        val deleted = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            registerNewKeyMaterial<Key>(
                create = {
                    created.countDown()
                    check(finishCreation.await(5, TimeUnit.SECONDS))
                    Key("new-material")
                },
                save = { it.copy(id = 7).also { key -> saved.complete(key) } },
                findSaved = { null },
                deleteUnregistered = { deleted.incrementAndGet() },
            )
        }
        try {
            assertTrue(created.await(5, TimeUnit.SECONDS))
            job.cancel() // screen disposed while synchronous creation is still returning
        } finally { finishCreation.countDown() }
        job.join()
        assertEquals(Key("new-material", 7), saved.await())
        assertEquals(0, deleted.get())
        assertTrue(job.isCancelled)
    }

    @Test(timeout = 10000) fun cancellationDuringInsertDoesNotInterruptCommit() = runBlocking {
        val inserting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted: Key? = null
        val job = launch {
            registerNewKeyMaterial<Key>(
                create = { Key("new") },
                save = { inserting.complete(Unit); release.await(); it.copy(id = 1).also { persisted = it } },
                findSaved = { persisted },
                deleteUnregistered = { error("Must not delete committed material") },
            )
        }
        inserting.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(Key("new", 1), persisted)
    }

    @Test fun insertCommitsThenThrowsPreservesAndReturnsRegisteredKey() = runBlocking {
        var persisted: Key? = null
        val result = registerNewKeyMaterial<Key>(
            create = { Key("new") },
            save = { persisted = it.copy(id = 3); throw IOException("lost result") },
            findSaved = { persisted },
            deleteUnregistered = { error("Committed key must survive") },
        )
        assertEquals(Key("new", 3), result)
    }

    @Test fun failedInsertDeletesOnlyNewUnreferencedMaterial() = runBlocking {
        val existing = Key("existing", 4)
        val deleted = mutableListOf<Key>()
        try {
            registerNewKeyMaterial<Key>(
                create = { Key("new") },
                save = { throw IOException("duplicate display name") },
                findSaved = { candidate -> existing.takeIf { it.storage == candidate.storage } },
                deleteUnregistered = { deleted.add(it) },
            )
            fail("Expected insert failure")
        } catch (_: IOException) { }
        assertEquals(listOf(Key("new")), deleted)
        assertFalse(existing in deleted)
    }

    @Test fun uncertainLookupPreservesMaterialAndOriginalFailure() = runBlocking {
        val failure = IOException("insert outcome unknown")
        try {
            registerNewKeyMaterial<Key>(
                create = { Key("new") },
                save = { throw failure },
                findSaved = { throw IOException("database unavailable") },
                deleteUnregistered = { error("Cannot prove it is unregistered") },
            )
            fail("Expected uncertainty to be reported")
        } catch (actual: IOException) {
            // Coroutine stacktrace recovery may copy IOException at the dispatcher boundary.
            val causes = generateSequence<Throwable>(actual) { it.cause }.toList()
            assertEquals(failure.message, actual.message)
            assertTrue("Original insert failure must remain in the cause chain", causes.any { it === failure })
            val lookupFailures = causes.flatMap { it.suppressed.toList() }
            assertTrue("Lookup failure must be preserved", lookupFailures.any {
                it is IOException && it.message == "database unavailable"
            })
        }
    }
}
