package dev.wissam.sshterm.ui.identities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wissam.sshterm.data.db.AuthType
import dev.wissam.sshterm.data.db.IdentityEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    viewModel: IdentitiesViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val identities by viewModel.identities.collectAsState()
    val keys by viewModel.keys.collectAsState()
    var editing by remember { mutableStateOf<IdentityEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Default.Add, contentDescription = "New identity")
            }
        },
    ) { padding ->
        if (identities.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No identities yet.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "An identity bundles a username and auth (key or password) so multiple " +
                        "profiles for the same account can share it instead of repeating the setup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(identities, key = { it.id }) { identity ->
                    val keyName = keys.firstOrNull { it.id == identity.keyId }?.name
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(identity.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${identity.username} · " +
                                        if (identity.authType != AuthType.PUBLIC_KEY) identity.authType.name.lowercase().replace('_', ' ')
                                        else "key: ${keyName ?: "(missing)"}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { editing = identity; showEditor = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.delete(identity) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        IdentityEditorDialog(
            existing = editing,
            keys = keys.map { it.id to it.name },
            onDismiss = { showEditor = false },
            onSave = { viewModel.save(it); showEditor = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityEditorDialog(
    existing: IdentityEntity?,
    keys: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onSave: (IdentityEntity) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var authType by remember { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var keyId by remember { mutableStateOf(existing?.keyId) }
    var expanded by remember { mutableStateOf(false) }

    val valid = name.isNotBlank() && username.isNotBlank() &&
        (authType != AuthType.PUBLIC_KEY || keyId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New identity" else "Edit identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authType == AuthType.PASSWORD,
                        onClick = { authType = AuthType.PASSWORD },
                        label = { Text("Password") },
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
                    val selectedName = keys.firstOrNull { it.first == keyId }?.second ?: "Select key…"
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Key") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded, onDismissRequest = { expanded = false },
                        ) {
                            keys.forEach { (id, n) ->
                                DropdownMenuItem(text = { Text(n) }, onClick = { keyId = id; expanded = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        (existing ?: IdentityEntity(name = "", username = "", authType = AuthType.PASSWORD)).copy(
                            name = name.trim(),
                            username = username.trim(),
                            authType = authType,
                            keyId = if (authType == AuthType.PUBLIC_KEY) keyId else null,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
