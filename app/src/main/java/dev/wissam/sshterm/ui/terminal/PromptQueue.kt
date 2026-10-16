package dev.wissam.sshterm.ui.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** FIFO UI requests with identity-bound completion, cancellation and bounded retention. */
internal class PromptQueue<P : Any>(private val capacity: Int = 32) {
    private class Entry<P>(val prompt: P, val cancel: () -> Unit)
    private val lock = Any()
    private val pending = ArrayDeque<Entry<P>>()
    private val visible = MutableStateFlow<P?>(null)
    val current: StateFlow<P?> = visible.asStateFlow()
    private var closed = false

    suspend fun <T> await(build: ((T) -> Unit) -> P): T = suspendCancellableCoroutine { continuation ->
        synchronized(lock) {
            if (closed || pending.size >= capacity) {
                continuation.cancel(CancellationException("Prompt queue unavailable"))
                return@synchronized
            }
            lateinit var entry: Entry<P>
            val prompt = build { value ->
                synchronized(lock) {
                    // A stale dialog cannot resolve either a successor or a cancelled request.
                    if (pending.firstOrNull() === entry && continuation.isActive) {
                        pending.removeFirst()
                        visible.value = pending.firstOrNull()?.prompt
                        continuation.resume(value) { _, undelivered, _ ->
                            if (undelivered is CharArray) undelivered.fill('\u0000')
                        }
                    } else if (value is CharArray) {
                        value.fill('\u0000')
                    }
                }
            }
            entry = Entry(prompt) { continuation.cancel() }
            pending.addLast(entry)
            visible.value = pending.first().prompt
            continuation.invokeOnCancellation {
                synchronized(lock) {
                    pending.remove(entry)
                    visible.value = pending.firstOrNull()?.prompt
                }
            }
        }
    }

    fun close() {
        val abandoned = synchronized(lock) {
            closed = true
            pending.toList().also { pending.clear(); visible.value = null }
        }
        abandoned.forEach { it.cancel() }
    }
}
