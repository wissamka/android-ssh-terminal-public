package dev.wissam.sshterm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.data.db.AppDatabase
import dev.wissam.sshterm.data.db.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun migrate1To4PreservesData() = verifyUpgrade(1)
    @Test fun migrate2To4PreservesData() = verifyUpgrade(2)
    @Test fun migrate3To4PreservesData() = verifyUpgrade(3)

    private fun verifyUpgrade(version: Int) = withFixture(version) { name ->
        // Production factory: the same complete migration chain and no-destructive-fallback policy.
        AppDatabase.open(context, name).useDatabase { room ->
            val db = room.openHelper.writableDatabase // runs Room's generated schema validation
            assertEquals(4, db.version)
            assertRowsPreserved(db)
            assertCount(db, "identities", 0)
            assertCount(db, "forwarding_rules", if (version >= 2) 1 else 0)
            assertCount(db, "snippets", if (version >= 3) 1 else 0)
            db.query("SELECT identityId FROM profiles").use {
                while (it.moveToNext()) assertTrue(it.isNull(0))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            // Both key storage tiers and jump-host links are readable through real Room DAOs.
            runBlocking {
                assertEquals("encrypted-ref", room.keyDao().get(1)!!.storageRef)
                assertEquals("keystore-alias", room.keyDao().get(2)!!.storageRef)
                assertEquals(1L, room.profileDao().get(2)!!.jumpHostProfileId)
            }
        }
        // A subsequent process/open uses the generated Room identity without re-running ALTER.
        AppDatabase.open(context, name).useDatabase { assertRowsPreserved(it.openHelper.writableDatabase) }
    }

    @Test fun interruptedMigrationRollsBackAndCanBeRetriedWithoutLosingKeys() = withFixture(1) { name ->
        val interrupted = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE keys SET storageRef = 'must-roll-back'")
                Migrations.MIGRATION_1_2.migrate(db)
                throw IllegalStateException("test interruption")
            }
        }
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(interrupted).build().useDatabase { room ->
                assertThrows(IllegalStateException::class.java) { room.openHelper.writableDatabase }
            }
        raw(name) { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT storageRef FROM keys WHERE id = 1", null).use {
                assertTrue(it.moveToFirst()); assertEquals("encrypted-ref", it.getString(0))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'forwarding_rules'", null).use {
                assertFalse("DDL must roll back with the failed migration", it.moveToFirst())
            }
        }
        AppDatabase.open(context, name).useDatabase { assertRowsPreserved(it.openHelper.writableDatabase) }
    }

    @Test fun unsupportedFutureVersionFailsClosedAndLeavesKeysRecoverable() = withFixture(3) { name ->
        // Synthetic unsupported version marker; this is NOT a claimed historical v99 schema.
        raw(name) { it.version = 99 }
        AppDatabase.open(context, name).useDatabase { room ->
            assertThrows(IllegalStateException::class.java) { room.openHelper.writableDatabase }
        }
        raw(name) { db ->
            assertEquals(99, db.version)
            db.rawQuery("SELECT storageRef FROM keys ORDER BY id", null).use {
                assertTrue(it.moveToNext()); assertEquals("encrypted-ref", it.getString(0))
                assertTrue(it.moveToNext()); assertEquals("keystore-alias", it.getString(0))
            }
            db.rawQuery("SELECT keyId FROM profiles ORDER BY id", null).use {
                assertTrue(it.moveToNext()); assertEquals(1L, it.getLong(0))
                assertTrue(it.moveToNext()); assertEquals(2L, it.getLong(0))
            }
        }
    }

    @Test fun schemaValidationFailureDoesNotDeleteDataOrCommitPartialUpgrade() = withFixture(3) { name ->
        raw(name) { it.execSQL("ALTER TABLE keys ADD COLUMN unexpected TEXT") }
        AppDatabase.open(context, name).useDatabase { room ->
            assertThrows(IllegalStateException::class.java) { room.openHelper.writableDatabase }
        }
        raw(name) { db ->
            assertEquals(3, db.version)
            db.rawQuery("SELECT name, storageRef FROM keys WHERE id=1", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("software", it.getString(0))
                assertEquals("encrypted-ref", it.getString(1))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='identities'", null).use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    private fun assertRowsPreserved(db: SupportSQLiteDatabase) {
        db.query("SELECT * FROM keys ORDER BY id").use {
            assertTrue(it.moveToNext())
            assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("id")))
            assertEquals("software", it.getString(it.getColumnIndexOrThrow("name")))
            assertEquals("ssh-ed25519", it.getString(it.getColumnIndexOrThrow("algorithm")))
            assertEquals("SOFTWARE_ENCRYPTED", it.getString(it.getColumnIndexOrThrow("protection")))
            assertEquals("encrypted-ref", it.getString(it.getColumnIndexOrThrow("storageRef")))
            assertEquals("software-public", it.getString(it.getColumnIndexOrThrow("publicKeyOpenSsh")))
            assertEquals(1000L, it.getLong(it.getColumnIndexOrThrow("createdAt")))
            assertTrue(it.moveToNext())
            assertEquals("HARDWARE_KEYSTORE", it.getString(it.getColumnIndexOrThrow("protection")))
            assertEquals("keystore-alias", it.getString(it.getColumnIndexOrThrow("storageRef")))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT id, host, port, username, keyId, jumpHostProfileId, lastConnectedAt FROM profiles ORDER BY id").use {
            assertTrue(it.moveToNext())
            assertEquals(1L, it.getLong(0)); assertEquals("jump.example", it.getString(1))
            assertEquals(2222, it.getInt(2)); assertEquals("alice", it.getString(3))
            assertEquals(1L, it.getLong(4)); assertTrue(it.isNull(5)); assertEquals(1234L, it.getLong(6))
            assertTrue(it.moveToNext())
            assertEquals(2L, it.getLong(0)); assertEquals("target.example", it.getString(1))
            assertEquals(22, it.getInt(2)); assertEquals("bob", it.getString(3))
            assertEquals(2L, it.getLong(4)); assertEquals(1L, it.getLong(5)); assertEquals(5678L, it.getLong(6))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT command, profileId, useCount, lastUsedAt FROM command_history").use {
            assertTrue(it.moveToFirst())
            assertEquals("private-command", it.getString(0)); assertEquals(2L, it.getLong(1))
            assertEquals(3, it.getInt(2)); assertEquals(9876L, it.getLong(3))
            assertFalse(it.moveToNext())
        }
    }

    private fun assertCount(db: SupportSQLiteDatabase, table: String, count: Int) {
        db.query("SELECT count(*) FROM $table").use {
            assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0))
        }
    }

    private fun raw(name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use(block)
    }

    private fun withFixture(version: Int, block: (String) -> Unit) {
        val name = "migration-test-${UUID.randomUUID()}.db"
        try {
            HistoricalDatabaseFixture.create(context, name, version)
            block(name)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
