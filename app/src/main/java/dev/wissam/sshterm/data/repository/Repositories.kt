package dev.wissam.sshterm.data.repository

import dev.wissam.sshterm.data.db.CommandHistoryDao
import dev.wissam.sshterm.data.db.CommandHistoryEntity
import dev.wissam.sshterm.data.db.ForwardingRuleDao
import dev.wissam.sshterm.data.db.ForwardingRuleEntity
import dev.wissam.sshterm.data.db.KeyDao
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.data.db.ProfileDao
import dev.wissam.sshterm.data.db.ProfileEntity
import dev.wissam.sshterm.core.keys.KeyManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val billing: dev.wissam.sshterm.core.billing.PlayBillingRepository,
) {
    private val creationGate = dev.wissam.sshterm.core.billing.ProfileCreationGate()
    fun observeAll(): Flow<List<ProfileEntity>> = dao.observeAll()
    suspend fun get(id: Long): ProfileEntity? = dao.get(id)
    suspend fun save(profile: ProfileEntity): Long =
        if (profile.id == 0L) creationGate.create(
            entitlement = { billing.state.value.entitlement },
            enforceLimits = billing.enforcesFreeLimits,
            limits = billing.freeLimits,
            count = dao::count,
            insert = { dao.insert(profile) },
        ) else { dao.update(profile); profile.id }
    suspend fun delete(profile: ProfileEntity) = dao.delete(profile)
    suspend fun touchLastConnected(id: Long) = dao.touchLastConnected(id, System.currentTimeMillis())
}

@Singleton
class IdentityRepository @Inject constructor(
    private val dao: dev.wissam.sshterm.data.db.IdentityDao,
    private val profileDao: ProfileDao,
) {
    fun observeAll(): Flow<List<dev.wissam.sshterm.data.db.IdentityEntity>> = dao.observeAll()
    suspend fun get(id: Long): dev.wissam.sshterm.data.db.IdentityEntity? = dao.get(id)
    suspend fun save(identity: dev.wissam.sshterm.data.db.IdentityEntity): Long =
        if (identity.id == 0L) dao.insert(identity) else { dao.update(identity); identity.id }
    suspend fun delete(identity: dev.wissam.sshterm.data.db.IdentityEntity) {
        // Clear the reference from any profile first (identityId isn't a DB foreign key).
        profileDao.clearIdentityRef(identity.id)
        dao.delete(identity)
    }
}

@Singleton
class SnippetRepository @Inject constructor(private val dao: dev.wissam.sshterm.data.db.SnippetDao) {
    fun observeAll(): Flow<List<dev.wissam.sshterm.data.db.SnippetEntity>> = dao.observeAll()
    suspend fun all(): List<dev.wissam.sshterm.data.db.SnippetEntity> = dao.all()
    suspend fun save(name: String, body: String, id: Long = 0): Long = dao.upsert(
        dev.wissam.sshterm.data.db.SnippetEntity(
            id = id, name = name.trim(), body = body, createdAt = System.currentTimeMillis()
        )
    )
    suspend fun delete(snippet: dev.wissam.sshterm.data.db.SnippetEntity) = dao.delete(snippet)
}

@Singleton
class ForwardingRepository @Inject constructor(private val dao: ForwardingRuleDao) {
    fun observeForProfile(profileId: Long): Flow<List<ForwardingRuleEntity>> =
        dao.observeForProfile(profileId)
    suspend fun enabledForProfile(profileId: Long): List<ForwardingRuleEntity> =
        dao.enabledForProfile(profileId)
    suspend fun save(rule: ForwardingRuleEntity): Long =
        if (rule.id == 0L) dao.insert(rule) else { dao.update(rule); rule.id }
    suspend fun delete(rule: ForwardingRuleEntity) = dao.delete(rule)
}

@Singleton
class KeyRepository @Inject constructor(
    private val dao: KeyDao,
    private val keyManager: KeyManager,
) {
    private val materialMutex = kotlinx.coroutines.sync.Mutex()

    fun observeAll(): Flow<List<KeyEntity>> = dao.observeAll()
    suspend fun get(id: Long): KeyEntity? = dao.get(id)
    suspend fun save(key: KeyEntity): Long = materialMutex.withLock { dao.insert(key) }

    /** Creation owns its IO result until the row is committed or safe compensation completes. */
    suspend fun createAndRegister(create: () -> KeyEntity): KeyEntity = materialMutex.withLock {
        registerNewKeyMaterial(
            create = create,
            save = { it.copy(id = dao.insert(it)) },
            findSaved = { dao.findByMaterial(it.storageRef, it.protection) },
            deleteUnregistered = keyManager::deleteMaterial,
        )
    }
    suspend fun delete(key: KeyEntity) = materialMutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            keyManager.deleteMaterial(key)
            dao.delete(key)
        }
    }
}

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: CommandHistoryDao,
    private val settingsRepo: dev.wissam.sshterm.data.settings.SettingsRepository,
) {
    // Serialize read/modify/write and clearing, including SQLite's nullable global scope.
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    suspend fun candidatesFor(profileId: Long?): List<CommandHistoryEntity> {
        val settings = settingsRepo.settings.first()
        return dao.candidatesFor(profileId, settings.globalHistoryFallback)
    }

    /** Never copies a saved profile's command into the global tier. */
    suspend fun record(profileId: Long?, command: String): Unit = mutex.withLock {
        val settings = settingsRepo.settings.first()
        if (!HistoryPolicy.shouldRecord(profileId, settings.recordHistory, settings.globalHistoryFallback)) return@withLock
        val trimmed = HistoryPolicy.commandToRecord(command) ?: return@withLock
        val now = System.currentTimeMillis()
        val existing = if (profileId == null) dao.findGlobal(trimmed) else dao.find(profileId, trimmed)
        dao.upsert(
            existing?.copy(useCount = existing.useCount + 1, lastUsedAt = now)
                ?: CommandHistoryEntity(command = trimmed, profileId = profileId, useCount = 1, lastUsedAt = now)
        )
        _revision.value++
    }

    suspend fun clearAll(): Unit = mutex.withLock { dao.clearAll(); _revision.value++ }
    suspend fun clearGlobal(): Unit = mutex.withLock { dao.clearGlobal(); _revision.value++ }
    suspend fun clearForProfile(profileId: Long): Unit = mutex.withLock {
        dao.clearForProfile(profileId); _revision.value++
    }
    suspend fun clearLegacyGlobalDuplicates(): Int = mutex.withLock {
        val count = dao.clearLegacyGlobalDuplicates()
        _revision.value++
        count
    }
}
