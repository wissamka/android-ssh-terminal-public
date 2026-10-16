package dev.wissam.sshterm.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.wissam.sshterm.R
import dev.wissam.sshterm.ui.security.secretKeyboardOptions
import dev.wissam.sshterm.ui.security.showAuthPrompt
import kotlinx.coroutines.flow.collectLatest

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel(),
    onNavigateToProfiles: () -> Unit,
    onReconnect: (Long) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsState()
    val activeId by viewModel.activeSessionId.collectAsState()
    val ended by viewModel.endedSessions.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val themeId by viewModel.themeId.collectAsState()
    val sessionThemes by viewModel.sessionThemes.collectAsState()
    val context = LocalContext.current

    val active = sessions.firstOrNull { it.id == activeId }
    val isConnecting by viewModel.isConnecting.collectAsState()
    val clipboardSnackbarHostState = remember { SnackbarHostState() }

    // Cancelling this effect on tab change, session end, or navigation invalidates consent.
    // Never inspect or mutate the clipboard until the user presses Copy.
    LaunchedEffect(activeId, ended) {
        viewModel.clipboardRequest.collectLatest { request ->
            if (request == null) return@collectLatest
            try {
                val session = active?.session ?: return@collectLatest
                if (request.sessionId != session.mHandle ||
                    session.mHandle in ended || !session.isRunning
                ) return@collectLatest
                val result = clipboardSnackbarHostState.showSnackbar(
                    message = "Server requests copying ${request.charCount} characters to clipboard",
                    actionLabel = "Copy",
                    withDismissAction = true,
                    // The consent policy owns the 10-second deadline; avoid a second, shorter UI timer.
                    duration = androidx.compose.material3.SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.acceptRemoteClipboardRequest(request.id)
                }
            } finally {
                viewModel.dismissRemoteClipboardRequest(request.id)
            }
        }
    }

    // If every tab is gone, fall back to the profile list — but not while a connect is still
    // resolving (its host-key/credential prompt renders on this screen and the session tab
    // appears a moment later).
    LaunchedEffect(sessions.isEmpty(), isConnecting, prompt) {
        if (sessions.isEmpty() && !isConnecting && prompt == null) onNavigateToProfiles()
    }

    // First-connect battery optimization ask (contextual, not at install time).
    LaunchedEffect(sessions.isNotEmpty()) {
        if (sessions.isEmpty()) return@LaunchedEffect
        val prefs = context.getSharedPreferences("sshterm", Context.MODE_PRIVATE)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!prefs.getBoolean("battery_prompted", false) &&
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        ) {
            prefs.edit().putBoolean("battery_prompted", true).apply()
            viewModel.requestBatteryOptimizationExemption {
                @SuppressLint("BatteryLife")
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(intent) }
            }
        }
    }

    val activeTheme = active?.session?.mHandle?.let { sessionThemes[it] } ?: themeId
    val themeBg = Color(dev.wissam.sshterm.core.terminal.TerminalThemes.backgroundColor(activeTheme))
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(themeBg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        SessionTabs(
            sessions = sessions,
            activeId = activeId,
            endedHandles = ended,
            onSelect = viewModel::setActiveSession,
            onClose = viewModel::closeSession,
            onAdd = onNavigateToProfiles,
        )

        Box(modifier = Modifier.weight(1f)) {
            if (active != null) {
                androidx.compose.runtime.key(active.id) {
                    TerminalViewHost(viewModel = viewModel, session = active.session)
                }
                if (active.session.mHandle in ended) {
                    SessionEndedOverlay(
                        canReconnect = active.profileId != null,
                        onReconnect = {
                            val profileId = active.profileId
                            viewModel.closeSession(active.id)
                            profileId?.let(onReconnect)
                        },
                        onClose = { viewModel.closeSession(active.id) },
                    )
                }
            }
        }

        SuggestionsDropdown(viewModel)
        CommandInputBar(viewModel)
        ExtraKeysRow(viewModel)
    }
    SnackbarHost(
        hostState = clipboardSnackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 96.dp),
    ) { data ->
        Snackbar(data)
    }
    }

    prompt?.let { current -> androidx.compose.runtime.key(current) { PromptHost(current) } }
}

// ---- Terminal view interop -------------------------------------------------------------------

@Composable
private fun TerminalViewHost(viewModel: TerminalViewModel, session: TerminalSession) {
    val fontSizeSp by viewModel.fontSizeSp.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.boundView = null }
    }

    val sessionThemes by viewModel.sessionThemes.collectAsState()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                val startSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat(), ctx.resources.displayMetrics
                )
                setTextSize(startSizePx.toInt())
                ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_nf)?.let(::setTypeface)
                setBackgroundColor(viewModel.sessionBackground(session))
                // Pinch-zoom persists the new size so it survives across sessions/restarts.
                setTerminalViewClient(makeClient(ctx, viewModel, this) { newSizePx ->
                    setTextSize(newSizePx.toInt())
                    val sp = newSizePx / ctx.resources.displayMetrics.scaledDensity
                    viewModel.onFontSizeChanged(sp.toInt())
                })
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
        },
        update = { view ->
            viewModel.boundView = view
            // Re-apply the persisted font size and theme background (e.g. changed in Settings).
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat(), view.resources.displayMetrics
            )
            view.setTextSize(px.toInt())
            @Suppress("UNUSED_EXPRESSION") sessionThemes // re-run this block when the theme changes
            viewModel.applySessionTheme(session)
            view.setBackgroundColor(viewModel.sessionBackground(session))
            if (view.currentSession !== session) {
                view.attachSession(session)
                // attachSession() nulls the view's mEmulator, and the immediate updateSize()
                // no-ops while the view is still 0x0 (layout hasn't run). Post a redraw for the
                // next UI cycle so that once the view is measured, mEmulator is repopulated and
                // the accumulated screen buffer paints — otherwise navigating back to a tab can
                // leave a stale frame until the next SSH byte happens to arrive.
                view.post { view.onScreenUpdated() }
            }
            view.invalidate()
        },
    )
}

private fun makeClient(
    context: Context,
    viewModel: TerminalViewModel,
    view: TerminalView,
    onFontSizeChanged: (Float) -> Unit,
): TerminalViewClient = object : TerminalViewClient {

    private var currentFontScale = 1f

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val base = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics
            )
            currentFontScale = (currentFontScale * scale).coerceIn(0.6f, 2.5f)
            onFontSizeChanged(base * currentFontScale)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        viewModel.consumeStickies()
        return false
    }

    override fun onLongPress(event: MotionEvent) = false

    // Sticky modifiers work for BOTH the soft keyboard and physical/Bluetooth keyboards:
    // TerminalView consults these on every key path.
    override fun readControlKey() = viewModel.stickyCtrl.value != StickyState.OFF
    override fun readAltKey() = viewModel.stickyAlt.value != StickyState.OFF
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        viewModel.consumeStickies()
        return false
    }

    override fun onEmulatorSet() {
        view.currentSession?.let {
            viewModel.applySessionTheme(it)
            view.setBackgroundColor(viewModel.sessionBackground(it))
        }
    }

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

// ---- Tabs --------------------------------------------------------------------------------------

@Composable
private fun SessionTabs(
    sessions: List<dev.wissam.sshterm.core.ssh.SshSessionManager.ManagedSession>,
    activeId: Long?,
    endedHandles: Set<String>,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { managed ->
                val isActive = managed.id == activeId
                val isEnded = managed.session.mHandle in endedHandles
                Surface(
                    color = if (isActive) Color(0xFF0D1117) else Color.Transparent,
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                    onClick = { onSelect(managed.id) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    ) {
                        Text(
                            text = managed.displayName,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isEnded -> Color(0xFF8B949E)
                                isActive -> Color(0xFF3FB950)
                                else -> Color(0xFFE6EDF3)
                            },
                        )
                        IconButton(onClick = { onClose(managed.id) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close ${managed.displayName} session",
                                tint = Color(0xFF8B949E),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "New session", tint = Color(0xFF3FB950))
        }
    }
}

@Composable
private fun SessionEndedOverlay(canReconnect: Boolean, onReconnect: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA0D1117)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Color(0xFF161B22), shape = RoundedCornerShape(12.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Session ended", color = Color(0xFFE6EDF3), fontWeight = FontWeight.Bold)
                Row {
                    if (canReconnect) {
                        TextButton(onClick = onReconnect) { Text("Reconnect", color = Color(0xFF3FB950)) }
                    }
                    TextButton(onClick = onClose) { Text("Close", color = Color(0xFF8B949E)) }
                }
            }
        }
    }
}

// ---- Autocomplete + input bar -------------------------------------------------------------------

@Composable
private fun SuggestionsDropdown(viewModel: TerminalViewModel) {
    val visible by viewModel.suggestionsVisible.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val selected by viewModel.selectedSuggestion.collectAsState()
    if (!visible || suggestions.isEmpty()) return

    Surface(color = Color(0xFF161B22)) {
        LazyColumn(modifier = Modifier.height((36 * minOf(suggestions.size, 5)).dp)) {
            items(suggestions.withIndex().toList()) { (index, suggestion) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index == selected) Color(0xFF21262D) else Color.Transparent)
                        .height(36.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        suggestion.snippetName != null -> Icon(
                            Icons.Default.Bookmark,
                            contentDescription = "Snippet",
                            tint = Color(0xFF3FB950),
                            modifier = Modifier.width(16.dp),
                        )
                        suggestion.fromSeed -> Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = Color(0xFF484F58),
                            modifier = Modifier.width(16.dp),
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.selectedSuggestion.value = index
                            viewModel.commitSelectedSuggestion()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            // Snippets show "name — body preview"; commands show the command.
                            suggestion.snippetName?.let { "$it — ${suggestion.command.replace("\n", " ⏎ ")}" }
                                ?: suggestion.command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (index == selected) Color(0xFF3FB950) else Color(0xFFE6EDF3),
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandInputBar(viewModel: TerminalViewModel) {
    val input by viewModel.inputText.collectAsState()
    var fieldValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(input)) }
    var acceptedValue by remember { mutableStateOf(fieldValue) }
    val consumedPhysicalKeys = remember { mutableSetOf<Int>() }
    LaunchedEffect(input) {
        if (acceptedValue.text != input) {
            fieldValue = androidx.compose.ui.text.input.TextFieldValue(input, androidx.compose.ui.text.TextRange(input.length))
            acceptedValue = fieldValue
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onInputFocusChanged(false) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Up/down drive the frecency dropdown — this is the novel input model, not history replay.
        IconButton(onClick = { viewModel.moveSuggestion(-1) }) {
            Icon(Icons.Default.KeyboardArrowUp, "Previous suggestion", tint = Color(0xFF8B949E))
        }
        IconButton(onClick = { viewModel.moveSuggestion(1) }) {
            Icon(Icons.Default.KeyboardArrowDown, "Next suggestion", tint = Color(0xFF8B949E))
        }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { edit ->
                val sent = edit.text != viewModel.inputText.value &&
                    viewModel.onInputChanged(edit.text, composing = edit.composition != null)
                if (sent) {
                    // Reject the inserted/preedit key so the IME and field keep the original buffer.
                    fieldValue = acceptedValue.copy(composition = null)
                } else {
                    fieldValue = edit
                    if (edit.composition == null || viewModel.inputText.value == edit.text) {
                        acceptedValue = edit.copy(composition = null)
                    }
                }
            },
            modifier = Modifier.weight(1f)
                .onFocusChanged {
                    viewModel.onInputFocusChanged(it.isFocused)
                    if (!it.isFocused) consumedPhysicalKeys.clear()
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (event.type == KeyEventType.KeyUp && consumedPhysicalKeys.remove(native.keyCode)) {
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        if (native.keyCode in consumedPhysicalKeys) return@onPreviewKeyEvent true
                        val plainMeta = native.metaState and
                            (android.view.KeyEvent.META_CTRL_MASK or android.view.KeyEvent.META_ALT_MASK).inv()
                        if (viewModel.handleModifiedCommandKey(native.keyCode, native.getUnicodeChar(plainMeta),
                                native.isCtrlPressed, native.isAltPressed)) {
                            consumedPhysicalKeys.add(native.keyCode)
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Let an IME finish preedit before Enter can commit or send the buffered command.
                    if (fieldValue.composition != null) return@onPreviewKeyEvent false
                    val key = when (event.key) {
                        Key.DirectionUp -> "Up"
                        Key.DirectionDown -> "Down"
                        Key.Enter, Key.NumPadEnter -> "Enter"
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        // Holding Enter must not execute the suggestion just committed by its first down.
                        if (key == "Enter" && event.nativeKeyEvent.repeatCount > 0) true
                        else viewModel.handleCommandKey(key)
                    } else event.type == KeyEventType.KeyUp
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (fieldValue.composition == null) viewModel.handleCommandKey("Enter")
            }),
            placeholder = { Text("command", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFE6EDF3),
            ),
            // The terminal chrome is always dark, independent of the app theme, so pin the field's
            // border/cursor/placeholder to fixed light-on-dark colors. Inheriting MaterialTheme
            // would render dark-on-dark (invisible border/cursor) under the Light app theme.
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF3FB950),
                unfocusedBorderColor = Color(0xFF30363D),
                cursorColor = Color(0xFF3FB950),
                focusedPlaceholderColor = Color(0xFF8B949E),
                unfocusedPlaceholderColor = Color(0xFF8B949E),
                focusedTextColor = Color(0xFFE6EDF3),
                unfocusedTextColor = Color(0xFFE6EDF3),
            ),
            singleLine = true,
        )
        IconButton(onClick = viewModel::sendInputLine) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send command", tint = Color(0xFF3FB950))
        }
    }
}

// ---- Extra keys row ------------------------------------------------------------------------------

@Composable
private fun ExtraKeysRow(viewModel: TerminalViewModel) {
    val ctrl by viewModel.stickyCtrl.collectAsState()
    val alt by viewModel.stickyAlt.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF10151C))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ExtraKey("ESC") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_ESCAPE) }
        StickyKey("CTRL", ctrl) { viewModel.toggleSticky(viewModel.stickyCtrl) }
        StickyKey("ALT", alt) { viewModel.toggleSticky(viewModel.stickyAlt) }
        ExtraKey("TAB") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_TAB) }
        ExtraKeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") {
            viewModel.sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        ExtraKeyIcon(Icons.Default.KeyboardArrowDown, "Down") {
            viewModel.sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        ExtraKeyIcon(Icons.Default.KeyboardArrowUp, "Up") {
            viewModel.sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP)
        }
        ExtraKeyIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") {
            viewModel.sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        listOf("-", "/", "|", "~", "`", "\\").forEach { symbol ->
            ExtraKey(symbol) { viewModel.sendLiteral(symbol) }
        }
        ExtraKey("HOME") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_MOVE_HOME) }
        ExtraKey("END") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_MOVE_END) }
        ExtraKey("PGUP") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_PAGE_UP) }
        ExtraKey("PGDN") { viewModel.sendSpecialKey(KeyEvent.KEYCODE_PAGE_DOWN) }
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFE6EDF3))
    }
}

@Composable
private fun ExtraKeyIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color(0xFFE6EDF3))
    }
}

@Composable
private fun StickyKey(label: String, state: StickyState, onClick: () -> Unit) {
    val color = when (state) {
        StickyState.OFF -> Color(0xFFE6EDF3)
        StickyState.ARMED -> Color(0xFF3FB950)
        StickyState.LOCKED -> Color(0xFFF85149)
    }
    TextButton(onClick = onClick) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = color,
            fontWeight = if (state != StickyState.OFF) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---- Prompt dialogs -------------------------------------------------------------------------------

@Composable
internal fun PromptHost(prompt: UiPrompt) {
    when (prompt) {
        is UiPrompt.Secret -> SecretDialog(prompt)
        is UiPrompt.KeyUnlock -> KeyUnlockEffect(prompt)
        is UiPrompt.HostKey -> HostKeyDialog(prompt)
        is UiPrompt.BatteryOptimization -> BatteryDialog(prompt)
        is UiPrompt.Error -> ErrorDialog(prompt)
    }
}

@Composable
private fun SecretDialog(prompt: UiPrompt.Secret) {
    var value by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.onResult(null) },
        title = { Text(prompt.title) },
        text = {
            Column {
                Text(prompt.hint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    keyboardOptions = secretKeyboardOptions(echo = prompt.echo),
                    visualTransformation = if (prompt.echo) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.onResult(value.toCharArray()) }) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = { prompt.onResult(null) }) { Text("Cancel") }
        },
    )
}

@Composable
private fun KeyUnlockEffect(prompt: UiPrompt.KeyUnlock) {
    val context = LocalContext.current
    DisposableEffect(prompt, context) {
        val activity = context as? FragmentActivity
        val operation = if (activity == null) {
            prompt.onResult(false)
            null
        } else {
            showAuthPrompt(
                activity, prompt.title, prompt.subtitle,
                strongOnly = true,
                cryptoObject = prompt.cryptoSignature?.let { androidx.biometric.BiometricPrompt.CryptoObject(it) }
                    ?: prompt.cryptoCipher?.let { androidx.biometric.BiometricPrompt.CryptoObject(it) },
                canAuthorize = {
                    (activity as? dev.wissam.sshterm.ui.MainActivity)?.appLock?.locked?.value == false
                },
                onResult = prompt.onResult,
            )
        }
        // Denies the suspended connection before any late biometric success can resume it.
        onDispose { operation?.cancel() }
    }
}

@Composable
private fun HostKeyDialog(prompt: UiPrompt.HostKey) {
    AlertDialog(
        onDismissRequest = { prompt.onResult(false) },
        title = {
            Text(
                if (prompt.changed) "⚠ HOST KEY CHANGED" else "Unknown host",
                color = if (prompt.changed) Color(0xFFF85149) else Color.Unspecified,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                if (prompt.changed) {
                    Text(
                        "The identity of ${prompt.info.host} has CHANGED since you last " +
                            "connected. Someone could be intercepting this connection " +
                            "(man-in-the-middle), or the server was reinstalled. The stored " +
                            "key is kept; accepting trusts the new key for this attempt only.",
                        color = Color(0xFFF85149),
                        fontSize = 13.sp,
                    )
                } else {
                    Text(
                        "First connection to ${prompt.info.host}. Verify the fingerprint " +
                            "matches the server before trusting it.",
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "\n${prompt.info.keyType}\n${prompt.info.fingerprint}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.onResult(true) }) {
                Text(
                    if (prompt.changed) "Accept risk" else "Trust host",
                    color = if (prompt.changed) Color(0xFFF85149) else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.onResult(false) }) { Text("Disconnect") }
        },
    )
}

@Composable
private fun BatteryDialog(prompt: UiPrompt.BatteryOptimization) {
    AlertDialog(
        onDismissRequest = { prompt.onResult(false) },
        title = { Text("Keep sessions alive") },
        text = {
            Text(
                "Android's battery optimizer can kill SSH connections when the screen is off " +
                    "or the app is in the background. Exempting SSH Term keeps long-running " +
                    "sessions (tail -f, htop, tmux) connected.",
            )
        },
        confirmButton = {
            TextButton(onClick = { prompt.onResult(true) }) { Text("Exempt") }
        },
        dismissButton = {
            TextButton(onClick = { prompt.onResult(false) }) { Text("Not now") }
        },
    )
}

@Composable
private fun ErrorDialog(prompt: UiPrompt.Error) {
    AlertDialog(
        onDismissRequest = prompt.onDismiss,
        title = { Text("Connection error") },
        text = { Text(prompt.message) },
        confirmButton = { TextButton(onClick = prompt.onDismiss) { Text("OK") } },
    )
}
