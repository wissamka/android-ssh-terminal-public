package dev.wissam.sshterm.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        KeyEntity::class,
        CommandHistoryEntity::class,
        ForwardingRuleEntity::class,
        SnippetEntity::class,
        IdentityEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        /** Unknown or invalid schemas must fail closed; never discard saved key references. */
        fun open(context: Context, name: String = "sshterm.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*Migrations.ALL)
                .build()
    }

    abstract fun profileDao(): ProfileDao
    abstract fun keyDao(): KeyDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun forwardingRuleDao(): ForwardingRuleDao
    abstract fun snippetDao(): SnippetDao
    abstract fun identityDao(): IdentityDao
}
