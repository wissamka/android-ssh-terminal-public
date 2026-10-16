package dev.wissam.sshterm.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.core.keys.GeneratedKeyAlgorithm
import dev.wissam.sshterm.core.keys.KeyManager
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.data.db.ProfileEntity
import dev.wissam.sshterm.data.repository.KeyRepository
import dev.wissam.sshterm.data.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepo: ProfileRepository,
    private val keyRepo: KeyRepository,
    private val keyManager: KeyManager,
    private val identityRepo: dev.wissam.sshterm.data.repository.IdentityRepository,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> = profileRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keys: StateFlow<List<KeyEntity>> = keyRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val identities: StateFlow<List<dev.wissam.sshterm.data.db.IdentityEntity>> =
        identityRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()
    fun clearSaveError() { _saveError.value = null }

    fun save(profile: ProfileEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                profileRepo.save(profile)
                _saveError.value = null
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveError.value = e.message ?: "Could not save profile."
            }
        }
    }

    fun delete(profile: ProfileEntity) {
        viewModelScope.launch { profileRepo.delete(profile) }
    }

    suspend fun get(id: Long): ProfileEntity? = profileRepo.get(id)

    private val _keyGenBusy = MutableStateFlow(false)
    val keyGenBusy: StateFlow<Boolean> = _keyGenBusy.asStateFlow()

    private val _keyGenError = MutableStateFlow<String?>(null)
    val keyGenError: StateFlow<String?> = _keyGenError.asStateFlow()

    fun clearKeyGenError() { _keyGenError.value = null }

    /**
     * Inline key generation from the profile editor; [onCreated] gets the new key's id and its
     * public key line (for the paste-into-authorized_keys dialog).
     */
    fun generateKey(
        name: String,
        algorithm: GeneratedKeyAlgorithm,
        comment: String = name,
        onCreated: (Long, String) -> Unit,
    ) {
        viewModelScope.launch {
            _keyGenBusy.value = true
            try {
                val saved = keyRepo.createAndRegister { keyManager.generate(name, algorithm, comment) }
                onCreated(saved.id, saved.publicKeyOpenSsh)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _keyGenError.value = "Key generation failed: ${e.message}"
            } finally {
                _keyGenBusy.value = false
            }
        }
    }

    /**
     * Inline import of an existing private key from the profile editor — so a key created earlier
     * (and saved off-device) can be re-added after a reinstall without leaving this screen.
     * [onCreated] gets the new key's id so the editor can select it immediately.
     */
    fun importKey(
        name: String,
        privateKeyText: String,
        passphrase: CharArray?,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            _keyGenBusy.value = true
            try {
                val saved = keyRepo.createAndRegister {
                    keyManager.import(name, privateKeyText, passphrase)
                }
                onCreated(saved.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _keyGenError.value = "Import failed — check the key text and passphrase. (${e.message})"
            } finally {
                passphrase?.fill('\u0000')
                _keyGenBusy.value = false
            }
        }
    }
}
