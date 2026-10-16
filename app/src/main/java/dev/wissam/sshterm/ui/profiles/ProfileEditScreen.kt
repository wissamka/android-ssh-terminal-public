package dev.wissam.sshterm.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wissam.sshterm.data.db.AuthType
import dev.wissam.sshterm.data.db.ProfileEntity
import dev.wissam.sshterm.ui.keys.GenerateKeyDialog
import dev.wissam.sshterm.ui.keys.ImportKeyDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: Long?,
    viewModel: ProfilesViewModel = hiltViewModel(),
    onDone: () -> Unit,
    onEditForwards: (Long) -> Unit = {},
) {
    val keys by viewModel.keys.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val identities by viewModel.identities.collectAsState()

    var loaded by remember { mutableStateOf(profileId == null) }
    var existing by remember { mutableStateOf<ProfileEntity?>(null) }

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(AuthType.PASSWORD) }
    var keyId by remember { mutableStateOf<Long?>(null) }
    var identityId by remember { mutableStateOf<Long?>(null) }
    var colorScheme by remember { mutableStateOf("default") }
    var themeExpanded by remember { mutableStateOf(false) }
    var jumpId by remember { mutableStateOf<Long?>(null) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var generatedPublicKey by remember { mutableStateOf<String?>(null) }
    val keyGenBusy by viewModel.keyGenBusy.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val keyGenError by viewModel.keyGenError.collectAsState()

    LaunchedEffect(profileId) {
        if (profileId != null) {
            viewModel.get(profileId)?.let { profile ->
                existing = profile
                name = profile.name
                host = profile.host
                port = profile.port.toString()
                username = profile.username
                authType = profile.authType
                keyId = profile.keyId
                identityId = profile.identityId
                jumpId = profile.jumpHostProfileId
                colorScheme = profile.colorScheme
            }
            loaded = true
        }
    }

    // With an identity, username/auth come from it, so the profile's own fields aren't required.
    val usesIdentity = identityId != null
    val valid = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() in 1..65535 &&
        (usesIdentity || (username.isNotBlank() && (authType != AuthType.PUBLIC_KEY || keyId != null)))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == null) "New profile" else "Edit profile") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid && loaded,
                        onClick = {
                            val base = existing ?: ProfileEntity(
                                name = "", host = "", username = "", authType = AuthType.PASSWORD,
                            )
                            viewModel.save(
                                base.copy(
                                    name = name.trim(),
                                    host = host.trim(),
                                    port = port.toInt(),
                                    username = username.trim(),
                                    authType = authType,
                                    keyId = if (authType == AuthType.PUBLIC_KEY) keyId else null,
                                    identityId = identityId,
                                    jumpHostProfileId = jumpId,
                                    colorScheme = colorScheme,
                                ),
                            ) { onDone() }
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(3f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (identities.isNotEmpty()) {
                ReferencePicker(
                    label = "Identity (reusable username + auth)",
                    options = identities.map { it.id to it.name },
                    selectedId = identityId,
                    allowNone = true,
                    onSelect = { identityId = it },
                )
            }

            if (!usesIdentity) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authType == AuthType.PASSWORD,
                        onClick = { authType = AuthType.PASSWORD },
                        label = { Text("Password (asked at connect)") },
                    )
                    FilterChip(
                        selected = authType == AuthType.PUBLIC_KEY,
                        onClick = { authType = AuthType.PUBLIC_KEY },
                        label = { Text("Key") },
                    )
                }

                androidx.compose.material3.FilterChip(
                    selected = authType == AuthType.KEYBOARD_INTERACTIVE,
                    onClick = { authType = AuthType.KEYBOARD_INTERACTIVE },
                    label = { Text("Interactive / OTP") },
                )
                if (authType == AuthType.PUBLIC_KEY) {
                    ReferencePicker(
                        label = "SSH key",
                        options = keys.map { it.id to it.name },
                        selectedId = keyId,
                        allowNone = false,
                        onSelect = { keyId = it },
                        extraOptionLabel = "＋ Generate new key…",
                        onExtraOption = { showGenerateDialog = true },
                        extraOptionLabel2 = "⭳ Import existing key…",
                        onExtraOption2 = { showImportDialog = true },
                    )
                    if (keyGenBusy) {
                        Text(
                            "Generating key…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Text(
                    "Username and auth come from the selected identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ReferencePicker(
                label = "Jump host (ProxyJump)",
                options = profiles.filter { it.id != profileId }.map { it.id to it.name },
                selectedId = jumpId,
                allowNone = true,
                onSelect = { jumpId = it },
            )

            val themeChoices = listOf("default" to "Use app theme") +
                dev.wissam.sshterm.core.terminal.TerminalThemes.profileChoices.map { it.id to it.label }
            ExposedDropdownMenuBox(expanded = themeExpanded, onExpandedChange = { themeExpanded = it }) {
                OutlinedTextField(
                    value = themeChoices.firstOrNull { it.first == colorScheme }?.second ?: "Use app theme",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Terminal theme") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(themeExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                    themeChoices.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            colorScheme = id
                            themeExpanded = false
                        })
                    }
                }
            }
            Text("Use app theme follows Settings. A profile theme overrides it for this profile's sessions.",
                style = MaterialTheme.typography.bodySmall)

            // Forwards need a saved profile (FK). Offer the editor only when one exists.
            if (profileId != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onEditForwards(profileId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Port forwarding…") }
            } else {
                Text(
                    "Save the profile to add port forwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { keyName, algorithm, comment ->
                showGenerateDialog = false
                viewModel.generateKey(keyName, algorithm, comment) { newId, publicKey ->
                    keyId = newId
                    generatedPublicKey = publicKey
                }
            },
        )
    }
    if (showImportDialog) {
        ImportKeyDialog(
            onDismiss = { showImportDialog = false },
            onImport = { keyName, text, passphrase ->
                showImportDialog = false
                viewModel.importKey(keyName, text, passphrase) { newId -> keyId = newId }
            },
        )
    }
    generatedPublicKey?.let { publicKey ->
        PublicKeyDialog(publicKey = publicKey, onDismiss = { generatedPublicKey = null })
    }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearSaveError,
            title = { Text("Profile not saved") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearSaveError) { Text("OK") } },
        )
    }
    keyGenError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearKeyGenError,
            title = { Text("Key error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearKeyGenError) { Text("OK") }
            },
        )
    }
}

/** Shown right after inline key generation: the one thing to paste into the server. */
@Composable
private fun PublicKeyDialog(publicKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Key created — authorize it on your server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copy this PUBLIC key and paste it into your provider's SSH key settings " +
                        "or append it to ~/.ssh/authorized_keys on the server. The private key " +
                        "stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        publicKey,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("public key", publicKey))
                copied = true
            }) { Text(if (copied) "Copied ✓" else "Copy public key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferencePicker(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    allowNone: Boolean,
    onSelect: (Long?) -> Unit,
    extraOptionLabel: String? = null,
    onExtraOption: () -> Unit = {},
    extraOptionLabel2: String? = null,
    onExtraOption2: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: if (allowNone) "None" else "Select…"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = { onSelect(null); expanded = false },
                )
            }
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
            if (extraOptionLabel != null) {
                DropdownMenuItem(
                    text = { Text(extraOptionLabel, color = MaterialTheme.colorScheme.primary) },
                    onClick = { onExtraOption(); expanded = false },
                )
            }
            if (extraOptionLabel2 != null) {
                DropdownMenuItem(
                    text = { Text(extraOptionLabel2, color = MaterialTheme.colorScheme.primary) },
                    onClick = { onExtraOption2(); expanded = false },
                )
            }
        }
    }
}
