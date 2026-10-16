package dev.wissam.sshterm.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.core.ssh.KnownHostsManager
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class KnownHostsViewModel @Inject constructor(
    private val manager: KnownHostsManager,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<KnownHostsManager.Entry>>(emptyList())
    val entries: StateFlow<List<KnownHostsManager.Entry>> = _entries.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) { manager.list() }
        }
    }

    fun delete(entry: KnownHostsManager.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manager.delete(entry) }
            refresh()
        }
    }
}
