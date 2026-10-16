package dev.wissam.sshterm.core.ssh

import org.junit.Assert.*
import org.junit.Test

class InteractiveChallengesTest {
    @Test fun actualProviderForwardsEchoInstructionsAndEveryRoundWithoutReusingAnswers() {
        val seen = mutableListOf<InteractiveChallenge>()
        val replies = ArrayDeque(listOf("alice", "123456", "654321"))
        val provider = InteractiveChallenges("alice@host:22") { seen.add(it); replies.removeFirst().toCharArray() }
        provider.init(null, "Identity", "Enter account")
        assertEquals("alice", String(provider.getResponse("Username", true)))
        assertEquals("123456", String(provider.getResponse("OTP", false)))
        provider.init(null, "Step 2", "Fresh token")
        assertEquals("654321", String(provider.getResponse("Next OTP", false)))
        assertEquals(listOf(true, false, false), seen.map { it.echo })
        assertEquals("Fresh token", seen.last().instructions)
        assertEquals("alice@host:22", seen.last().target)
        assertFalse(provider.shouldRetry())
    }
    @Test fun refusalFailsAuthenticationInsteadOfSendingEmptyAnswer() {
        val provider = InteractiveChallenges("host") { null }
        assertThrows(SshFatalException::class.java) { provider.getResponse("Password", false) }
    }
    @Test fun challengeFloodAndTextRetentionAreBounded() {
        var seen: InteractiveChallenge? = null
        val provider = InteractiveChallenges("host") { seen = it; charArrayOf() }
        provider.init(null, "n".repeat(5000), "i".repeat(5000))
        provider.getResponse("p".repeat(5000), false)
        assertEquals(4096, seen!!.prompt.length)
        assertEquals(4096, seen!!.name.length)
        repeat(15) { provider.init(null, "", "") }
        assertThrows(SshFatalException::class.java) { provider.init(null, "", "") }
    }
}
