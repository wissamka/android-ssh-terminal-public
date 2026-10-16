package dev.wissam.sshterm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wissam.sshterm.BuildConfig
import dev.wissam.sshterm.core.terminal.TerminalThemes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenPurchase: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Terminal theme") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TerminalThemes.all.forEach { theme ->
                        FilterChip(
                            selected = settings.themeId == theme.id,
                            onClick = { viewModel.setTheme(theme.id) },
                            label = { Text(theme.label) },
                        )
                    }
                }
            }

            HorizontalDivider()

            Section("Terminal font size") {
                Text(
                    "${settings.fontSizeSp} sp",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.fontSizeSp.toFloat(),
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                    valueRange = 8f..28f,
                    steps = 19,
                )
                Text(
                    "You can also pinch-to-zoom in the terminal.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Section("Auto-lock") {
                Text(
                    if (settings.autoLockSeconds == 0) "Immediately"
                    else "After ${settings.autoLockSeconds}s in background",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0, 30, 60, 300, 900).forEach { s ->
                        FilterChip(
                            selected = settings.autoLockSeconds == s,
                            onClick = { viewModel.setAutoLockSeconds(s) },
                            label = { Text(if (s == 0) "Instant" else "${s}s") },
                        )
                    }
                }
            }

            HorizontalDivider()

            Section("Command history") {
                Text("Saved-profile commands stay with that profile. Turning recording off keeps existing history.")
                FilterChip(
                    selected = settings.recordHistory,
                    onClick = { viewModel.setRecordHistory(!settings.recordHistory) },
                    label = { Text("Record command history") },
                )
                FilterChip(
                    selected = settings.globalHistoryFallback,
                    onClick = { viewModel.setGlobalHistoryFallback(!settings.globalHistoryFallback) },
                    label = { Text("Allow global history fallback") },
                )
                Text("Global fallback shares existing global commands across sessions and allows recording local and quick-connect commands. Saved-profile commands are never copied globally.")
                TextButton(onClick = viewModel::cleanLegacyHistory) { Text("Clean legacy global duplicates") }
                Text("Cleanup removes only global entries matching a profile command, timestamp and use count. Ambiguous entries are kept.")
                TextButton(onClick = viewModel::clearGlobalHistory) { Text("Clear global history") }
                TextButton(onClick = viewModel::clearHistory) { Text("Clear all command history") }
                val message by viewModel.historyMessage.collectAsState()
                message?.let { Text(it) }
            }

            HorizontalDivider()
            TextButton(onClick = onOpenPurchase) { Text("Free & Pro / Restore purchases") }

            Section("About") {
                Text("SSH Term ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "A local-first SSH client. No account, no telemetry.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenLicenses, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Open-source licenses")
                }
                val crash = remember { viewModel.lastCrash() }
                if (crash != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TextButton(
                        onClick = {
                            // Explicit user action: share the local crash log. Nothing is sent
                            // automatically.
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "SSH Term crash log")
                                putExtra(android.content.Intent.EXTRA_TEXT, crash)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share crash log"))
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("Share last crash log", color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = { viewModel.clearCrash() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("Clear crash log", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
