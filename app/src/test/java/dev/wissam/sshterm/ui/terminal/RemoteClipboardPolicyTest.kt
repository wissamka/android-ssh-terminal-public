package dev.wissam.sshterm.ui.terminal

import org.junit.Assert.*
import org.junit.Test

class RemoteClipboardPolicyTest {
    private val policy = RemoteClipboardPolicy()

    @Test fun activeRequestRequiresExplicitAcceptanceAndIsOneShot() {
        val request = policy.offer("a", "a", "server text", 0)!!
        assertEquals(11, request.charCount)
        assertEquals("server text", policy.accept(request.id, "a", 1))
        assertNull(policy.accept(request.id, "a", 2))
    }

    @Test fun inactiveOrMissingSessionCannotQueueRequest() {
        assertNull(policy.offer("b", "a", "inactive", 0))
        assertNull(policy.offer("a", null, "closed", 0))
        val request = policy.offer("a", "a", "active", 0)!!
        assertEquals("active", policy.accept(request.id, "a", 1))
    }

    @Test fun floodKeepsOnlyOriginalPayload() {
        val request = policy.offer("a", "a", "approved payload", 0)!!
        repeat(1000) { assertNull(policy.offer("a", "a", "replacement $it", 1)) }
        assertEquals("approved payload", policy.accept(request.id, "a", 2))
    }

    @Test fun payloadBoundIsEnforcedWithoutTruncation() {
        assertNull(policy.offer("a", "a", "x".repeat(RemoteClipboardPolicy.MAX_CHARS + 1), 0))
        val text = "x".repeat(RemoteClipboardPolicy.MAX_CHARS)
        val request = policy.offer("a", "a", text, 0)!!
        assertEquals(text, policy.accept(request.id, "a", 1))
    }

    @Test fun sessionSwitchOrClosureRejectsAndConsumesConsent() {
        for (active in listOf("b", null)) {
            val request = policy.offer("a", "a", "secret", 0)!!
            assertNull(policy.accept(request.id, active, 1))
            assertNull(policy.accept(request.id, "a", 2))
        }
    }

    @Test fun expiryBoundaryRejectsCopy() {
        val request = policy.offer("a", "a", "expired", 0)!!
        assertNull(policy.accept(request.id, "a", RemoteClipboardPolicy.REQUEST_TTL_MS))
    }

    @Test fun staleCopyAndDismissCannotAffectNewRequest() {
        val old = policy.offer("a", "a", "old", 0)!!
        val fresh = policy.offer("a", "a", "fresh", RemoteClipboardPolicy.REQUEST_TTL_MS)!!
        assertNull(policy.accept(old.id, "a", RemoteClipboardPolicy.REQUEST_TTL_MS + 1))
        policy.dismiss(old.id)
        assertEquals("fresh", policy.accept(fresh.id, "a", RemoteClipboardPolicy.REQUEST_TTL_MS + 2))
    }

    @Test fun dismissalOrNavigationCancellationPreventsLaterCopy() {
        val request = policy.offer("a", "a", "dismissed", 0)!!
        policy.dismiss(request.id)
        assertNull(policy.accept(request.id, "a", 1))
        assertNotNull(policy.offer("a", "a", "new", 2))
    }

    @Test fun emptyClipboardClearAlsoRequiresAcceptance() {
        val request = policy.offer("a", "a", "", 0)!!
        assertEquals("", policy.accept(request.id, "a", 1))
    }
}
