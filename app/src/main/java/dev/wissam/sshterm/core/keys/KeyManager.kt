package dev.wissam.sshterm.core.keys

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.data.db.KeyProtection
import java.io.File
import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider

enum class GeneratedKeyAlgorithm { ED25519, ECDSA_P256, RSA_3072, RSA_4096 }

/**
 * Key generation, import, and loading. Two storage routes (not verified hardware tiers):
 *
 * New Keystore keys require per-operation strong biometric authorization. Existing aliases keep
 * their original policy (including legacy 60-second windows). New/imported software files initially
 * use legacy wrapping; explicit authorized migration upgrades them to V2 per-operation wrapping.
 * Recovery ciphertext must be retired before claiming at-rest auth binding. SshEngine separately
 * enforces fresh application authorization at every signature; decrypted providers retain material.
 * The legacy HARDWARE_KEYSTORE enum is not proof of hardware backing; inspect KeyInfo.
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStore: AesGcmFileStore,
    private val metadataInspector: KeystoreMetadataInspector,
) {

    private val keyDir: File get() = File(context.filesDir, "keys")

    /** Inspect existing aliases on demand without changing database values or key material. */
    fun protectionMetadata(entity: KeyEntity): KeyProtectionMetadata = when (entity.protection) {
        KeyProtection.HARDWARE_KEYSTORE -> metadataInspector.inspect(entity.storageRef)
        KeyProtection.SOFTWARE_ENCRYPTED -> KeyProtectionMetadata.ENCRYPTED_FILE
    }

    // ---- Generation -------------------------------------------------------------------------

    /**
     * Returns an unsaved [KeyEntity] describing the new key; caller persists it.
     * [comment] is the ssh-keygen -C equivalent, embedded in the public key line
     * (conventionally an email); defaults to the key's name.
     */
    fun generate(
        name: String,
        algorithm: GeneratedKeyAlgorithm,
        comment: String = name,
    ): KeyEntity = when (algorithm) {
        GeneratedKeyAlgorithm.ED25519 -> generateEd25519(name, comment)
        GeneratedKeyAlgorithm.ECDSA_P256 -> generateKeystoreKey(name, comment, ec = true, bits = 256)
        GeneratedKeyAlgorithm.RSA_3072 -> generateKeystoreKey(name, comment, ec = false, bits = 3072)
        GeneratedKeyAlgorithm.RSA_4096 -> generateKeystoreKey(name, comment, ec = false, bits = 4096)
    }

    private fun generateKeystoreKey(name: String, comment: String, ec: Boolean, bits: Int): KeyEntity {
        val alias = "sshkey-" + UUID.randomUUID().toString()
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (ec) {
            builder.setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        } else {
            builder.setKeySize(bits)
            builder.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        }
        // New aliases only. Existing keys cannot have their authorization policy changed in place.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        val algorithmName = if (ec) KeyProperties.KEY_ALGORITHM_EC else KeyProperties.KEY_ALGORITHM_RSA
        val generator = KeyPairGenerator.getInstance(algorithmName, ANDROID_KEYSTORE)

        val pair = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.initialize(builder.setIsStrongBoxBacked(true).build())
                generator.generateKeyPair()
            } catch (e: StrongBoxUnavailableException) {
                generator.initialize(builder.setIsStrongBoxBacked(false).build())
                generator.generateKeyPair()
            }
        } else {
            generator.initialize(builder.build())
            generator.generateKeyPair()
        }

        val sshAlgorithm = if (ec) "ecdsa-sha2-nistp256" else "ssh-rsa"
        return KeyEntity(
            name = name,
            algorithm = sshAlgorithm,
            protection = KeyProtection.HARDWARE_KEYSTORE,
            storageRef = alias,
            publicKeyOpenSsh = OpenSshKeyCodec.publicKeyLine(pair.public, comment),
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun generateEd25519(name: String, comment: String): KeyEntity {
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val pair = generator.generateKeyPair()

        val seed = ASN1OctetString.getInstance(
            PrivateKeyInfo.getInstance(pair.private.encoded).parsePrivateKey()
        ).octets
        val rawPublic = SubjectPublicKeyInfo.getInstance(pair.public.encoded).publicKeyData.bytes

        val fileName = "key-" + UUID.randomUUID().toString()
        val text = OpenSshKeyCodec.ed25519PrivateKeyFile(seed, rawPublic, comment)
        fileStore.encryptToFile(text.toByteArray(), File(keyDir, fileName))
        seed.fill(0)

        return KeyEntity(
            name = name,
            algorithm = "ssh-ed25519",
            protection = KeyProtection.SOFTWARE_ENCRYPTED,
            storageRef = fileName,
            publicKeyOpenSsh = "ssh-ed25519 " +
                java.util.Base64.getEncoder().encodeToString(
                    sshEd25519PublicBlob(rawPublic)
                ) + " " + comment,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun sshEd25519PublicBlob(rawPublic: ByteArray): ByteArray {
        fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val type = "ssh-ed25519".toByteArray()
        return int32(type.size) + type + int32(rawPublic.size) + rawPublic
    }

    // ---- Import -----------------------------------------------------------------------------

    /**
     * Validates and stores an existing private key (OpenSSH v1, PEM/PKCS8, PuTTY). The original
     * text is preserved (including its own passphrase protection, if any) and encrypted at rest.
     * Throws if the text is not a parseable private key.
     */
    fun import(name: String, privateKeyText: String, passphrase: CharArray?): KeyEntity {
        // A private key file is a few KB at most; reject oversized pastes before they hit the
        // parser/cipher so a pathological input can't exhaust memory.
        require(privateKeyText.length <= MAX_KEY_TEXT_CHARS) {
            "Key text is too large to be a private key."
        }
        val provider = softwareKeyProvider(privateKeyText, passphrase)
        val publicKey = provider.public // Forces parsing/decryption; throws on bad key or passphrase.

        val fileName = "key-" + UUID.randomUUID().toString()
        fileStore.encryptToFile(privateKeyText.toByteArray(), File(keyDir, fileName))

        return KeyEntity(
            name = name,
            algorithm = provider.type.toString(),
            protection = KeyProtection.SOFTWARE_ENCRYPTED,
            storageRef = fileName,
            publicKeyOpenSsh = OpenSshKeyCodec.publicKeyLine(publicKey, name),
            createdAt = System.currentTimeMillis(),
        )
    }

    // ---- Loading for authentication ----------------------------------------------------------

    /**
     * Loads key material; this is NOT signing authorization. Connections must use SshEngine's
     * per-signature authorizer. Software providers retain decrypted material for their lifetime.
     */
    fun keyProviderFor(entity: KeyEntity, passphrase: CharArray? = null): KeyProvider =
        when (entity.protection) {
            KeyProtection.HARDWARE_KEYSTORE -> KeystoreKeyProvider(entity.storageRef)
            KeyProtection.SOFTWARE_ENCRYPTED -> {
                val text = String(fileStore.decryptFromFile(File(keyDir, entity.storageRef)))
                softwareKeyProvider(text, passphrase)
            }
        }

    /** Public feelers load no secrets. The closure runs anew inside every actual signature. */
    fun deferredKeyProviderFor(
        entity: KeyEntity,
        authorizer: KeyFileAuthorizer,
        requestPassphrase: suspend () -> CharArray?,
    ): KeyProvider {
        if (entity.protection == KeyProtection.HARDWARE_KEYSTORE) return KeystoreKeyProvider(entity.storageRef)
        val blob = java.util.Base64.getDecoder().decode(entity.publicKeyOpenSsh.trim().split(Regex("\\s+"))[1])
        val publicKey = net.schmizz.sshj.common.Buffer.PlainBuffer(blob).readPublicKey()
        return DeferredKeyProvider(publicKey) {
            val file = File(keyDir, entity.storageRef)
            val bytes = if (fileStore.hasAuthBoundEnvelope(file)) fileStore.decryptAuthorized(file, authorizer)
                else fileStore.decryptFromFile(file)
            try {
                val text = String(bytes)
                val provider = try { softwareKeyProvider(text, null).also { it.private } }
                catch (e: Exception) {
                    val passphrase = requestPassphrase() ?: throw KeyFileAuthorizationDeniedException()
                    try { softwareKeyProvider(text, passphrase).also { it.private } }
                    finally { passphrase.fill('\u0000') }
                }
                check(java.security.MessageDigest.isEqual(
                    net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(provider.public).compactData,
                    net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(publicKey).compactData,
                )) {
                    "Private key does not match saved public key"
                }
                provider.private
            } finally { bytes.fill(0) }
        }
    }

    /** Authorized V2 load for parent UI integration. Legacy generation/import remains unchanged.
     * This decrypt grant is not a signing grant: still pass a SigningAuthorizer to SshAuthMethod.
     */
    suspend fun keyProviderForAuthorized(
        entity: KeyEntity,
        authorizer: KeyFileAuthorizer,
        passphrase: CharArray? = null,
    ): KeyProvider {
        if (entity.protection == KeyProtection.HARDWARE_KEYSTORE) return KeystoreKeyProvider(entity.storageRef)
        val plaintext = fileStore.decryptAuthorized(File(keyDir, entity.storageRef), authorizer)
        return try { softwareKeyProvider(String(plaintext), passphrase) } finally { plaintext.fill(0) }
    }

    suspend fun migrateSoftwareKeyAuthorized(entity: KeyEntity, authorizer: KeyFileAuthorizer): File {
        require(entity.protection == KeyProtection.SOFTWARE_ENCRYPTED)
        return fileStore.migrateLegacyAuthorized(File(keyDir, entity.storageRef), authorizer)
    }

    suspend fun retireSoftwareKeyRecoveryAuthorized(entity: KeyEntity, authorizer: KeyFileAuthorizer) {
        require(entity.protection == KeyProtection.SOFTWARE_ENCRYPTED)
        fileStore.retireLegacyRecoveryAuthorized(File(keyDir, entity.storageRef), authorizer)
    }

    fun hasAuthBoundEnvelope(entity: KeyEntity): Boolean =
        entity.protection == KeyProtection.SOFTWARE_ENCRYPTED &&
            fileStore.hasAuthBoundEnvelope(File(keyDir, entity.storageRef))

    fun validateSoftwareWrapperPolicy(entity: KeyEntity) {
        require(entity.protection == KeyProtection.SOFTWARE_ENCRYPTED)
        fileStore.validateAuthBoundPolicy(File(keyDir, entity.storageRef))
    }

    fun hasLegacyRecovery(entity: KeyEntity): Boolean =
        entity.protection == KeyProtection.SOFTWARE_ENCRYPTED &&
            fileStore.hasLegacyRecovery(File(keyDir, entity.storageRef))

    private fun softwareKeyProvider(privateKeyText: String, passphrase: CharArray?): FileKeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(privateKeyText, false)
        val provider: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Unrecognized private key format: $format")
        }
        val finder = passphrase?.let {
            object : PasswordFinder {
                override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = it.copyOf()
                override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = false
            }
        }
        provider.init(StringReader(privateKeyText), finder)
        return provider
    }

    // ---- Export ------------------------------------------------------------------------------

    class NotExportableException(message: String) : Exception(message)

    /**
     * True when [entity] can be exported: only software Ed25519 keys, which we can re-encode as a
     * freshly passphrase-encrypted OpenSSH file. Keystore keys are non-exportable by design, and
     * other software key types are withheld rather than risk handing out unencrypted material
     * (their stored form may be unencrypted — see the export flow's security review).
     */
    fun canExport(entity: KeyEntity): Boolean =
        entity.protection == KeyProtection.SOFTWARE_ENCRYPTED && entity.algorithm == "ssh-ed25519"

    /**
     * Exports a software Ed25519 key as a passphrase-protected OpenSSH private key (openssh-key-v1,
     * aes256-ctr + bcrypt), suitable for moving to another machine. Always re-encrypts under
     * [exportPassphrase]; never emits unencrypted material. The caller must have passed the
     * app-lock/biometric gate first.
     */
    fun exportKey(entity: KeyEntity, exportPassphrase: CharArray, storedPassphrase: CharArray? = null): String {
        if (!canExport(entity)) {
            throw NotExportableException(
                when {
                    entity.protection == KeyProtection.HARDWARE_KEYSTORE ->
                        "“${entity.name}” uses Android Keystore: its private key cannot be exported by this app."
                    else ->
                        "Export currently supports Ed25519 keys only (“${entity.name}” is ${entity.algorithm})."
                }
            )
        }
        require(exportPassphrase.isNotEmpty()) { "An export passphrase is required." }

        val storedText = String(fileStore.decryptFromFile(File(keyDir, entity.storageRef)))
        return exportText(entity, storedText, exportPassphrase, storedPassphrase)
    }

    suspend fun exportKeyAuthorized(
        entity: KeyEntity,
        exportPassphrase: CharArray,
        authorizer: KeyFileAuthorizer,
        storedPassphrase: CharArray? = null,
    ): String {
        if (!canExport(entity)) throw NotExportableException("Only software Ed25519 keys can be exported")
        require(exportPassphrase.isNotEmpty()) { "An export passphrase is required." }
        val bytes = fileStore.decryptAuthorized(File(keyDir, entity.storageRef), authorizer)
        return try { exportText(entity, String(bytes), exportPassphrase, storedPassphrase) }
        finally { bytes.fill(0) }
    }

    private fun exportText(entity: KeyEntity, storedText: String, exportPassphrase: CharArray, storedPassphrase: CharArray?): String {
        val provider = softwareKeyProvider(storedText, storedPassphrase)
        val keyPair = java.security.KeyPair(provider.public, provider.private)
        val seed = ASN1OctetString.getInstance(
            PrivateKeyInfo.getInstance(keyPair.private.encoded).parsePrivateKey()
        ).octets
        val rawPublic = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded).publicKeyData.bytes
        val text = OpenSshKeyCodec.ed25519EncryptedPrivateKeyFile(
            seed, rawPublic, entity.name, exportPassphrase
        )
        seed.fill(0)
        return text
    }

    // ---- Deletion ----------------------------------------------------------------------------

    fun deleteMaterial(entity: KeyEntity) {
        when (entity.protection) {
            KeyProtection.HARDWARE_KEYSTORE -> {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                runCatching { ks.deleteEntry(entity.storageRef) }
            }
            KeyProtection.SOFTWARE_ENCRYPTED -> {
                fileStore.deleteKeyFiles(File(keyDir, entity.storageRef))
            }
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAX_KEY_TEXT_CHARS = 64 * 1024
    }
}
