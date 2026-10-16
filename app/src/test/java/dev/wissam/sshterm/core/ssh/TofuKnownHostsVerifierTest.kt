package dev.wissam.sshterm.core.ssh

import java.io.File
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TofuKnownHostsVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val keyA = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
    private val keyB = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
    private fun file() = File(temp.root, "known_hosts")
    private class Prompts(val unknown: () -> Boolean = { true }, val changed: () -> Boolean = { false }) : HostKeyPrompter {
        var unknownCount = 0
        var changedCount = 0
        override suspend fun onUnknownHost(info: HostKeyInfo): Boolean { unknownCount++; return unknown() }
        override suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String): Boolean { changedCount++; return changed() }
    }

    @Test fun staleVerifiersDoNotEraseOtherHosts() {
        val f = file()
        val a = TofuKnownHostsVerifier(f, Prompts())
        val b = TofuKnownHostsVerifier(f, Prompts())
        assertTrue(a.verify("one.example", 22, keyA))
        assertTrue(b.verify("two.example", 2222, keyB))
        val deny = Prompts(unknown = { false })
        val reader = TofuKnownHostsVerifier(f, deny)
        assertTrue(reader.verify("one.example", 22, keyA))
        assertTrue(reader.verify("two.example", 2222, keyB))
        assertEquals(0, deny.unknownCount)
        assertFalse(reader.verify("two.example", 22, keyB))
    }

    @Test fun simultaneousFirstUseForSameHostRechecksChangedKey() {
        val f = file()
        val prompts = Prompts(unknown = {
            assertTrue(TofuKnownHostsVerifier(f, Prompts()).verify("server", 22, keyA))
            true
        })
        assertFalse(TofuKnownHostsVerifier(f, prompts).verify("server", 22, keyB))
        assertEquals(1, prompts.unknownCount)
        assertEquals(1, prompts.changedCount)
        assertTrue(TofuKnownHostsVerifier(f, Prompts(unknown = { false })).verify("server", 22, keyA))
    }

    @Test fun deletionInvalidatesPreviouslyConstructedVerifier() {
        val f = file()
        assertTrue(TofuKnownHostsVerifier(f, Prompts()).verify("server", 22, keyA))
        val deny = Prompts(unknown = { false })
        val stale = TofuKnownHostsVerifier(f, deny)
        KnownHostsFileStore.update(f) { "" }
        assertFalse(stale.verify("server", 22, keyA))
        assertEquals(1, deny.unknownCount)
    }

    @Test fun changedKeyAcceptanceDoesNotReplaceStoredTrust() {
        val f = file()
        assertTrue(TofuKnownHostsVerifier(f, Prompts()).verify("server", 22, keyA))
        val before = f.readText()
        assertTrue(TofuKnownHostsVerifier(f, Prompts(changed = { true })).verify("server", 22, keyB))
        assertEquals(before, f.readText())
        assertFalse(TofuKnownHostsVerifier(f, Prompts()).verify("server", 22, keyB))
    }

    @Test fun rejectedUnknownHostDoesNotCreateTrust() {
        val f = file()
        assertFalse(TofuKnownHostsVerifier(f, Prompts(unknown = { false })).verify("server", 22, keyA))
        assertFalse(f.exists())
    }

    @Test fun unreadableStoreIsNotAccepted() {
        val directory = temp.newFolder("not-a-file")
        try {
            TofuKnownHostsVerifier(directory, Prompts()).verify("server", 22, keyA)
            fail("Unreadable trust store must not accept a key")
        } catch (expected: java.io.IOException) { }
    }
    @Test fun concurrentThreadsPreserveBothHostEntries() {
        val f = file()
        val bothPrompted = java.util.concurrent.CountDownLatch(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf("one" to keyA, "two" to keyB).map { (host, key) ->
                pool.submit<Boolean> {
                    val prompts = Prompts(unknown = {
                        bothPrompted.countDown()
                        check(bothPrompted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        true
                    })
                    TofuKnownHostsVerifier(f, prompts).verify(host, 22, key)
                }
            }
            jobs.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            val deny = Prompts(unknown = { false })
            val verifier = TofuKnownHostsVerifier(f, deny)
            assertTrue(verifier.verify("one", 22, keyA))
            assertTrue(verifier.verify("two", 22, keyB))
            assertEquals(0, deny.unknownCount)
        } finally { pool.shutdownNow() }
    }

    @Test fun trustDeletionDuringChangedKeyPromptRequiresNewDecision() {
        val f = file()
        assertTrue(TofuKnownHostsVerifier(f, Prompts()).verify("server", 22, keyA))
        val prompts = Prompts(unknown = { false }, changed = {
            KnownHostsFileStore.update(f) { "" }
            true
        })
        assertFalse(TofuKnownHostsVerifier(f, prompts).verify("server", 22, keyB))
        assertEquals(1, prompts.changedCount)
        assertEquals(1, prompts.unknownCount)
        assertEquals("", f.readText())
    }

    @Test fun continuousTrustChangesFailClosedAfterBoundedRetries() {
        val f = file()
        var generation = 0
        val prompts = Prompts(unknown = {
            KnownHostsFileStore.update(f) { "# revision ${++generation}\n" }
            true
        })
        assertFalse(TofuKnownHostsVerifier(f, prompts).verify("server", 22, keyA))
        assertEquals(8, prompts.unknownCount)
        assertFalse(f.readText().contains("ssh-rsa"))
    }

}
