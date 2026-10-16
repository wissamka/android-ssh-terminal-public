package dev.wissam.sshterm.core.keys

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType

/** Encoders for OpenSSH public-key lines and the openssh-key-v1 private container. */
object OpenSshKeyCodec {

    /** authorized_keys line: `<type> <base64 wire blob> <comment>`. */
    fun publicKeyLine(key: PublicKey, comment: String): String {
        val type = KeyType.fromKey(key)
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "$type ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    /**
     * Unencrypted openssh-key-v1 container for an Ed25519 key from its raw 32-byte seed and
     * public point. (Encryption at rest is provided by [AesGcmFileStore] around the whole file,
     * not by the container's own KDF.)
     */
    fun ed25519PrivateKeyFile(seed: ByteArray, publicKey: ByteArray, comment: String): String {
        require(seed.size == 32 && publicKey.size == 32)

        val pubBlob = sshString("ssh-ed25519".toByteArray()) + sshString(publicKey)

        val check = SecureRandom().nextInt()
        val privateBlock = ByteArrayOutputStream().apply {
            write(int32(check)); write(int32(check))
            write(sshString("ssh-ed25519".toByteArray()))
            write(sshString(publicKey))
            write(sshString(seed + publicKey))
            write(sshString(comment.toByteArray(StandardCharsets.UTF_8)))
        }.toByteArray().let { block ->
            // Pad with 1,2,3… to the cipher block size (8 for "none").
            var padded = block
            var i = 1
            while (padded.size % 8 != 0) padded += i++.toByte()
            padded
        }

        val payload = ByteArrayOutputStream().apply {
            write("openssh-key-v1".toByteArray()); write(0)
            write(sshString("none".toByteArray()))   // ciphername
            write(sshString("none".toByteArray()))   // kdfname
            write(sshString(ByteArray(0)))            // kdfoptions
            write(int32(1))                           // number of keys
            write(sshString(pubBlob))
            write(sshString(privateBlock))
        }.toByteArray()

        val b64 = Base64.getEncoder().encodeToString(payload).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /**
     * Passphrase-protected openssh-key-v1 container for an Ed25519 key (aes256-ctr + bcrypt KDF),
     * the exact format `ssh-keygen` produces. The bcrypt_pbkdf comes from sshj's bundled jBCrypt
     * so the KDF is not hand-rolled. Used for exporting a generated key to another machine.
     */
    fun ed25519EncryptedPrivateKeyFile(
        seed: ByteArray,
        publicKey: ByteArray,
        comment: String,
        passphrase: CharArray,
        rounds: Int = 16,
    ): String {
        require(seed.size == 32 && publicKey.size == 32)
        require(passphrase.isNotEmpty()) { "An export passphrase is required." }

        val pubBlob = sshString("ssh-ed25519".toByteArray()) + sshString(publicKey)

        val check = SecureRandom().nextInt()
        val seedAndPub = seed + publicKey
        var privateBlock = ByteArrayOutputStream().apply {
            write(int32(check)); write(int32(check))
            write(sshString("ssh-ed25519".toByteArray()))
            write(sshString(publicKey))
            write(sshString(seedAndPub))
            write(sshString(comment.toByteArray(StandardCharsets.UTF_8)))
        }.toByteArray()
        seedAndPub.fill(0)
        // Pad to the AES block size (16) with 1,2,3…
        var i = 1
        while (privateBlock.size % 16 != 0) privateBlock += i++.toByte()

        // KDF: bcrypt_pbkdf(passphrase, salt, rounds) -> 48 bytes = 32 key + 16 IV.
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pwBytes = passwordBytes(passphrase)
        val derived = ByteArray(48)
        BCrypt().pbkdf(pwBytes, salt, rounds, derived)
        pwBytes.fill(0)
        val aesKey = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(privateBlock)
        privateBlock.fill(0)  // held the plaintext seed; zero it once encrypted
        aesKey.fill(0); iv.fill(0); derived.fill(0)

        val kdfOptions = sshString(salt) + int32(rounds)

        val payload = ByteArrayOutputStream().apply {
            write("openssh-key-v1".toByteArray()); write(0)
            write(sshString("aes256-ctr".toByteArray()))    // ciphername
            write(sshString("bcrypt".toByteArray()))         // kdfname
            write(sshString(kdfOptions))                     // kdfoptions: salt + rounds
            write(int32(1))                                  // number of keys
            write(sshString(pubBlob))
            write(sshString(encrypted))
        }.toByteArray()

        val b64 = Base64.getEncoder().encodeToString(payload).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /**
     * OpenSSH passes the raw UTF-8 passphrase (strlen, no NUL) to bcrypt_pbkdf. Encodes the
     * CharArray directly (no intermediate String) so the plaintext isn't left in an
     * un-zeroable String on the heap.
     */
    private fun passwordBytes(passphrase: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(passphrase))
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        // Best-effort wipe of the encoder's backing array.
        if (encoded.hasArray()) encoded.array().fill(0)
        return out
    }

    private fun int32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun sshString(data: ByteArray) = int32(data.size) + data
}
