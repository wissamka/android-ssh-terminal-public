package dev.wissam.sshterm.core.ssh

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Single-process trust-store transactions, shared by verification and the trust editor. */
internal object KnownHostsFileStore {
    @Synchronized
    fun read(file: File): String = if (file.exists()) file.readText() else ""

    @Synchronized
    fun update(file: File, transform: (String) -> String) {
        val before = read(file)
        val after = transform(before)
        if (after != before) replace(file, after)
    }

    /** False means trust changed while a user decision was pending; verify again. */
    @Synchronized
    fun commitIfUnchanged(file: File, expected: String, replacement: String): Boolean {
        if (read(file) != expected) return false
        if (replacement != expected) replace(file, replacement)
        return true
    }

    private fun replace(file: File, text: String) {
        val parent = file.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create host trust directory" }
        val temp = File.createTempFile("known-hosts-", ".tmp", parent)
        try {
            FileOutputStream(temp).use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            // No truncate-in-place or non-atomic fallback: preserve old trust on failure.
            Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}
