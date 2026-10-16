package dev.wissam.sshterm.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** All Room migrations, kept in one place so both the DI module and migration tests use them. */
object Migrations {

    /**
     * v1 (20b5550) had keys, profiles and history only. v2 (3fc6b03) added forwarding;
     * the existing key/profile columns and their foreign keys were unchanged.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `forwarding_rules` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `profileId` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `bindHost` TEXT NOT NULL, `bindPort` INTEGER NOT NULL, " +
                    "`targetHost` TEXT NOT NULL, `targetPort` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_forwarding_rules_profileId` ON `forwarding_rules` (`profileId`)")
        }
    }


    /** v2 → v3: add the snippets table without dropping existing profiles/keys/history. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `snippets` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "`name` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
        }
    }

    /** v3 → v4: add identities + a nullable profiles.identityId, preserving existing data. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `identities` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, " +
                    "`username` TEXT NOT NULL, `authType` TEXT NOT NULL, `keyId` INTEGER, " +
                    "FOREIGN KEY(`keyId`) REFERENCES `keys`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_identities_name` ON `identities` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_identities_keyId` ON `identities` (`keyId`)")
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `identityId` INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_identityId` ON `profiles` (`identityId`)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
