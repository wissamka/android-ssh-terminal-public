package dev.wissam.sshterm.ui.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.data.db.SnippetEntity
import dev.wissam.sshterm.data.repository.SnippetRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SnippetsViewModel @Inject constructor(
    private val repo: SnippetRepository,
) : ViewModel() {

    val snippets: StateFlow<List<SnippetEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(name: String, body: String, id: Long = 0) {
        if (name.isBlank() || body.isBlank()) return
        viewModelScope.launch { repo.save(name, body, id) }
    }

    fun delete(snippet: SnippetEntity) {
        viewModelScope.launch { repo.delete(snippet) }
    }
}
