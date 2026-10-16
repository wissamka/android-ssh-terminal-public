package dev.wissam.sshterm.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("SELECT * FROM profiles ORDER BY lastConnectedAt IS NULL, lastConnectedAt DESC, name")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("UPDATE profiles SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun touchLastConnected(id: Long, timestamp: Long)

    /** App-layer referential cleanup for identityId (which is not a DB foreign key). */
    @Query("UPDATE profiles SET identityId = NULL WHERE identityId = :identityId")
    suspend fun clearIdentityRef(identityId: Long)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY name")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun get(id: Long): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(identity: IdentityEntity): Long

    @Update
    suspend fun update(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)
}

@Dao
interface KeyDao {
    @Query("SELECT * FROM keys WHERE storageRef = :storageRef AND protection = :protection LIMIT 1")
    suspend fun findByMaterial(storageRef: String, protection: KeyProtection): KeyEntity?

    @Query("SELECT * FROM keys ORDER BY name")
    fun observeAll(): Flow<List<KeyEntity>>

    @Query("SELECT * FROM keys WHERE id = :id")
    suspend fun get(id: Long): KeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(key: KeyEntity): Long

    @Delete
    suspend fun delete(key: KeyEntity)
}

@Dao
interface ForwardingRuleDao {
    @Query("SELECT * FROM forwarding_rules WHERE profileId = :profileId ORDER BY id")
    fun observeForProfile(profileId: Long): Flow<List<ForwardingRuleEntity>>

    @Query("SELECT * FROM forwarding_rules WHERE profileId = :profileId AND enabled = 1 ORDER BY id")
    suspend fun enabledForProfile(profileId: Long): List<ForwardingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: ForwardingRuleEntity): Long

    @Update
    suspend fun update(rule: ForwardingRuleEntity)

    @Delete
    suspend fun delete(rule: ForwardingRuleEntity)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY name")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY name")
    suspend fun all(): List<SnippetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snippet: SnippetEntity): Long

    @Delete
    suspend fun delete(snippet: SnippetEntity)
}

@Dao
interface CommandHistoryDao {
    /** Global fallback is deliberately opt-in, including for sessions without a profile. */
    @Query(
        "SELECT * FROM command_history WHERE profileId = :profileId " +
            "OR (:includeGlobal = 1 AND profileId IS NULL) " +
            "ORDER BY lastUsedAt DESC LIMIT 2000"
    )
    suspend fun candidatesFor(profileId: Long?, includeGlobal: Boolean = false): List<CommandHistoryEntity>

    @Query("SELECT * FROM command_history WHERE profileId = :profileId AND command = :command")
    suspend fun find(profileId: Long?, command: String): CommandHistoryEntity?

    @Query("SELECT * FROM command_history WHERE profileId IS NULL AND command = :command ORDER BY lastUsedAt DESC, id DESC LIMIT 1")
    suspend fun findGlobal(command: String): CommandHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CommandHistoryEntity)

    @Query("DELETE FROM command_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: Long)

    @Query("DELETE FROM command_history WHERE profileId IS NULL")
    suspend fun clearGlobal()

    @Query("DELETE FROM command_history")
    suspend fun clearAll()

    /**
     * Explicit settings cleanup, not a schema migration. Only exact legacy-copy signatures
     * are removed; ambiguous aggregate/ad-hoc history and every profile row are preserved.
     */
    @Query(
        "DELETE FROM command_history WHERE profileId IS NULL AND EXISTS (" +
            "SELECT 1 FROM command_history AS scoped WHERE scoped.profileId IS NOT NULL " +
            "AND scoped.command = command_history.command " +
            "AND scoped.lastUsedAt = command_history.lastUsedAt " +
            "AND scoped.useCount = command_history.useCount)"
    )
    suspend fun clearLegacyGlobalDuplicates(): Int
}
