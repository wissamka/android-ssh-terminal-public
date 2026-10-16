package dev.wissam.sshterm

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Independent SQL fixtures transcribed from the actual historical Room entities:
 * v1: 20b5550; v2: 3fc6b03; v3: e417700.
 * Kotlin constructor defaults were not SQL DEFAULT clauses. No Room identity hash is invented.
 * Do not create these fixtures with the migrations under test.
 */
internal object HistoricalDatabaseFixture {
    fun create(context: Context, name: String, version: Int) {
        require(version in 1..3)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `keys` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, `algorithm` TEXT NOT NULL, `protection` TEXT NOT NULL, `storageRef` TEXT NOT NULL, `publicKeyOpenSsh` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX `index_keys_name` ON `keys` (`name`)")
                        db.execSQL("CREATE TABLE `profiles` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `authType` TEXT NOT NULL, `keyId` INTEGER, `jumpHostProfileId` INTEGER, `termType` TEXT NOT NULL, `colorScheme` TEXT NOT NULL, `lastConnectedAt` INTEGER, FOREIGN KEY(`keyId`) REFERENCES `keys`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`jumpHostProfileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                        db.execSQL("CREATE INDEX `index_profiles_keyId` ON `profiles` (`keyId`)")
                        db.execSQL("CREATE INDEX `index_profiles_jumpHostProfileId` ON `profiles` (`jumpHostProfileId`)")
                        db.execSQL("CREATE TABLE `command_history` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `command` TEXT NOT NULL, `profileId` INTEGER, `useCount` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX `index_command_history_profileId_command` ON `command_history` (`profileId`, `command`)")
                        if (version >= 2) db.execSQL("CREATE TABLE `forwarding_rules` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `profileId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `bindHost` TEXT NOT NULL, `bindPort` INTEGER NOT NULL, `targetHost` TEXT NOT NULL, `targetPort` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        if (version >= 2) db.execSQL("CREATE INDEX `index_forwarding_rules_profileId` ON `forwarding_rules` (`profileId`)")
                        if (version >= 3) db.execSQL("CREATE TABLE `snippets` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO keys VALUES (1,'software','ssh-ed25519','SOFTWARE_ENCRYPTED','encrypted-ref','software-public',1000)")
                        db.execSQL("INSERT INTO keys VALUES (2,'hardware','ecdsa-sha2-nistp256','HARDWARE_KEYSTORE','keystore-alias','hardware-public',2000)")
                        db.execSQL("INSERT INTO profiles VALUES (1,'jump','jump.example',2222,'alice','PUBLIC_KEY',1,NULL,'xterm','default',1234)")
                        db.execSQL("INSERT INTO profiles VALUES (2,'target','target.example',22,'bob','PUBLIC_KEY',2,1,'xterm-256color','default',5678)")
                        db.execSQL("INSERT INTO command_history VALUES (1,'private-command',2,3,9876)")
                        if (version >= 2) db.execSQL("INSERT INTO forwarding_rules VALUES (1,2,'LOCAL','127.0.0.1',8080,'localhost',80,1)")
                        if (version >= 3) db.execSQL("INSERT INTO snippets VALUES (1,'saved','printf test',1000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Fixtures must be created fresh")
                    }
                }).build()
        )
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }
    }
}

/** RoomDatabase has close(), but Room 2.8.4 does not implement Closeable/AutoCloseable. */
internal inline fun <T> dev.wissam.sshterm.data.db.AppDatabase.useDatabase(
    block: (dev.wissam.sshterm.data.db.AppDatabase) -> T,
): T = try { block(this) } finally { close() }
