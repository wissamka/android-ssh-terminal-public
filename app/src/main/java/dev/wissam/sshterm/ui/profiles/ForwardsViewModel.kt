package dev.wissam.sshterm.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wissam.sshterm.data.db.ForwardKind
import dev.wissam.sshterm.data.db.ForwardingRuleEntity
import dev.wissam.sshterm.data.repository.ForwardingRepository
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ForwardsViewModel @Inject constructor(
    private val forwardingRepo: ForwardingRepository,
) : ViewModel() {

    fun rulesFor(profileId: Long) = forwardingRepo.observeForProfile(profileId)

    fun save(rule: ForwardingRuleEntity) {
        viewModelScope.launch { forwardingRepo.save(rule) }
    }

    fun setEnabled(rule: ForwardingRuleEntity, enabled: Boolean) {
        viewModelScope.launch { forwardingRepo.save(rule.copy(enabled = enabled)) }
    }

    fun delete(rule: ForwardingRuleEntity) {
        viewModelScope.launch { forwardingRepo.delete(rule) }
    }

    /** Validates raw form input, returning an error string or null on success (then persists). */
    fun add(
        profileId: Long,
        kind: ForwardKind,
        bindHost: String,
        bindPort: String,
        targetHost: String,
        targetPort: String,
    ): String? {
        val bp = bindPort.toIntOrNull()
        val tp = targetPort.toIntOrNull()
        if (bp == null || bp !in 1..65535) return "Bind port must be 1–65535."
        if (tp == null || tp !in 1..65535) return "Target port must be 1–65535."
        if (targetHost.isBlank()) return "Target host is required."
        // A local forward that binds beyond loopback exposes the tunnel to the LAN. Requiring an
        // explicit non-loopback host is fine, but default-blank must never widen exposure.
        val resolvedBindHost = bindHost.ifBlank { "127.0.0.1" }
        if (kind == ForwardKind.LOCAL && bp < 1024) {
            return "Local bind ports below 1024 can't be opened on Android; use 1024+."
        }
        save(
            ForwardingRuleEntity(
                profileId = profileId,
                kind = kind,
                bindHost = resolvedBindHost,
                bindPort = bp,
                targetHost = targetHost.trim(),
                targetPort = tp,
            )
        )
        return null
    }
}
