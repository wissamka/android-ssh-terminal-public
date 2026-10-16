package dev.wissam.sshterm.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import dev.wissam.sshterm.data.db.ProfileEntity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onConnect: (Long) -> Unit,
    onEdit: (Long?) -> Unit,
    onOpenKeys: () -> Unit,
    onOpenLocalShell: () -> Unit,
    onOpenKnownHosts: () -> Unit,
    onOpenSnippets: () -> Unit,
    onOpenIdentities: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuickConnect: (connectionString: String, keyId: Long?) -> Unit,
    activeSessionCount: Int,
    onBackToTerminal: () -> Unit,
    endedSessions: List<dev.wissam.sshterm.core.ssh.recovery.EndedSession> = emptyList(),
    onDismissRecovery: (String) -> Unit = {},
) {
    val profiles by viewModel.profiles.collectAsState()
    val keys by viewModel.keys.collectAsState()
    var showQuickConnect by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Term", fontFamily = FontFamily.Monospace) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    if (activeSessionCount > 0) {
                        IconButton(onClick = onBackToTerminal) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = "Back to sessions ($activeSessionCount active)",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { showQuickConnect = true }) {
                        Icon(Icons.Default.Bolt, contentDescription = "Quick connect")
                    }
                    IconButton(onClick = onOpenKeys) {
                        Icon(Icons.Default.Key, contentDescription = "SSH keys")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Snippets") },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                            onClick = { menuOpen = false; onOpenSnippets() },
                        )
                        DropdownMenuItem(
                            text = { Text("Identities") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            onClick = { menuOpen = false; onOpenIdentities() },
                        )
                        DropdownMenuItem(
                            text = { Text("Known hosts") },
                            leadingIcon = { Icon(Icons.Default.VerifiedUser, null) },
                            onClick = { menuOpen = false; onOpenKnownHosts() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New profile")
            }
        },
    ) { padding ->
        if (profiles.isEmpty() && endedSessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No connection profiles yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(8.dp))
                Text(
                    "Tap + to add a VPS, or open a local fallback shell below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(8.dp))
                androidx.compose.material3.TextButton(onClick = onOpenLocalShell) {
                    Text("Open local shell", fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(endedSessions, key = { "ended-${it.token}" }) { ended ->
                    val profile = profiles.firstOrNull { it.id == ended.profileId }
                    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Session ended", style = MaterialTheme.typography.titleMedium)
                            Text(profile?.name ?: if (ended.profileId == null) "Start a new connection or local shell." else "Saved profile is unavailable.")
                            Text("The previous connection cannot be restored. Reconnecting starts a new shell.")
                            if (profile != null) {
                                androidx.compose.material3.TextButton(onClick = { onConnect(profile.id) }) {
                                    Text("Reconnect")
                                }
                            } else if (ended.profileId == null) {
                                androidx.compose.material3.TextButton(onClick = { showQuickConnect = true }) {
                                    Text("New connection")
                                }
                            }
                            androidx.compose.material3.TextButton(onClick = { onDismissRecovery(ended.token) }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onConnect = { onConnect(profile.id) },
                        onEdit = { onEdit(profile.id) },
                        onDelete = { viewModel.delete(profile) },
                    )
                }
                item {
                    androidx.compose.material3.TextButton(
                        onClick = onOpenLocalShell,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open local shell (fallback)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showQuickConnect) {
        QuickConnectDialog(
            keys = keys.map { it.id to it.name },
            onDismiss = { showQuickConnect = false },
            onConnect = { connString, keyId ->
                showQuickConnect = false
                onQuickConnect(connString, keyId)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickConnectDialog(
    keys: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onConnect: (String, Long?) -> Unit,
) {
    var connString by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Basic shape check: user@host, non-empty on both sides.
    val at = connString.indexOf('@')
    val valid = at > 0 && at < connString.trim().length - 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = connString,
                    onValueChange = { connString = it },
                    label = { Text("user@host or user@host:port") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
                val selectedName = keys.firstOrNull { it.first == keyId }?.second ?: "Password (asked)"
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Authentication") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Password (asked)") },
                            onClick = { keyId = null; expanded = false },
                        )
                        keys.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { keyId = id; expanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConnect(connString.trim(), keyId) }) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileCard(
    profile: ProfileEntity,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onConnect,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${profile.username}@${profile.host}:${profile.port}" +
                        if (profile.authType == AuthType.PUBLIC_KEY) "  ⚿" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile.lastConnectedAt?.let {
                    Text(
                        "last: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.width(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.width(18.dp))
            }
        }
    }
}
