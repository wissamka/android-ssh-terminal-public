package dev.wissam.sshterm.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import dev.wissam.sshterm.data.db.ForwardKind
import dev.wissam.sshterm.data.db.ForwardingRuleEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardsScreen(
    profileId: Long,
    viewModel: ForwardsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val rules by remember(profileId) { viewModel.rulesFor(profileId) }
        .collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Port forwarding") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add forward")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No port forwards.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Local (-L) forwards a port on this device to a host reachable from the " +
                        "server. Remote (-R) forwards a server port back to a host reachable " +
                        "from this device. Forwards start with the session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    ForwardCard(
                        rule = rule,
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onDelete = { viewModel.delete(rule) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddForwardDialog(
            onDismiss = { showAdd = false },
            onAdd = { kind, bindHost, bindPort, targetHost, targetPort ->
                val error = viewModel.add(profileId, kind, bindHost, bindPort, targetHost, targetPort)
                if (error == null) showAdd = false
                error
            },
        )
    }
}

@Composable
private fun ForwardCard(
    rule: ForwardingRuleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val arrow = if (rule.kind == ForwardKind.LOCAL) "→" else "←"
                Text(
                    "${rule.kind.name[0]}  ${rule.bindHost}:${rule.bindPort}  $arrow  " +
                        "${rule.targetHost}:${rule.targetPort}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Text(
                    if (rule.kind == ForwardKind.LOCAL) "Local (-L)" else "Remote (-R)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription =
                        "${rule.bindHost}:${rule.bindPort} forward, ${if (rule.enabled) "enabled" else "disabled"}"
                },
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete forward")
            }
        }
    }
}

@Composable
private fun AddForwardDialog(
    onDismiss: () -> Unit,
    onAdd: (ForwardKind, String, String, String, String) -> String?,
) {
    var kind by remember { mutableStateOf(ForwardKind.LOCAL) }
    var bindHost by remember { mutableStateOf("127.0.0.1") }
    var bindPort by remember { mutableStateOf("") }
    var targetHost by remember { mutableStateOf("") }
    var targetPort by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == ForwardKind.LOCAL,
                        onClick = { kind = ForwardKind.LOCAL },
                        label = { Text("Local -L") },
                    )
                    FilterChip(
                        selected = kind == ForwardKind.REMOTE,
                        onClick = { kind = ForwardKind.REMOTE },
                        label = { Text("Remote -R") },
                    )
                }
                Text(
                    if (kind == ForwardKind.LOCAL) {
                        "Listen on this device, tunnel to a host the server can reach."
                    } else {
                        "Listen on the server, tunnel to a host this device can reach."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bindHost,
                        onValueChange = { bindHost = it },
                        label = { Text("Bind host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = bindPort,
                        onValueChange = { bindPort = it.filter(Char::isDigit) },
                        label = { Text("Bind port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = targetHost,
                        onValueChange = { targetHost = it },
                        label = { Text("Target host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = targetPort,
                        onValueChange = { targetPort = it.filter(Char::isDigit) },
                        label = { Text("Target port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onAdd(kind, bindHost, bindPort, targetHost, targetPort)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
