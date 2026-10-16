package dev.wissam.sshterm.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.core.keys.GeneratedKeyAlgorithm
import dev.wissam.sshterm.core.keys.KeyProtectionMetadata
import dev.wissam.sshterm.core.keys.KeyManager
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.data.repository.KeyRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SoftwareProtection(val label: String, val detail: String) {
    LEGACY("Legacy encryption · upgrade pending", "Private key stored in software. Upgrade to require a strong biometric for decryption."),
    PENDING("Upgrade pending · recovery retained", "Biometric wrapper saved; a legacy recovery copy remains. Finalize to remove that copy safely."),
    BOUND("Biometric-bound encryption", "A fresh strong biometric unlocks each decryption. The SSH private key remains a software key."),
    UNAVAILABLE("Protection unavailable", "Key storage could not be inspected. Existing material has been preserved."),
}
data class KeyWithProtection(val key: KeyEntity, val protection: KeyProtectionMetadata, val software: SoftwareProtection?)

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val keyRepo: KeyRepository,
    private val keyManager: KeyManager,
) : ViewModel() {

    private val refresh = MutableStateFlow(0)
    private val prompts = dev.wissam.sshterm.ui.terminal.PromptQueue<dev.wissam.sshterm.ui.terminal.UiPrompt>()
    val prompt = prompts.current
    private var operation: kotlinx.coroutines.Job? = null
    private val _upgradeOffer = MutableStateFlow<KeyEntity?>(null)
    val upgradeOffer = _upgradeOffer.asStateFlow()
    fun dismissUpgradeOffer() { _upgradeOffer.value = null }
    fun cancelOperations() { operation?.cancel(); _upgradeOffer.value = null }

    private fun softwareState(key: KeyEntity): SoftwareProtection? {
        if (key.protection != dev.wissam.sshterm.data.db.KeyProtection.SOFTWARE_ENCRYPTED) return null
        return try {
            if (!keyManager.hasAuthBoundEnvelope(key)) SoftwareProtection.LEGACY
            else {
                keyManager.validateSoftwareWrapperPolicy(key)
                if (keyManager.hasLegacyRecovery(key)) SoftwareProtection.PENDING else SoftwareProtection.BOUND
            }
        } catch (e: Exception) { SoftwareProtection.UNAVAILABLE }
    }
    val keys: StateFlow<List<KeyWithProtection>> = kotlinx.coroutines.flow.combine(keyRepo.observeAll(), refresh) { keys, _ ->
        keys.map { KeyWithProtection(it, keyManager.protectionMetadata(it), softwareState(it)) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun authorizer(key: KeyEntity) = dev.wissam.sshterm.core.keys.KeyFileAuthorizer { request ->
        prompts.await<Boolean> { done -> dev.wissam.sshterm.ui.terminal.UiPrompt.KeyUnlock(
            "Protect “${key.name}”",
            when (request.purpose) {
                dev.wissam.sshterm.core.keys.KeyFileAuthorizationRequest.Purpose.ENCRYPT_MIGRATION -> "Step 1: encrypt with biometric protection"
                dev.wissam.sshterm.core.keys.KeyFileAuthorizationRequest.Purpose.VERIFY_MIGRATION -> "Verify the protected copy before completing the upgrade"
                else -> "Authorize decrypting this software key"
            },
            done, cryptoCipher = request.cipher,
        ) }
    }

    fun upgrade(key: KeyEntity) {
        if (_busy.value) return
        _upgradeOffer.value = null
        operation = viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    if (!keyManager.hasAuthBoundEnvelope(key)) keyManager.migrateSoftwareKeyAuthorized(key, authorizer(key))
                    keyManager.retireSoftwareKeyRecoveryAuthorized(key, authorizer(key))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _error.value = "Upgrade not completed. Your saved key is preserved. Check its protection status and try Upgrade or Finalize again. (${e.message})"
            } finally { refresh.value++; _busy.value = false }
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun generate(name: String, algorithm: GeneratedKeyAlgorithm, comment: String = name) {
        if (_busy.value) return
        operation = viewModelScope.launch {
            _busy.value = true
            try {
                val saved = keyRepo.createAndRegister { keyManager.generate(name, algorithm, comment) }
                if (saved.protection == dev.wissam.sshterm.data.db.KeyProtection.SOFTWARE_ENCRYPTED) _upgradeOffer.value = saved
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _error.value = "Key generation failed: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun import(name: String, privateKeyText: String, passphrase: CharArray?) {
        if (_busy.value) { passphrase?.fill('\u0000'); return }
        operation = viewModelScope.launch {
            _busy.value = true
            try {
                val saved = keyRepo.createAndRegister {
                    keyManager.import(name, privateKeyText, passphrase)
                }
                if (saved.protection == dev.wissam.sshterm.data.db.KeyProtection.SOFTWARE_ENCRYPTED) _upgradeOffer.value = saved
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _error.value = "Import failed — check the key text and passphrase. (${e.message})"
            } finally {
                passphrase?.fill('\u0000')
                _busy.value = false
            }
        }
    }

    fun delete(key: KeyEntity) {
        if (_busy.value) return
        operation = viewModelScope.launch {
            _busy.value = true
            try { withContext(Dispatchers.IO) { keyRepo.delete(key) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _error.value = "Could not delete key: ${e.message}" }
            finally { _busy.value = false }
        }
    }

    /** Result of an export attempt, delivered to [onDone] on the main thread. */
    fun export(key: KeyEntity, exportPassphrase: CharArray, onDone: (String?) -> Unit) {
        if (_busy.value) { exportPassphrase.fill('\u0000'); onDone(null); return }
        operation = viewModelScope.launch {
            _busy.value = true
            var stored: CharArray? = null
            try {
                stored = prompts.await<CharArray?> { done -> dev.wissam.sshterm.ui.terminal.UiPrompt.Secret(
                    "Stored key passphrase", "Leave empty if “${key.name}” has no existing passphrase.", done,
                ) } ?: run { onDone(null); return@launch }
                val text = withContext(Dispatchers.IO) {
                    if (keyManager.hasAuthBoundEnvelope(key)) {
                        keyManager.exportKeyAuthorized(key, exportPassphrase, authorizer(key), stored)
                    } else {
                        val allowed = prompts.await<Boolean> { done -> dev.wissam.sshterm.ui.terminal.UiPrompt.KeyUnlock(
                            "Export “${key.name}”", "Authorize exporting this software private key", done,
                        ) }
                        if (!allowed) throw dev.wissam.sshterm.core.keys.KeyFileAuthorizationDeniedException()
                        keyManager.exportKey(key, exportPassphrase, stored)
                    }
                }
                onDone(text)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _error.value = "Export failed: ${e.message}"; onDone(null) }
            finally { stored?.fill('\u0000'); exportPassphrase.fill('\u0000'); _busy.value = false }
        }
    }

    override fun onCleared() { prompts.close(); super.onCleared() }

    fun canExport(key: KeyEntity): Boolean = keyManager.canExport(key)
}
