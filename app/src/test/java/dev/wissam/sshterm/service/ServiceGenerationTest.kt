package dev.wissam.sshterm.service

import org.junit.Assert.*
import org.junit.Test

class ServiceGenerationTest {
    @Test fun delayedDestructionCannotRetireReplacement() {
        val owner = ServiceGeneration()
        val old = owner.acquire()
        assertEquals(old, owner.acquire())
        owner.retireCurrent() // last tab closed; stopService requested
        val replacement = owner.acquire()
        assertNotEquals(old, replacement)
        owner.retire(old) // delayed onDestroy
        assertTrue(owner.isCurrent(replacement))
        assertFalse(owner.isCurrent(old))
    }

    @Test fun timeoutThenDestroyCannotRetireNewStart() {
        val owner = ServiceGeneration()
        val old = owner.acquire()
        owner.retire(old) // timeout
        val replacement = owner.acquire()
        owner.retire(old) // destruction after timeout
        assertTrue(owner.isCurrent(replacement))
        owner.retire(replacement)
        assertNull(owner.current)
    }
}
