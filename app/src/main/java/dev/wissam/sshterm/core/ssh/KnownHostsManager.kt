package dev.wissam.sshterm.core.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/delete access to the OpenSSH-format known_hosts file that backs TOFU verification
 * ([TofuKnownHostsVerifier]). Lets the user inspect and revoke trusted host keys — e.g. after a
 * legitimate server reinstall, so the next connection re-prompts instead of failing forever with
 * the MITM warning.
 */
@Singleton
class KnownHostsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One trusted host key, tied to its exact source line so deletion is unambiguous. */
    data class Entry(
        val rawLine: String,
        val hostPattern: String,
        val keyType: String,
        /** SHA256:… fingerprint, or null if the key blob couldn't be decoded. */
        val fingerprint: String?,
        /** True when the host field is a hashed |1|salt|hash token (host not human-readable). */
        val hashed: Boolean,
    )

    private val file: File get() = File(context.filesDir, "known_hosts")

    fun list(): List<Entry> {
        return KnownHostsFileStore.read(file).lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            // Optional leading marker (@cert-authority / @revoked), then host, keytype, blob.
            val parts = trimmed.split(Regex("\\s+"))
            val fields = if (parts.firstOrNull()?.startsWith("@") == true) parts.drop(1) else parts
            if (fields.size < 3) return@mapNotNull null
            val host = fields[0]
            val keyType = fields[1]
            val blob = fields[2]
            Entry(
                rawLine = line,
                hostPattern = host,
                keyType = keyType,
                fingerprint = fingerprintOf(blob),
                hashed = host.startsWith("|1|"),
            )
        }.toList()
    }

    /** Removes every line whose raw text matches [entry] (exact line match). */
    fun delete(entry: Entry) {
        KnownHostsFileStore.update(file) { current ->
            current.lineSequence().filter { it != entry.rawLine }.joinToString("\n")
        }
    }

    fun clearAll() {
        KnownHostsFileStore.update(file) { "" }
    }

    private fun fingerprintOf(base64Blob: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(base64Blob)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }.getOrNull()
}
