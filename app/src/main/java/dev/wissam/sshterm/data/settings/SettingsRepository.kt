package dev.wissam.sshterm.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeId: String = "default",
    val fontSizeSp: Int = 13,
    val autoLockSeconds: Int = 60,
    val recordHistory: Boolean = true,
    val globalHistoryFallback: Boolean = false,
    val onboardingSeen: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val RECORD_HISTORY = androidx.datastore.preferences.core.booleanPreferencesKey("record_history")
        val GLOBAL_HISTORY = androidx.datastore.preferences.core.booleanPreferencesKey("global_history_fallback")
        val THEME = stringPreferencesKey("theme_id")
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        val ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_seen")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            recordHistory = p[Keys.RECORD_HISTORY] ?: true,
            globalHistoryFallback = p[Keys.GLOBAL_HISTORY] ?: false,
            themeId = p[Keys.THEME] ?: "default",
            fontSizeSp = p[Keys.FONT_SIZE] ?: 13,
            autoLockSeconds = p[Keys.AUTO_LOCK] ?: 60,
            onboardingSeen = p[Keys.ONBOARDING] ?: false,
        )
    }

    suspend fun setRecordHistory(enabled: Boolean) = context.dataStore.edit { it[Keys.RECORD_HISTORY] = enabled }
    suspend fun setGlobalHistoryFallback(enabled: Boolean) = context.dataStore.edit { it[Keys.GLOBAL_HISTORY] = enabled }

    suspend fun setTheme(id: String) = context.dataStore.edit { it[Keys.THEME] = id }
    suspend fun setFontSize(sp: Int) =
        context.dataStore.edit { it[Keys.FONT_SIZE] = sp.coerceIn(8, 28) }
    suspend fun setAutoLockSeconds(seconds: Int) =
        context.dataStore.edit { it[Keys.AUTO_LOCK] = seconds.coerceIn(0, 3600) }
    suspend fun setOnboardingSeen() = context.dataStore.edit { it[Keys.ONBOARDING] = true }
}
