package dev.wissam.sshterm.core.keys

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Caller serializes commits and has already authenticated/verified replacement plaintext.
 * A backup is retained until explicit cleanup after a durable commit. Never automatically read
 * that backup as a fallback for a corrupt/missing/invalidated V2 file.
 */
internal class KeyFileReplacement(private val syncDirectory: (File) -> Unit) {
    fun commit(file: File, expected: ByteArray, verifiedReplacement: ByteArray): File {
        check(file.readBytes().contentEquals(expected)) { "Key file changed during authorization; retry required" }
        val backup = File(file.parentFile, "${file.name}.legacy-recovery")
        if (backup.exists()) {
            check(backup.readBytes().contentEquals(expected)) { "Conflicting recovery file; manual recovery required" }
        } else {
            writeAtomically(backup, expected)
        }
        // Ensure a verified old ciphertext copy is durable BEFORE replacement is promoted.
        check(backup.readBytes().contentEquals(expected))
        syncDirectory(file.absoluteFile.parentFile!!)
        writeAtomically(file, verifiedReplacement)
        check(file.readBytes().contentEquals(verifiedReplacement))
        return backup
    }

    /** Only call with the exact V2 bytes from a successful authorized decrypt of the primary. */
    fun retireRecovery(file: File, verifiedPrimary: ByteArray) {
        check(file.readBytes().contentEquals(verifiedPrimary)) { "Primary changed; recovery retained" }
        syncDirectory(file.absoluteFile.parentFile!!)
        val backup = File(file.parentFile, "${file.name}.legacy-recovery")
        if (backup.exists()) {
            check(backup.delete()) { "Could not retire legacy recovery file" }
            syncDirectory(file.absoluteFile.parentFile!!)
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File.createTempFile("${file.name}.", ".tmp", file.absoluteFile.parentFile)
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(file.absoluteFile.parentFile!!)
        } finally { tmp.delete() }
    }
}
