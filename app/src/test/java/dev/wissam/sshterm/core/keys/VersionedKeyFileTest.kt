package dev.wissam.sshterm.core.keys

import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class VersionedKeyFileTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        VersionedKeyFile.bind(cipher, id)
        return VersionedKeyFile.encrypt(cipher, plaintext)
    }
    private fun decrypt(id: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, VersionedKeyFile.iv(bytes)))
        }
        VersionedKeyFile.bind(cipher, id)
        return VersionedKeyFile.decrypt(cipher, bytes)
    }
    @Test fun `roundtrip authenticates version storage identity and ciphertext`() {
        val bytes = encrypt("key-123", byteArrayOf(1, 2, 3))
        assertTrue(VersionedKeyFile.isVersioned(bytes))
        assertArrayEquals(byteArrayOf(1, 2, 3), decrypt("key-123", bytes))
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt("key-other", bytes) }
        val corrupt = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt("key-123", corrupt) }
        val future = bytes.copyOf().apply { this[4] = 3 }
        assertTrue(VersionedKeyFile.isVersioned(future))
        assertThrows(IllegalArgumentException::class.java) { decrypt("key-123", future) }
    }
    @Test fun `reject truncated envelope and do not classify legacy as versioned`() {
        assertFalse(VersionedKeyFile.isVersioned(byteArrayOf(12, 0, 0, 0)))
        val bytes = encrypt("key", byteArrayOf(1))
        for (size in 0 until 34) {
            assertThrows(IllegalArgumentException::class.java) { VersionedKeyFile.iv(bytes.copyOf(size)) }
        }
    }
    @Test fun `successful commit preserves old ciphertext until separate verified finalization`() {
        val dir = Files.createTempDirectory("key-migration-test").toFile()
        try {
            val file = java.io.File(dir, "key")
            val old = byteArrayOf(12, 1, 2)
            val fresh = encrypt(file.name, byteArrayOf(8))
            file.writeBytes(old)
            val replacement = KeyFileReplacement { }
            val backup = replacement.commit(file, old, fresh)
            assertArrayEquals(old, backup.readBytes())
            assertArrayEquals(fresh, file.readBytes())
            assertArrayEquals(byteArrayOf(8), decrypt(file.name, file.readBytes()))
            replacement.retireRecovery(file, fresh)
            assertFalse(backup.exists())
            assertArrayEquals(fresh, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `failure at each directory sync retains old primary or recovery copy`() {
        for (failAt in 1..3) {
            val dir = Files.createTempDirectory("key-migration-fault").toFile()
            try {
                val file = java.io.File(dir, "key")
                val old = byteArrayOf(12, 3, 4)
                val fresh = encrypt(file.name, byteArrayOf(9))
                file.writeBytes(old)
                var syncs = 0
                val replacement = KeyFileReplacement { if (++syncs == failAt) throw java.io.IOException("injected sync failure") }
                assertThrows(java.io.IOException::class.java) { replacement.commit(file, old, fresh) }
                val backup = java.io.File(dir, "key.legacy-recovery")
                assertTrue(file.readBytes().contentEquals(old) || (backup.exists() && backup.readBytes().contentEquals(old)))
                assertTrue(file.readBytes().contentEquals(old) || file.readBytes().contentEquals(fresh))
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun `concurrent change and conflicting recovery cannot be overwritten`() {
        val dir = Files.createTempDirectory("key-migration-conflict").toFile()
        try {
            val file = java.io.File(dir, "key").apply { writeBytes(byteArrayOf(1)) }
            val replacement = KeyFileReplacement { }
            assertThrows(IllegalStateException::class.java) { replacement.commit(file, byteArrayOf(2), byteArrayOf(3)) }
            val backup = java.io.File(dir, "key.legacy-recovery").apply { writeBytes(byteArrayOf(7)) }
            assertThrows(IllegalStateException::class.java) { replacement.commit(file, byteArrayOf(1), byteArrayOf(3)) }
            assertArrayEquals(byteArrayOf(1), file.readBytes())
            assertArrayEquals(byteArrayOf(7), backup.readBytes())
            assertThrows(IllegalStateException::class.java) { replacement.retireRecovery(file, byteArrayOf(3)) }
            assertTrue(backup.exists())
        } finally { dir.deleteRecursively() }
    }
}
