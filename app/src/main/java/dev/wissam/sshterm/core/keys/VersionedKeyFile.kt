package dev.wissam.sshterm.core.keys

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher

/** V2 envelope. The version and stable storage filename are authenticated as GCM AAD. */
internal object VersionedKeyFile {
    private val magic = byteArrayOf(0x53, 0x54, 0x4b, 0x46)
    private val header = magic + byteArrayOf(2, 12)
    const val MAX_FILE_BYTES = 128 * 1024
    fun isVersioned(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(magic)
    fun iv(bytes: ByteArray): ByteArray {
        require(bytes.size in (header.size + 12 + 16)..MAX_FILE_BYTES) { "Invalid key-file length" }
        require(bytes.copyOfRange(0, header.size).contentEquals(header)) { "Unsupported key-file envelope" }
        return bytes.copyOfRange(header.size, header.size + 12)
    }
    fun bind(cipher: Cipher, storageId: String) {
        cipher.updateAAD(header + storageId.toByteArray(StandardCharsets.UTF_8))
    }
    fun encrypt(cipher: Cipher, plaintext: ByteArray): ByteArray {
        require(cipher.iv.size == 12)
        require(plaintext.size <= MAX_FILE_BYTES - header.size - 12 - 16)
        return header + cipher.iv + cipher.doFinal(plaintext)
    }
    fun decrypt(cipher: Cipher, bytes: ByteArray): ByteArray {
        iv(bytes) // Strict version/length validation; never reinterpret corrupt V2 as legacy.
        return cipher.doFinal(bytes, header.size + 12, bytes.size - header.size - 12)
    }
}
