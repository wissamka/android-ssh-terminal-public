package dev.wissam.sshterm.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wissam.sshterm.core.ssh.ForwardDirection
import dev.wissam.sshterm.core.ssh.ForwardSpec
import dev.wissam.sshterm.core.ssh.HostKeyInfo
import dev.wissam.sshterm.core.ssh.HostKeyPrompter
import dev.wissam.sshterm.core.ssh.ResolvedConnection
import dev.wissam.sshterm.core.ssh.SshAuthMethod
import dev.wissam.sshterm.core.ssh.SshSessionManager
import dev.wissam.sshterm.core.terminal.BaseTerminalSessionClient
import dev.wissam.sshterm.core.keys.KeyManager
import dev.wissam.sshterm.data.db.AuthType
import dev.wissam.sshterm.data.db.ForwardKind
import dev.wissam.sshterm.data.db.KeyProtection
import dev.wissam.sshterm.data.db.ProfileEntity
import dev.wissam.sshterm.data.repository.ForwardingRepository
import dev.wissam.sshterm.data.repository.HistoryRepository
import dev.wissam.sshterm.data.repository.KeyRepository
import dev.wissam.sshterm.data.repository.ProfileRepository
import dev.wissam.sshterm.ui.autocomplete.FrecencyRanker
import java.io.BufferedReader
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A prompt the terminal screen must render; resolved back through the callback. */
sealed interface UiPrompt {
    class Secret(
        val title: String,
        val hint: String,
        val onResult: (CharArray?) -> Unit,
        val echo: Boolean = false,
    ) : UiPrompt

    /** Gate before any private-key use; screen shows the system auth sheet. */
    class KeyUnlock(
        val title: String,
        val subtitle: String,
        val onResult: (Boolean) -> Unit,
        val cryptoSignature: java.security.Signature? = null,
        val cryptoCipher: javax.crypto.Cipher? = null,
    ) : UiPrompt

    class HostKey(
        val info: HostKeyInfo,
        /** True when a previously-trusted key CHANGED (possible MITM) — render loudly. */
        val changed: Boolean,
        val onResult: (Boolean) -> Unit,
    ) : UiPrompt

    class BatteryOptimization(val onResult: (Boolean) -> Unit) : UiPrompt

    class Error(val message: String, val onDismiss: () -> Unit) : UiPrompt
}

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SshSessionManager,
    private val profileRepo: ProfileRepository,
    private val keyRepo: KeyRepository,
    private val keyManager: KeyManager,
    private val historyRepo: HistoryRepository,
    private val forwardingRepo: ForwardingRepository,
    private val snippetRepo: dev.wissam.sshterm.data.repository.SnippetRepository,
    private val settingsRepo: dev.wissam.sshterm.data.settings.SettingsRepository,
    private val identityRepo: dev.wissam.sshterm.data.repository.IdentityRepository,
) : ViewModel() {

    private var snippets: List<dev.wissam.sshterm.data.db.SnippetEntity> = emptyList()

    /** Terminal font size (sp), persisted; the AndroidView host reads and re-applies it. */
    val fontSizeSp: StateFlow<Int> = settingsRepo.settings
        .map { it.fontSizeSp }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 13)

    /** Active theme id; drives the terminal View background re-apply on change. */
    val themeId: StateFlow<String> = settingsRepo.settings
        .map { it.themeId }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "default")

    /** Resolved independently for each tab, including sessions whose emulator is still lazy. */
    val sessionThemes: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.combine(
        settingsRepo.settings.map { it.themeId }.distinctUntilChanged(),
        profileRepo.observeAll(),
        sessionManager.sessions,
    ) { appTheme, profiles, sessions ->
        val choices = profiles.associate { it.id to it.colorScheme }
        sessions.associate { managed ->
            managed.session.mHandle to dev.wissam.sshterm.core.terminal.TerminalThemes.resolve(
                choices[managed.profileId], appTheme,
            ).id
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch { snippetRepo.observeAll().collect { snippets = it } }
        viewModelScope.launch {
            sessionThemes.collect {
                sessionManager.sessions.value.forEach { applySessionTheme(it.session) }
                boundView?.let { view ->
                    view.currentSession?.let { view.setBackgroundColor(sessionBackground(it)) }
                    // Theme changes redraw only: retain scroll position, selection and search highlights.
                    view.invalidate()
                }
            }
        }
    }

    fun sessionThemeId(session: TerminalSession): String = sessionThemes.value[session.mHandle] ?: themeId.value

    fun applySessionTheme(session: TerminalSession) {
        session.emulator?.mColors?.let {
            dev.wissam.sshterm.core.terminal.TerminalThemes.apply(it, sessionThemeId(session))
        }
    }

    fun sessionBackground(session: TerminalSession): Int = session.emulator?.mColors?.let {
        dev.wissam.sshterm.core.terminal.TerminalThemes.backgroundColor(it)
    } ?: dev.wissam.sshterm.core.terminal.TerminalThemes.backgroundColor(sessionThemeId(session))

    /** Persists a new terminal font size (from pinch-zoom or Settings). */
    fun onFontSizeChanged(sp: Int) {
        viewModelScope.launch { settingsRepo.setFontSize(sp) }
    }

    val recoveryCards = sessionManager.endedSessions
    fun dismissRecovery(token: String) = sessionManager.dismissRecovery(token)

    val sessions = sessionManager.sessions
    val activeSessionId = sessionManager.activeSessionId

    private val promptQueue = PromptQueue<UiPrompt>()
    val prompt: StateFlow<UiPrompt?> = promptQueue.current

    /**
     * True while a connect is resolving (credentials/host-key prompts) but no session tab exists
     * yet. Keeps the terminal screen from bouncing back to the profile list — and losing the
     * pending host-key/credential dialog — before the session registers.
     */
    private var pendingConnections = 0
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /** Sessions whose transport finished (tab shows "ended" state). */
    private val _endedSessions = MutableStateFlow<Set<String>>(emptySet())
    val endedSessions: StateFlow<Set<String>> = _endedSessions.asStateFlow()

    // ---- Remote clipboard writes (OSC 52) -----------------------------------------------------
    // Only metadata is exposed to the UI; the bounded payload stays in the consent policy.
    private val remoteClipboardPolicy = RemoteClipboardPolicy()
    private val _clipboardRequest = MutableStateFlow<RemoteClipboardPolicy.Request?>(null)
    val clipboardRequest: StateFlow<RemoteClipboardPolicy.Request?> = _clipboardRequest.asStateFlow()
    private var clipboardExpiry: kotlinx.coroutines.Job? = null

    private fun activeClipboardSessionId(): String? = sessionManager.activeSession()?.session
        ?.takeIf { it.isRunning && it.mHandle !in _endedSessions.value }
        ?.mHandle

    internal fun handleServerClipboardWrite(session: TerminalSession, text: String) {
        val request = remoteClipboardPolicy.offer(
            session.mHandle, activeClipboardSessionId(), text, android.os.SystemClock.elapsedRealtime(),
        ) ?: return
        _clipboardRequest.value = request
        clipboardExpiry?.cancel()
        clipboardExpiry = viewModelScope.launch {
            kotlinx.coroutines.delay(RemoteClipboardPolicy.REQUEST_TTL_MS)
            dismissRemoteClipboardRequest(request.id)
        }
    }

    /** Called only by the snackbar's explicit Copy action, on the main thread. */
    fun acceptRemoteClipboardRequest(requestId: Long) {
        val text = remoteClipboardPolicy.accept(
            requestId, activeClipboardSessionId(), android.os.SystemClock.elapsedRealtime(),
        )
        dismissRemoteClipboardRequest(requestId)
        if (text == null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun dismissRemoteClipboardRequest(requestId: Long) {
        remoteClipboardPolicy.dismiss(requestId)
        if (_clipboardRequest.value?.id == requestId) {
            _clipboardRequest.value = null
            clipboardExpiry?.cancel()
            clipboardExpiry = null
        }
    }

    // ---- Sticky modifiers (consumed by TerminalViewClient.readControlKey/readAltKey) ---------
    val stickyCtrl = MutableStateFlow(StickyState.OFF)
    val stickyAlt = MutableStateFlow(StickyState.OFF)

    fun toggleSticky(state: MutableStateFlow<StickyState>) {
        state.value = when (state.value) {
            StickyState.OFF -> StickyState.ARMED
            StickyState.ARMED -> StickyState.LOCKED
            StickyState.LOCKED -> StickyState.OFF
        }
    }

    /** Called after a keypress has consumed the modifiers; ARMED is one-shot, LOCKED persists. */
    fun consumeStickies() {
        if (stickyCtrl.value == StickyState.ARMED) stickyCtrl.value = StickyState.OFF
        if (stickyAlt.value == StickyState.ARMED) stickyAlt.value = StickyState.OFF
    }

    // ---- Autocomplete -------------------------------------------------------------------------
    val inputText = MutableStateFlow("")
    private val _suggestions = MutableStateFlow<List<FrecencyRanker.Suggestion>>(emptyList())
    val suggestions: StateFlow<List<FrecencyRanker.Suggestion>> = _suggestions.asStateFlow()
    val selectedSuggestion = MutableStateFlow(-1)
    val suggestionsVisible = MutableStateFlow(false)

    private val seedCommands: List<String> by lazy {
        runCatching {
            context.assets.open("seed_commands.txt").bufferedReader().use(BufferedReader::readLines)
                .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        }.getOrDefault(emptyList())
    }

    private var suggestionRevision = 0
    private var inputFocused = false

    fun onInputFocusChanged(focused: Boolean) {
        inputFocused = focused
        if (!focused) dismissSuggestions()
    }

    fun handleCommandKey(key: String): Boolean {
        val nativeCode = when (key) {
            "Up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "Down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "Enter" -> android.view.KeyEvent.KEYCODE_ENTER
            else -> null
        }
        if (nativeCode != null && handleModifiedCommandKey(nativeCode, 0, false, false)) return true
        when (commandInputAction(key, inputFocused, suggestionsVisible.value,
            selectedSuggestion.value, _suggestions.value.size)) {
            CommandInputAction.PASS -> return false
            CommandInputAction.PREVIOUS -> moveSuggestion(-1)
            CommandInputAction.NEXT -> moveSuggestion(1)
            CommandInputAction.COMMIT -> commitSelectedSuggestion()
            CommandInputAction.SEND -> sendInputLine()
        }
        return true
    }

    fun onInputChanged(text: String, composing: Boolean = false): Boolean {
        val immediate = stickyCommandInsertion(inputText.value, text, stickyCtrl.value, stickyAlt.value, composing)
        val session = sessionManager.activeSession()?.session
        if (immediate != null && session != null) {
            session.write(immediate)
            consumeStickies()
            return true // Preserve the complete buffered command and its suggestion selection.
        }
        if (composing && (stickyCtrl.value != StickyState.OFF || stickyAlt.value != StickyState.OFF)) return false
        inputText.value = text
        selectedSuggestion.value = -1
        _suggestions.value = emptyList()
        refreshSuggestions()
        return false
    }

    /** Called by command-field preview before Compose edits; handled events never reach onValueChange. */
    fun handleModifiedCommandKey(keyCode: Int, codePoint: Int, physicalCtrl: Boolean, physicalAlt: Boolean): Boolean {
        if (!inputFocused) return false
        val ctrl = if (physicalCtrl) StickyState.LOCKED else stickyCtrl.value
        val alt = if (physicalAlt) StickyState.LOCKED else stickyAlt.value
        if (ctrl == StickyState.OFF && alt == StickyState.OFF) return false
        val session = sessionManager.activeSession()?.session ?: return false
        var modifiers = 0
        if (ctrl != StickyState.OFF) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (alt != StickyState.OFF) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        val emulator = session.emulator
        val text = KeyHandler.getCode(keyCode, modifiers,
            emulator?.isCursorKeysApplicationMode ?: false,
            emulator?.isKeypadApplicationMode ?: false,
        ) ?: modifiedCommandCodePoint(codePoint, ctrl, alt) ?: return false
        session.write(text)
        consumeStickies()
        return true
    }

    /** Up/down from the command field or its visible navigation buttons. */
    fun moveSuggestion(delta: Int) {
        suggestionsVisible.value = true
        selectedSuggestion.value = nextSuggestionIndex(selectedSuggestion.value, delta, _suggestions.value.size)
        if (_suggestions.value.isEmpty()) refreshSuggestions(delta)
    }

    /** Commits the highlighted suggestion into the input line for editing (does NOT send). */
    fun commitSelectedSuggestion() {
        val index = selectedSuggestion.value
        val suggestion = _suggestions.value.getOrNull(index) ?: return
        invalidateHistorySuggestions()
        inputText.value = suggestion.command
        _suggestions.value = emptyList()
        suggestionsVisible.value = false
        selectedSuggestion.value = -1
    }

    fun dismissSuggestions() {
        suggestionRevision++
        suggestionsVisible.value = false
        selectedSuggestion.value = -1
    }

    private val historySuggestionPolicy = dev.wissam.sshterm.data.repository.HistorySuggestionPolicy()
    private var suggestionJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            sessionManager.activeSessionId.collect {
                invalidateHistorySuggestions()
                inputText.value = ""
                refreshSuggestions()
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepo.settings.map { it.recordHistory to it.globalHistoryFallback }.distinctUntilChanged(),
                historyRepo.revision,
            ) { _, _ -> Unit }.collect {
                invalidateHistorySuggestions()
                refreshSuggestions()
            }
        }
    }

    private fun invalidateHistorySuggestions() {
        historySuggestionPolicy.invalidate()
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
        dismissSuggestions()
    }

    private fun refreshSuggestions(initialDelta: Int? = null) {
        val revision = ++suggestionRevision
        val query = inputText.value
        val sessionId = sessionManager.activeSessionId.value
        val profileId = sessionManager.activeSession()?.profileId
        val request = historySuggestionPolicy.invalidate()
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
        selectedSuggestion.value = -1
        suggestionJob = viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) { historyRepo.candidatesFor(profileId) }
            val ranked = FrecencyRanker.rank(query, candidates, seedCommands, profileId)
            // Saved snippets lead within each match tier; command prefixes still come first.
            val q = query.trim()
            val snippetMatches = snippets
                .filter { q.isEmpty() || it.name.contains(q, true) || it.body.contains(q, true) }
                .map {
                    FrecencyRanker.Suggestion(
                        command = it.body,
                        score = Double.MAX_VALUE,
                        snippetName = it.name,
                    )
                }
            if (!historySuggestionPolicy.isCurrent(request) ||
                sessionManager.activeSessionId.value != sessionId || inputText.value != query) return@launch
            if (revision != suggestionRevision) return@launch
            _suggestions.value = (snippetMatches + ranked)
                .sortedByDescending { q.isNotEmpty() && it.command.startsWith(q) }.take(10)
            if (initialDelta != null) {
                selectedSuggestion.value = nextSuggestionIndex(-1, initialDelta, _suggestions.value.size)
            } else if (selectedSuggestion.value >= _suggestions.value.size) selectedSuggestion.value = -1
            suggestionsVisible.value = inputFocused && (query.isNotBlank() || suggestionsVisible.value)
        }
    }

    /** Sends a special key (arrows, ESC, TAB, HOME…) honoring emulator modes and sticky modifiers. */
    fun sendSpecialKey(keyCode: Int) {
        val managed = sessionManager.activeSession() ?: return
        val emulator = managed.session.emulator
        var keyMod = 0
        if (stickyCtrl.value != StickyState.OFF) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (stickyAlt.value != StickyState.OFF) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            emulator?.isCursorKeysApplicationMode ?: false,
            emulator?.isKeypadApplicationMode ?: false,
        ) ?: return
        managed.session.write(code)
        consumeStickies()
    }

    /** Sends literal text from the symbol keys. */
    fun sendLiteral(text: String) {
        if (text.isEmpty()) return
        val session = sessionManager.activeSession()?.session ?: return
        session.write(modifiedTerminalText(text, stickyCtrl.value, stickyAlt.value))
        consumeStickies()
    }

    /** Sends the input line to the active session and records it for frecency ranking. */
    fun sendInputLine() {
        val command = inputText.value
        if (command.isBlank()) return
        val managed = sessionManager.activeSession() ?: return
        managed.session.write(command + "\r")
        inputText.value = ""
        invalidateHistorySuggestions()
        viewModelScope.launch(Dispatchers.IO) { historyRepo.record(managed.profileId, command) }
    }

    // ---- Session plumbing ----------------------------------------------------------------------

    /** The view currently on screen; session callbacks repaint it. */
    @Volatile var boundView: TerminalView? = null

    private val sessionClient = object : BaseTerminalSessionClient() {
        override fun onColorsChanged(session: TerminalSession) {
            boundView?.takeIf { it.currentSession === session }?.let {
                it.setBackgroundColor(sessionBackground(session))
                it.invalidate()
            }
        }

        override fun onTextChanged(changedSession: TerminalSession) {
            applySessionTheme(changedSession)
            val view = boundView ?: return
            if (view.currentSession == changedSession) view.onScreenUpdated()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            _endedSessions.value = _endedSessions.value + finishedSession.mHandle
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onServerRequestedClipboardWrite(session: TerminalSession, text: String) {
            handleServerClipboardWrite(session, text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            (session ?: sessionManager.activeSession()?.session)?.emulator?.paste(text)
        }
    }

    private fun hostKeyPrompter(lease: InteractionLease) = object : HostKeyPrompter {
        override suspend fun onUnknownHost(info: HostKeyInfo): Boolean = lease.request {
            awaitPrompt { done -> UiPrompt.HostKey(info, changed = false, onResult = done) }
        }
        override suspend fun onHostKeyChanged(info: HostKeyInfo, oldFingerprint: String): Boolean = lease.request {
            awaitPrompt { done -> UiPrompt.HostKey(info, changed = true, onResult = done) }
        }
    }

    fun setActiveSession(id: Long) {
        invalidateHistorySuggestions()
        sessionManager.setActive(id)
    }

    fun closeSession(id: Long) {
        val managed = sessionManager.get(id)
        sessionManager.close(id)
        managed?.let { _endedSessions.value = _endedSessions.value - it.session.mHandle }
        if (sessionManager.sessions.value.isEmpty()) dismissSuggestions()
    }

    fun openLocalShell() {
        try {
            sessionManager.openLocalShellSession(sessionClient)
        } catch (e: Exception) {
            showError(e.message ?: "Could not open a local shell.")
        }
    }

    fun parseQuickConnect(input: String): QuickConnectTarget? = parseQuickConnectTarget(input)

    /**
     * Ad-hoc connect to `user@host[:port]` without a saved profile. [keyId] null means password
     * auth (prompted at connect). Without a profile, history is recorded only when the user
     * explicitly enables global history fallback.
     */
    fun quickConnect(target: QuickConnectTarget, keyId: Long?) {
        val lease = InteractionLease()
        var handedOff = false
        pendingConnections++
        _isConnecting.value = true
        viewModelScope.launch {
            try {
                val auth: SshAuthMethod = if (keyId == null) {
                    val password = awaitPrompt<CharArray?> { done ->
                        UiPrompt.Secret("Password", "${target.username}@${target.host}", done)
                    } ?: return@launch
                    SshAuthMethod.Password(password)
                } else {
                    val key = withContext(Dispatchers.IO) { keyRepo.get(keyId) }
                        ?: run { showError("The selected key was deleted."); return@launch }
                    keyAuthentication(key, lease)
                }
                val resolved = ResolvedConnection(
                    host = target.host,
                    port = target.port,
                    username = target.username,
                    auth = auth,
                    cancelPrompts = lease::cancel,
                )
                sessionManager.openSshSession(resolved, profileId = null, hostKeyPrompter(lease), sessionClient)
                handedOff = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "Could not connect.")
            } finally {
                if (!handedOff) lease.cancel()
                pendingConnections--
                _isConnecting.value = pendingConnections > 0
            }
        }
    }

    /** Full connect flow: resolve jump chain, gather credentials, open the tab. */
    fun connect(profileId: Long) {
        val lease = InteractionLease()
        var handedOff = false
        pendingConnections++
        _isConnecting.value = true
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { profileRepo.get(profileId) }
                if (profile == null) {
                    showError("Profile no longer exists.")
                    return@launch
                }
                val resolved = try {
                    resolve(profile, visited = mutableSetOf(), lease = lease)
                } catch (e: ConnectCancelled) {
                    return@launch
                } catch (e: Exception) {
                    showError(e.message ?: "Could not prepare connection.")
                    return@launch
                }
                sessionManager.openSshSession(resolved, profile.id, hostKeyPrompter(lease), sessionClient)
                handedOff = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "Could not connect.")
            } finally {
                if (!handedOff) lease.cancel()
                pendingConnections--
                _isConnecting.value = pendingConnections > 0
            }
        }
    }

    private fun keyAuthentication(key: dev.wissam.sshterm.data.db.KeyEntity, lease: InteractionLease): SshAuthMethod.PublicKey {
        val cipherAuthorizer = dev.wissam.sshterm.core.keys.KeyFileAuthorizer { request -> lease.request {
            awaitPrompt<Boolean> { done -> UiPrompt.KeyUnlock(
                "Decrypt “${key.name}” for this signature",
                "Authorize this software key operation with a strong biometric",
                done, cryptoCipher = request.cipher,
            ) }
        } }
        val provider = keyManager.deferredKeyProviderFor(key, cipherAuthorizer) {
            lease.request { awaitPrompt<CharArray?> { done -> UiPrompt.Secret("Key passphrase", key.name, done) } }
        }
        return SshAuthMethod.PublicKey(provider, signingAuthorizer(key.name, lease))
    }

    private fun signingAuthorizer(keyName: String, lease: InteractionLease) =
        dev.wissam.sshterm.core.ssh.SigningAuthorizer { request -> lease.request {
            awaitPrompt<Boolean> { done -> UiPrompt.KeyUnlock(
                "Sign with “$keyName”",
                "${request.username}@${request.host}:${request.port} · ${request.algorithm}",
                done, cryptoSignature = request.cryptoSignature,
            ) }
        } }

    private class ConnectCancelled : Exception()

    private suspend fun resolve(
        profile: ProfileEntity,
        visited: MutableSet<Long>,
        isTarget: Boolean = true,
        lease: InteractionLease,
    ): ResolvedConnection {
        if (!visited.add(profile.id)) {
            throw IllegalStateException("Jump host loop detected at '${profile.name}'.")
        }
        if (visited.size > MAX_JUMP_DEPTH) {
            throw IllegalStateException("Jump host chain deeper than $MAX_JUMP_DEPTH hops.")
        }

        val jump = profile.jumpHostProfileId
            ?.let { withContext(Dispatchers.IO) { profileRepo.get(it) } }
            ?.let { resolve(it, visited, isTarget = false, lease = lease) }

        // A referenced identity supplies the username/auth; otherwise the profile's own fields do.
        val identity = profile.identityId?.let { withContext(Dispatchers.IO) { identityRepo.get(it) } }
        val effUsername = identity?.username ?: profile.username
        val effAuthType = identity?.authType ?: profile.authType
        val effKeyId = identity?.keyId ?: profile.keyId

        val auth: SshAuthMethod = when (effAuthType) {
            AuthType.KEYBOARD_INTERACTIVE -> SshAuthMethod.KeyboardInteractive { challenge ->
                lease.request {
                    awaitPrompt<CharArray?> { done -> UiPrompt.Secret(
                        "Server authentication: ${challenge.target}",
                        listOf(challenge.name, challenge.instructions, challenge.prompt).filter { it.isNotBlank() }.joinToString("\n"),
                        done, echo = challenge.echo,
                    ) }
                }
            }
            AuthType.PASSWORD -> {
                // Never persisted: prompted at every connect by design.
                val password = awaitPrompt<CharArray?> { done ->
                    UiPrompt.Secret("Password", "$effUsername@${profile.host}", done)
                } ?: throw ConnectCancelled()
                SshAuthMethod.Password(password)
            }
            AuthType.PUBLIC_KEY -> {
                val keyId = effKeyId
                    ?: throw IllegalStateException("Profile '${profile.name}' has no key assigned.")
                val key = withContext(Dispatchers.IO) { keyRepo.get(keyId) }
                    ?: throw IllegalStateException("The key for '${profile.name}' was deleted.")

                keyAuthentication(key, lease)
            }
        }

        // Forwards belong to the endpoint the user is connecting to, not to bastions in the
        // chain. isTarget is captured per-call, so jump-host recursion (isTarget = false)
        // never picks them up and the target never loses them.
        val forwards = if (isTarget) {
            withContext(Dispatchers.IO) { forwardingRepo.enabledForProfile(profile.id) }
                .map { rule ->
                    ForwardSpec(
                        direction = when (rule.kind) {
                            ForwardKind.LOCAL -> ForwardDirection.LOCAL
                            ForwardKind.REMOTE -> ForwardDirection.REMOTE
                        },
                        bindHost = rule.bindHost,
                        bindPort = rule.bindPort,
                        targetHost = rule.targetHost,
                        targetPort = rule.targetPort,
                        key = rule.id,
                    )
                }
        } else {
            emptyList()
        }

        return ResolvedConnection(
            host = profile.host,
            port = profile.port,
            username = effUsername,
            auth = auth,
            termType = profile.termType,
            jumpHost = jump,
            forwards = forwards,
            cancelPrompts = lease::cancel,
        )
    }

    /** Cancellation of a connector also removes its queued or visible prompt. */
    private suspend fun <T> awaitPrompt(build: ((T) -> Unit) -> UiPrompt): T =
        promptQueue.await(build)

    fun requestBatteryOptimizationExemption(onLaunchSettings: () -> Unit) {
        viewModelScope.launch {
            val accepted = awaitPrompt<Boolean> { UiPrompt.BatteryOptimization(it) }
            if (accepted) onLaunchSettings()
        }
    }

    private fun showError(message: String) {
        viewModelScope.launch {
            awaitPrompt<Unit> { done -> UiPrompt.Error(message) { done(Unit) } }
        }
    }

    override fun onCleared() {
        promptQueue.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_JUMP_DEPTH = 5
    }
}
