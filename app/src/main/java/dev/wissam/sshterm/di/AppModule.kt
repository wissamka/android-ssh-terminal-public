package dev.wissam.sshterm.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wissam.sshterm.data.db.AppDatabase
import dev.wissam.sshterm.data.db.CommandHistoryDao
import dev.wissam.sshterm.data.db.ForwardingRuleDao
import dev.wissam.sshterm.data.db.KeyDao
import dev.wissam.sshterm.data.db.ProfileDao
import dev.wissam.sshterm.data.db.SnippetDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.open(context)

    @Provides
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideKeyDao(db: AppDatabase): KeyDao = db.keyDao()

    @Provides
    fun provideCommandHistoryDao(db: AppDatabase): CommandHistoryDao = db.commandHistoryDao()

    @Provides
    fun provideForwardingRuleDao(db: AppDatabase): ForwardingRuleDao = db.forwardingRuleDao()

    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()

    @Provides
    fun provideIdentityDao(db: AppDatabase): dev.wissam.sshterm.data.db.IdentityDao = db.identityDao()
}
