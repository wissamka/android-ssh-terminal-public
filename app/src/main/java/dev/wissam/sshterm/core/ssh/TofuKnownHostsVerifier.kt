package dev.wissam.sshterm.core.ssh

import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

/** Strict TOFU against the latest trust store, with atomic, conflict-checked persistence. */
class TofuKnownHostsVerifier(
    private val khFile: File,
    private val prompter: HostKeyPrompter,
) : HostKeyVerifier {
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        OpenSSHKnownHosts(StringReader(KnownHostsFileStore.read(khFile)))
            .findExistingAlgorithms(hostname, port)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        // Bound retries if another connection/editor continually changes trust. Never bypass it.
        repeat(8) {
            val snapshot = KnownHostsFileStore.read(khFile)
            var addition: String? = null
            val verifier = object : OpenSSHKnownHosts(StringReader(snapshot)) {
                override fun hostKeyUnverifiableAction(host: String, candidate: PublicKey): Boolean {
                    val info = HostKeyInfo(host, KeyType.fromKey(candidate).toString(), opensshFingerprint(candidate))
                    val accepted = runBlocking { prompter.onUnknownHost(info) }
                    if (accepted) addition = HostEntry(null, host, KeyType.fromKey(candidate), candidate).line
                    return accepted
                }

                override fun hostKeyChangedAction(host: String, candidate: PublicKey): Boolean {
                    val info = HostKeyInfo(host, KeyType.fromKey(candidate).toString(), opensshFingerprint(candidate))
                    val old = entries.firstOrNull { it.appliesTo(KeyType.fromKey(candidate), host) }
                        ?.fingerprint ?: "(unavailable)"
                    return runBlocking { prompter.onHostKeyChanged(info, old) }
                }
            }
            if (!verifier.verify(hostname, port, key)) return false
            val updated = addition?.let {
                snapshot + (if (snapshot.isEmpty() || snapshot.endsWith("\n")) "" else "\n") + it + "\n"
            } ?: snapshot
            // No monitor is held while prompting. A deletion/new trust decision invalidates
            // this snapshot and requires re-verification before acceptance.
            if (KnownHostsFileStore.commitIfUnchanged(khFile, snapshot, updated)) return true
        }
        return false
    }

    companion object {
        fun opensshFingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
