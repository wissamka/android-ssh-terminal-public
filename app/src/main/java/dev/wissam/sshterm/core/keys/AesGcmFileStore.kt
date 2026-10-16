package dev.wissam.sshterm.core.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts small blobs (imported/generated private key files) at rest with an AES-256-GCM key
 * that lives in Android Keystore, so the wrapping key itself is never on disk.
 *
 * Legacy writes remain compatible with generation/import. Explicit V2 migration uses an auth-per-use
 * Keystore AES key and keeps a recovery copy until a separate authorized finalization. V2 reads never
 * fall back to the legacy key or recovery ciphertext.
 */
@Singleton
class AesGcmFileStore @Inject constructor() {

    private val migrationMutex = Mutex()
    private val commitLock = Any()
    private val replacement = KeyFileReplacement { directory ->
        val fd = android.system.Os.open(directory.absolutePath,
            android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    @Synchronized
    private fun masterKey(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        check(create) { "Wrapping key is missing; existing encrypted material has been preserved." }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encryptToFile(plaintext: ByteArray, file: File): Unit = synchronized(commitLock) {
        // Compatibility path for existing generation/import UI. First authorized use may migrate.
        check(!file.exists()) { "Refusing to overwrite existing key material" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(create = true))
        val ciphertext = cipher.doFinal(plaintext)
        file.parentFile?.mkdirs()
        // Write to a temp file then atomically rename, so an interrupted write (process death,
        // full disk) can never leave a half-written file in place of usable key material.
        val tmp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(cipher.iv.size)
                out.write(cipher.iv)
                out.write(ciphertext)
                out.fd.sync()
            }
            // Android app storage supports atomic replacement. Never delete the old file to retry.
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    fun decryptFromFile(file: File): ByteArray {
        val bytes = readEnvelope(file)
        if (VersionedKeyFile.isVersioned(bytes)) throw KeyFileAuthorizationRequiredException()
        return decryptLegacy(bytes)
    }

    private fun decryptLegacy(bytes: ByteArray): ByteArray {
        // Guard against a truncated/corrupt file: need at least the length byte, one IV byte, and
        // the GCM tag. Without this, bytes[0] / copyOfRange throw IndexOutOfBounds on empty input.
        require(bytes.isNotEmpty()) { "Key file is empty or corrupt." }
        val ivLength = bytes[0].toInt()
        require(ivLength in 1..16 && bytes.size >= 1 + ivLength + 16) { "Key file is corrupt." }
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(create = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /** True means a V2 envelope is present, not a claim of hardware backing or valid ciphertext. */
    fun hasAuthBoundEnvelope(file: File): Boolean = VersionedKeyFile.isVersioned(readEnvelope(file))

    /** Non-prompting availability/policy inspection, not ciphertext authentication. */
    fun validateAuthBoundPolicy(file: File) {
        VersionedKeyFile.iv(readEnvelope(file))
        authBoundKey(create = false)
    }

    fun hasLegacyRecovery(file: File): Boolean = File(file.parentFile, "${file.name}.legacy-recovery").exists()

    /** Explicit authorized read. Legacy files remain readable through the compatibility API;
     * this method refuses legacy input so callers cannot mistake it for cryptographic authorization.
     */
    suspend fun decryptAuthorized(file: File, authorizer: KeyFileAuthorizer): ByteArray {
        val bytes = readEnvelope(file)
        return decryptV2(file.name, bytes, authorizer, KeyFileAuthorizationRequest.Purpose.DECRYPT)
    }

    /** Two fresh biometric operations: encrypt new envelope, then decrypt/compare for verification.
     * Legacy primary survives cancellation/failure before commit. A durable recovery copy precedes
     * promotion and remains until retireLegacyRecoveryAuthorized succeeds. No silent fallback.
     * Return value is the recovery file; retaining it means at-rest auth binding is NOT complete.
     */
    suspend fun migrateLegacyAuthorized(file: File, authorizer: KeyFileAuthorizer): File = migrationMutex.withLock {
        val original = readEnvelope(file)
        check(!VersionedKeyFile.isVersioned(original)) { "Already versioned; use authorized decrypt/recovery cleanup" }
        val plaintext = decryptLegacy(original)
        try {
            val encrypt = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, authBoundKey(create = true, directory = file.parentFile)) }
            authorize(file.name, KeyFileAuthorizationRequest.Purpose.ENCRYPT_MIGRATION, encrypt, authorizer)
            VersionedKeyFile.bind(encrypt, file.name)
            val candidate = VersionedKeyFile.encrypt(encrypt, plaintext)
            val verified = decryptV2(file.name, candidate, authorizer, KeyFileAuthorizationRequest.Purpose.VERIFY_MIGRATION)
            try { check(java.security.MessageDigest.isEqual(plaintext, verified)) { "Migration verification failed" } }
            finally { verified.fill(0) }
            currentCoroutineContext().ensureActive()
            synchronized(commitLock) { replacement.commit(file, original, candidate) }
        } finally { plaintext.fill(0) }
    }

    /** Separate explicit finalization, after a fresh authorized decrypt of the durable primary.
     * On failure recovery remains. Caller must not describe migration as fully protected until
     * this returns and the recovery file is gone. The shared legacy master alias is never removed.
     */
    suspend fun retireLegacyRecoveryAuthorized(file: File, authorizer: KeyFileAuthorizer) = migrationMutex.withLock {
        val bytes = readEnvelope(file)
        val plaintext = decryptV2(file.name, bytes, authorizer, KeyFileAuthorizationRequest.Purpose.VERIFY_MIGRATION)
        plaintext.fill(0)
        currentCoroutineContext().ensureActive()
        synchronized(commitLock) { replacement.retireRecovery(file, bytes) }
    }

    private suspend fun decryptV2(storageId: String, bytes: ByteArray, authorizer: KeyFileAuthorizer,
        purpose: KeyFileAuthorizationRequest.Purpose): ByteArray {
        val iv = VersionedKeyFile.iv(bytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, authBoundKey(create = false), GCMParameterSpec(128, iv))
        }
        authorize(storageId, purpose, cipher, authorizer)
        VersionedKeyFile.bind(cipher, storageId)
        return VersionedKeyFile.decrypt(cipher, bytes)
    }

    private suspend fun authorize(storageId: String, purpose: KeyFileAuthorizationRequest.Purpose,
        cipher: Cipher, authorizer: KeyFileAuthorizer) {
        val allowed = withTimeout(90_000) { authorizer.authorize(KeyFileAuthorizationRequest(storageId, purpose, cipher)) }
        currentCoroutineContext().ensureActive()
        if (!allowed) throw KeyFileAuthorizationDeniedException()
    }

    @Synchronized
    private fun authBoundKey(create: Boolean, directory: File? = null): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(AUTH_BOUND_ALIAS, null) as? SecretKey)?.let { key ->
            val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            check(KeyAuthenticationPolicy.isPerOperation(info.isUserAuthenticationRequired, info.userAuthenticationValidityDurationSeconds)) {
                "Auth-bound alias has incompatible policy; material preserved"
            }
            return key
        }
        // Never replace a missing V2 alias if it already protects any file in this key directory.
        if (create) check(directory != null && directory.listFiles()?.none { candidate ->
            candidate.isFile && candidate.inputStream().use { input ->
                val prefix = ByteArray(4)
                input.read(prefix) == 4 && VersionedKeyFile.isVersioned(prefix)
            }
        } == true) { "Existing V2 material has no wrapping key; recovery required" }
        check(create && !ks.containsAlias(AUTH_BOUND_ALIAS)) { "Auth-bound wrapping key missing or unusable; material preserved" }
        val spec = KeyGenParameterSpec.Builder(AUTH_BOUND_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(spec.build())
        }.generateKey()
    }

    /** Explicit user deletion only. Migration commits detect a concurrently deleted primary. */
    fun deleteKeyFiles(file: File) = synchronized(commitLock) {
        val recovery = File(file.parentFile, "${file.name}.legacy-recovery")
        check(!recovery.exists() || recovery.delete()) { "Could not delete key recovery material" }
        check(!file.exists() || file.delete()) { "Could not delete key material" }
    }

    private fun readEnvelope(file: File): ByteArray {
        require(file.length() <= VersionedKeyFile.MAX_FILE_BYTES) { "Key file too large" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= VersionedKeyFile.MAX_FILE_BYTES) { "Key file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AUTH_BOUND_ALIAS = "sshterm-key-file-auth-v2"
        const val MASTER_KEY_ALIAS = "sshterm-key-file-master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
