package dev.wissam.sshterm.ui.terminal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PromptQueueTest {
    data class Prompt(val host: String, val result: (Boolean) -> Unit)

    @Test fun requestsWaitInOrderAndLateCallbackCannotApproveNext() = runBlocking {
        val queue = PromptQueue<Prompt>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("one", it) } }
        val old = queue.current.value!!
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("two", it) } }
        assertEquals("one", queue.current.value!!.host)
        old.result(true)
        assertTrue(first.await())
        old.result(true)
        assertFalse(second.isCompleted)
        assertEquals("two", queue.current.value!!.host)
        queue.current.value!!.result(false)
        assertFalse(second.await())
        assertNull(queue.current.value)
    }

    @Test fun cancellingVisibleOrWaitingRequestDoesNotLoseSuccessor() = runBlocking {
        val queue = PromptQueue<Prompt>()
        val jobs = (1..3).map { n -> async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("$n", it) } } }
        val stale = queue.current.value!!
        jobs[1].cancelAndJoin()
        jobs[0].cancelAndJoin()
        assertEquals("3", queue.current.value!!.host)
        stale.result(true)
        assertFalse(jobs[2].isCompleted)
        queue.current.value!!.result(true)
        assertTrue(jobs[2].await())
    }

    @Test fun closeCancelsEveryWaiterAndRejectsNewRequests() = runBlocking {
        val queue = PromptQueue<Prompt>()
        val jobs = (1..3).map { async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("host", it) } } }
        val old = queue.current.value!!
        queue.close()
        jobs.forEach { it.join(); assertTrue(it.isCancelled) }
        old.result(true)
        assertNull(queue.current.value)
        val rejected = async { queue.await<Boolean> { Prompt("new", it) } }
        rejected.join()
        assertTrue(rejected.isCancelled)
    }

    @Test fun floodIsBoundedWithoutReplacingActivePrompt() = runBlocking {
        val queue = PromptQueue<Prompt>(1)
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("first", it) } }
        val overflow = async(start = CoroutineStart.UNDISPATCHED) { queue.await<Boolean> { Prompt("overflow", it) } }
        assertTrue(overflow.isCancelled)
        assertEquals("first", queue.current.value!!.host)
        queue.current.value!!.result(true)
        assertTrue(first.await())
    }
}
