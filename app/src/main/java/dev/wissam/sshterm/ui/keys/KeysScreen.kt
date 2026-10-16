package dev.wissam.sshterm.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wissam.sshterm.ui.security.secretKeyboardOptions
import dev.wissam.sshterm.core.keys.GeneratedKeyAlgorithm
import dev.wissam.sshterm.data.db.KeyEntity
import dev.wissam.sshterm.core.keys.KeyProtectionMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    viewModel: KeysViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val keys by viewModel.keys.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val upgradeOffer by viewModel.upgradeOffer.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelOperations() } }
    val context = LocalContext.current

    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<KeyEntity?>(null) }
    var upgradeTarget by remember { mutableStateOf<KeyEntity?>(null) }
    var exportTarget by remember { mutableStateOf<KeyEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(enabled = !busy, onClick = { showImport = true }) { Text("Import") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) showGenerate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Generate") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp)) }
            }
            if (keys.isEmpty() && !busy) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No keys yet.")
                    Text(
                        "Generate an Ed25519 key (recommended) or an Android Keystore key, " +
                            "then paste the public key into your server's authorized_keys.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(keys, key = { it.key.id }) { item ->
                        val key = item.key
                        KeyCard(
                            key = key,
                            protection = item.protection,
                            software = item.software,
                            busy = busy,
                            onUpgrade = { upgradeTarget = key },
                            canExport = viewModel.canExport(key),
                            onCopyPublic = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("public key", key.publicKeyOpenSsh))
                            },
                            onExport = { exportTarget = key },
                            onDelete = { confirmDelete = key },
                        )
                    }
                }
            }
        }
    }

    (upgradeTarget ?: upgradeOffer)?.let { key ->
        AlertDialog(
            onDismissRequest = { upgradeTarget = null; viewModel.dismissUpgradeOffer() },
            title = { Text("Protect “${key.name}”") },
            text = { Text("Your key is already saved. Upgrade uses strong biometric checks to encrypt and verify a protected copy, then safely removes the legacy recovery copy. Cancelling keeps your saved material; the card will show any remaining upgrade step. Software keys remain software keys.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                upgradeTarget = null; viewModel.upgrade(key)
            }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { upgradeTarget = null; viewModel.dismissUpgradeOffer() }) { Text("Not now") } },
        )
    }
    prompt?.let { dev.wissam.sshterm.ui.terminal.PromptHost(it) }

    if (showGenerate) {
        GenerateKeyDialog(
            onDismiss = { showGenerate = false },
            onGenerate = { name, algorithm, comment ->
                showGenerate = false
                viewModel.generate(name, algorithm, comment)
            },
        )
    }
    if (showImport) {
        ImportKeyDialog(
            onDismiss = { showImport = false },
            onImport = { name, text, passphrase ->
                showImport = false
                viewModel.import(name, text, passphrase)
            },
        )
    }
    confirmDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete key “${key.name}”?") },
            text = {
                Text(
                    "The private key material is destroyed permanently. Profiles using this " +
                        "key will stop authenticating.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(key); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
    exportTarget?.let { key ->
        ExportKeyFlow(
            key = key,
            onDismiss = { exportTarget = null },
            onCancel = viewModel::cancelOperations,
            onExport = { passphrase, deliver ->
                viewModel.export(key, passphrase) { text -> deliver(text) }
            },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Key error") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
        )
    }
}

@Composable
private fun KeyCard(
    key: KeyEntity,
    protection: KeyProtectionMetadata,
    software: SoftwareProtection?,
    busy: Boolean,
    onUpgrade: () -> Unit,
    canExport: Boolean,
    onCopyPublic: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(key.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        key.algorithm,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopyPublic) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy public key")
                }
                // Only offer export where we can re-encrypt to a portable, protected file
                // (software Ed25519). Keystore and other types are withheld.
                if (canExport) {
                    IconButton(enabled = !busy, onClick = onExport) {
                        Icon(Icons.Default.Upload, contentDescription = "Export private key")
                    }
                }
                IconButton(enabled = !busy, onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete key")
                }
            }
            // The protection tier is a real threat-model difference; show it, don't hide it.
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        protection.label,
                        fontSize = 11.sp,
                    )
                },
            )
            Text(
                protection.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            software?.let { state ->
                Text(state.label, style = MaterialTheme.typography.labelLarge)
                Text(state.detail, style = MaterialTheme.typography.bodySmall)
                if (state == SoftwareProtection.LEGACY || state == SoftwareProtection.PENDING) {
                    TextButton(enabled = !busy, onClick = onUpgrade) {
                        Text(if (state == SoftwareProtection.PENDING) "Finalize upgrade" else "Upgrade protection")
                    }
                }
            }
        }
    }
}

/** Shared with ProfileEditScreen, which offers inline key generation from its key picker. */
@Composable
fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (name: String, algorithm: GeneratedKeyAlgorithm, comment: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(GeneratedKeyAlgorithm.ED25519) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                // ssh-keygen -C equivalent: embedded in the public key line so you can tell
                // keys apart in authorized_keys. Conventionally an email.
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (e.g. your email, optional)") },
                    singleLine = true,
                )
                Text(
                    "Keystore keys are non-exportable. Hardware protection depends on this device " +
                        "and algorithm; the actual protection is checked after generation and shown " +
                        "in the key list. StrongBox is requested when available, with fallback to " +
                        "ordinary Keystore if StrongBox is unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Algorithm", style = MaterialTheme.typography.labelLarge)
                Column {
                    AlgorithmOption(
                        "Ed25519 (recommended)",
                        "Modern default; software key, encrypted at rest",
                        algorithm == GeneratedKeyAlgorithm.ED25519,
                    ) { algorithm = GeneratedKeyAlgorithm.ED25519 }
                    AlgorithmOption(
                        "ECDSA P-256 — Keystore",
                        "Generated inside Android Keystore, non-exportable",
                        algorithm == GeneratedKeyAlgorithm.ECDSA_P256,
                    ) { algorithm = GeneratedKeyAlgorithm.ECDSA_P256 }
                    AlgorithmOption(
                        "RSA 3072 — Keystore",
                        "For servers that only accept RSA",
                        algorithm == GeneratedKeyAlgorithm.RSA_3072,
                    ) { algorithm = GeneratedKeyAlgorithm.RSA_3072 }
                    AlgorithmOption(
                        "RSA 4096 — Keystore",
                        "Slower; rarely needed over RSA 3072",
                        algorithm == GeneratedKeyAlgorithm.RSA_4096,
                    ) { algorithm = GeneratedKeyAlgorithm.RSA_4096 }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onGenerate(name.trim(), algorithm, comment.trim().ifEmpty { name.trim() })
                },
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlgorithmOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(title)
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
internal fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImport: (String, String, CharArray?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Paste key (OpenSSH / PEM / PuTTY)") },
                    // Keep the key editable/visible, but classify its input as secret to the IME.
                    keyboardOptions = secretKeyboardOptions(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.height(160.dp),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (if the key has one)") },
                    keyboardOptions = secretKeyboardOptions(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank(),
                onClick = {
                    onImport(
                        name.trim(),
                        text.trim(),
                        passphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                    )
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Export flow: choose an export passphrase, then use the shared authorized prompt queue and hand
 * the private key to the share sheet as a content:// URI (never as an inline text extra, so the
 * key material isn't marshalled to arbitrary chooser targets). Only Ed25519 software keys reach
 * this flow, and the exported file is always freshly passphrase-encrypted.
 */
@Composable
private fun ExportKeyFlow(
    key: KeyEntity,
    onDismiss: () -> Unit,
    onExport: (CharArray, (String?) -> Unit) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? dev.wissam.sshterm.ui.MainActivity
    val handoff = remember(key.id) { ExportHandoff() }
    var submitting by remember(key.id) { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    DisposableEffect(key.id, activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP ||
                event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                if (handoff.invalidate()) {
                    onCancel()
                    onDismiss()
                }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            if (handoff.invalidate()) onCancel()
        }
    }
    // Keep this flow alive while the shared PromptHost renders passphrase/cipher requests.
    if (submitting) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export “${key.name}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Set a passphrase to protect the exported private key. You'll enter it when " +
                        "using the key on another machine.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; mismatch = false },
                    label = { Text("Export passphrase") },
                    keyboardOptions = secretKeyboardOptions(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; mismatch = false },
                    label = { Text("Confirm passphrase") },
                    keyboardOptions = secretKeyboardOptions(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (mismatch) {
                    Text("Passphrases don't match.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotEmpty(),
                onClick = {
                    if (passphrase != confirm) {
                        mismatch = true
                        return@TextButton
                    }
                    submitting = true
                    onExport(passphrase.toCharArray()) { text ->
                        // Main-thread, non-suspending handoff: check live lock/lifecycle state,
                        // not Compose's possibly stale disposal state, before creating any URI.
                        handoff.deliver(
                            isResumed = activity?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                                !activity.isFinishing && !activity.isDestroyed,
                            isUnlocked = activity?.appLock?.locked?.value == false,
                        ) {
                            if (text != null) shareViaFileProvider(context, key.name, text)
                            onDismiss()
                        }
                    }
                },
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Writes the exported key to a private cache file and shares it as a content:// URI with a
 * per-use read grant — the key never rides as an Intent text extra across the Binder.
 */
private fun shareViaFileProvider(context: Context, keyName: String, keyText: String) {
    val file = createKeyExportFile(context.cacheDir, keyName)
    file.writeText(keyText)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-pem-file"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("private key", uri)
    }
    context.startActivity(Intent.createChooser(send, "Export private key"))
}
