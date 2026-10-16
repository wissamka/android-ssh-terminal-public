package dev.wissam.sshterm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.data.db.*
import dev.wissam.sshterm.data.repository.HistoryRepository
import dev.wissam.sshterm.data.settings.SettingsRepository
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Uses the production Room DAO and repository, not a fake SQL/policy implementation. */
@RunWith(AndroidJUnit4::class)
class CommandHistoryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CommandHistoryDao
    private lateinit var settings: SettingsRepository
    private lateinit var repo: HistoryRepository

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.commandHistoryDao()
        settings = SettingsRepository(context)
        settings.setRecordHistory(true)
        settings.setGlobalHistoryFallback(false)
        repo = HistoryRepository(dao, settings)
    }

    @After fun tearDown() = runBlocking {
        settings.setRecordHistory(true)
        settings.setGlobalHistoryFallback(false)
        db.close()
    }

    @Test fun candidatesAreScopedByDefaultIncludingNullProfile() = runBlocking {
        dao.upsert(CommandHistoryEntity(command = "private-a", profileId = 1, lastUsedAt = 1))
        dao.upsert(CommandHistoryEntity(command = "private-b", profileId = 2, lastUsedAt = 2))
        dao.upsert(CommandHistoryEntity(command = "global", lastUsedAt = 3))
        assertEquals(listOf("private-a"), dao.candidatesFor(1).map { it.command })
        assertEquals(listOf("private-b"), dao.candidatesFor(2).map { it.command })
        assertTrue(dao.candidatesFor(null).isEmpty())
        assertEquals(listOf("global", "private-a"), dao.candidatesFor(1, true).map { it.command })
        assertEquals(listOf("global"), dao.candidatesFor(null, true).map { it.command })
    }

    @Test fun repositoryNeverDuplicatesProfilesAndHonorsRecordingControls() = runBlocking {
        repo.record(1, "  private-a  ")
        repo.record(1, "private-a")
        assertEquals(2, dao.find(1, "private-a")!!.useCount)
        assertNull(dao.findGlobal("private-a"))
        assertTrue(repo.candidatesFor(2).isEmpty())
        repo.record(null, "unscoped-disabled")
        assertNull(dao.findGlobal("unscoped-disabled"))

        settings.setGlobalHistoryFallback(true)
        repo.record(1, "still-private")
        repo.record(null, "shared")
        assertNull(dao.findGlobal("still-private"))
        assertEquals(listOf("shared"), repo.candidatesFor(2).map { it.command })

        settings.setRecordHistory(false)
        repo.record(1, "not-saved")
        repo.record(null, "not-global")
        assertNull(dao.find(1, "not-saved"))
        assertNull(dao.findGlobal("not-global"))
        settings.setGlobalHistoryFallback(false)
        assertTrue(repo.candidatesFor(2).isEmpty())
        assertNotNull(dao.findGlobal("shared")) // opt-out hides; explicit clear deletes
    }

    @Test fun concurrentRecordsKeepSingleGlobalRowAndAccurateCount() = runBlocking {
        settings.setGlobalHistoryFallback(true)
        coroutineScope {
            repeat(20) { launch(Dispatchers.IO) { repo.record(null, "shared") } }
        }
        assertEquals(1, dao.candidatesFor(null, true).size)
        assertEquals(20, dao.findGlobal("shared")!!.useCount)
    }

    @Test fun cleanupRemovesOnlyExactGlobalCopiesAndPreservesProfilesAndKeys() = runBlocking {
        val key = db.keyDao().insert(KeyEntity(
            name = "key", algorithm = "ssh-ed25519", protection = KeyProtection.SOFTWARE_ENCRYPTED,
            storageRef = "test-only", publicKeyOpenSsh = "test-only", createdAt = 1,
        ))
        val profile = db.profileDao().insert(ProfileEntity(
            name = "profile", host = "example.test", username = "test",
            authType = AuthType.PUBLIC_KEY, keyId = key,
        ))
        val scoped = CommandHistoryEntity(command = "copy", profileId = profile, useCount = 3, lastUsedAt = 100)
        dao.upsert(scoped)
        dao.upsert(scoped.copy(profileId = null))
        dao.upsert(CommandHistoryEntity(command = "ad-hoc", lastUsedAt = 100))
        dao.upsert(CommandHistoryEntity(command = "copy", profileId = 999, useCount = 1, lastUsedAt = 20))
        dao.upsert(CommandHistoryEntity(command = "aggregate", profileId = profile, useCount = 1, lastUsedAt = 100))
        dao.upsert(CommandHistoryEntity(command = "aggregate", useCount = 4, lastUsedAt = 100))
        dao.upsert(CommandHistoryEntity(command = "different-time", profileId = profile, lastUsedAt = 1))
        dao.upsert(CommandHistoryEntity(command = "different-time", lastUsedAt = 2))

        assertEquals(1, repo.clearLegacyGlobalDuplicates())
        assertEquals(0, repo.clearLegacyGlobalDuplicates()) // safe to repeat
        assertNull(dao.findGlobal("copy"))
        assertEquals(setOf("ad-hoc", "aggregate", "different-time"),
            dao.candidatesFor(null, true).map { it.command }.toSet())
        assertEquals(3, dao.candidatesFor(profile).size)
        assertEquals(1, dao.candidatesFor(999).size)
        assertNotNull(db.keyDao().get(key))
        assertEquals(key, db.profileDao().get(profile)!!.keyId)
        repo.clearAll()
        assertTrue(dao.candidatesFor(profile, true).isEmpty())
        assertNotNull(db.keyDao().get(key))
        assertNotNull(db.profileDao().get(profile))
    }

    @Test fun clearScopesDoNotDeleteOtherHistory() = runBlocking {
        repo.record(1, "one")
        repo.record(2, "two")
        dao.upsert(CommandHistoryEntity(command = "global", lastUsedAt = 3))
        repo.clearForProfile(1)
        assertTrue(dao.candidatesFor(1).isEmpty())
        assertNotNull(dao.find(2, "two"))
        assertNotNull(dao.findGlobal("global"))
        repo.clearGlobal()
        assertNull(dao.findGlobal("global"))
        assertNotNull(dao.find(2, "two"))
        repo.clearAll()
        assertTrue(dao.candidatesFor(2, true).isEmpty())
    }
}
