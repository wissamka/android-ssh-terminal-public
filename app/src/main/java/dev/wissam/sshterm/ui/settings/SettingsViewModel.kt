package dev.wissam.sshterm.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wissam.sshterm.core.CrashLogger
import dev.wissam.sshterm.data.settings.AppSettings
import dev.wissam.sshterm.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val historyRepo: dev.wissam.sshterm.data.repository.HistoryRepository,
    private val repo: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setTheme(id: String) { viewModelScope.launch { repo.setTheme(id) } }
    fun setFontSize(sp: Int) { viewModelScope.launch { repo.setFontSize(sp) } }
    fun setAutoLockSeconds(s: Int) { viewModelScope.launch { repo.setAutoLockSeconds(s) } }

    val historyMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun setRecordHistory(enabled: Boolean) { viewModelScope.launch { repo.setRecordHistory(enabled) } }
    fun setGlobalHistoryFallback(enabled: Boolean) { viewModelScope.launch { repo.setGlobalHistoryFallback(enabled) } }
    fun clearHistory() { viewModelScope.launch {
        historyRepo.clearAll()
        historyMessage.value = "All command history cleared."
    } }
    fun clearGlobalHistory() { viewModelScope.launch {
        historyRepo.clearGlobal()
        historyMessage.value = "Global command history cleared."
    } }
    fun cleanLegacyHistory() { viewModelScope.launch {
        val count = historyRepo.clearLegacyGlobalDuplicates()
        historyMessage.value = "Removed $count matching global entries. Profile history preserved."
    } }

    fun lastCrash(): String? = CrashLogger.lastCrash(context)
    fun clearCrash() { CrashLogger.clear(context) }
}
