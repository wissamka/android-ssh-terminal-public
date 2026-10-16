package dev.wissam.sshterm.ui.identities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.data.db.IdentityEntity
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.data.repository.IdentityRepository
import dev.wissam.sshterm.data.repository.KeyRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class IdentitiesViewModel @Inject constructor(
    private val repo: IdentityRepository,
    keyRepo: KeyRepository,
) : ViewModel() {

    val identities: StateFlow<List<IdentityEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keys: StateFlow<List<KeyEntity>> = keyRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(identity: IdentityEntity) { viewModelScope.launch { repo.save(identity) } }
    fun delete(identity: IdentityEntity) { viewModelScope.launch { repo.delete(identity) } }
}
