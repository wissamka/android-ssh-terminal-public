package dev.wissam.sshterm.ui.terminal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InteractionLeaseTest {
    @Test fun closingSessionCancelsReaderThreadPromptAndExposesNextConnection() = runBlocking {
        val queue = PromptQueue<PromptQueueTest.Prompt>()
        val lease = InteractionLease()
        val first = async { lease.request { queue.await<Boolean> { PromptQueueTest.Prompt("closed", it) } } }
        yield(); yield()
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { PromptQueueTest.Prompt("next", it) } }
        lease.cancel()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals("next", queue.current.value!!.host)
        queue.current.value!!.result(true)
        assertTrue(second.await())
    }
}
