package dev.wissam.sshterm.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AuthType { PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE }

/** How the private key material is protected; surfaced explicitly in the UI (real threat-model difference). */
enum class KeyProtection {
    /** Generated inside Android Keystore; non-exportable, signs in secure hardware. */
    HARDWARE_KEYSTORE,

    /** Key material encrypted at rest with a Keystore-wrapped AES key; decrypted transiently to memory. */
    SOFTWARE_ENCRYPTED,
}

@Entity(
    tableName = "keys",
    indices = [Index(value = ["name"], unique = true)],
)
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ssh algorithm, e.g. ssh-ed25519, ecdsa-sha2-nistp256, ssh-rsa. */
    val algorithm: String,
    val protection: KeyProtection,
    /** Android Keystore alias (HARDWARE_KEYSTORE) or encrypted-file name (SOFTWARE_ENCRYPTED). */
    val storageRef: String,
    /** Public key in OpenSSH authorized_keys line format. */
    val publicKeyOpenSsh: String,
    val createdAt: Long,
)

/**
 * A reusable username + auth bundle, so multiple profiles for the same account don't each
 * duplicate the username/key choice. A profile referencing an identity uses the identity's
 * username/authType/keyId instead of its own inline fields.
 */
@Entity(
    tableName = "identities",
    foreignKeys = [
        ForeignKey(
            entity = KeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["name"], unique = true), Index("keyId")],
)
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val username: String,
    val authType: AuthType,
    val keyId: Long? = null,
)

@Entity(
    tableName = "profiles",
    foreignKeys = [
        ForeignKey(
            entity = KeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpHostProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        // identityId is intentionally NOT a DB foreign key: SQLite can't add an enforced FK via
        // ALTER TABLE ADD COLUMN, so referential cleanup is done at the app layer (see
        // IdentityRepository.delete). The index is kept for lookup speed.
    ],
    indices = [Index("keyId"), Index("jumpHostProfileId"), Index("identityId")],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Passwords are NEVER stored; PASSWORD profiles prompt at connect time. */
    val authType: AuthType,
    val keyId: Long? = null,
    /** ProxyJump equivalent: connect through this profile first. */
    val jumpHostProfileId: Long? = null,
    /** When set, the profile uses this identity's username/authType/keyId instead of its own. */
    val identityId: Long? = null,
    val termType: String = "xterm-256color",
    val colorScheme: String = "default",
    val lastConnectedAt: Long? = null,
)

enum class ForwardKind { LOCAL, REMOTE }

@Entity(
    tableName = "forwarding_rules",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ForwardingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val kind: ForwardKind,
    /** Listener side. LOCAL: on-device bind (usually 127.0.0.1). REMOTE: server bind. */
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    /** Destination side, resolved from the server (LOCAL) or the device (REMOTE). */
    val targetHost: String,
    val targetPort: Int,
    val enabled: Boolean = true,
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The command text; may be multi-line. Inserted into the input bar, never auto-run. */
    val body: String,
    val createdAt: Long,
)

@Entity(
    tableName = "command_history",
    indices = [Index(value = ["profileId", "command"], unique = true)],
)
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    /** Scope: per-profile by default so suggestions don't leak across hosts; null = global tier. */
    val profileId: Long? = null,
    val useCount: Int = 1,
    val lastUsedAt: Long,
)
