package dev.wissam.sshterm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Attribution(val name: String, val license: String, val note: String)

private val attributions = listOf(
    Attribution("Google Play Billing", "Google Android SDK terms",
        "Optional Pro purchase/restore integration. Distributed under Google’s applicable SDK terms; not represented as Apache-licensed app code."),
    Attribution(
        "Termux terminal-emulator & terminal-view",
        "Apache License 2.0",
        "Vendored from github.com/termux/termux-app (these two modules are Apache-2.0, an explicit " +
            "exception to the rest of that project). The native PTY layer was removed and " +
            "TerminalSession refactored; see each module's NOTICE.md.",
    ),
    Attribution(
        "Apache MINA SSHD",
        "Apache License 2.0",
        "Optional hybrid SSH backend. Copyright 2008–2026 The Apache Software Foundation. " +
            "This product includes software developed at The Apache Software Foundation (https://www.apache.org/).",
    ),
    Attribution(
        "SSHJ",
        "Apache License 2.0",
        "SSH protocol implementation — github.com/hierynomus/sshj.",
    ),
    Attribution(
        "Bouncy Castle",
        "MIT-style (Bouncy Castle License)",
        "Cryptographic provider — bouncycastle.org.",
    ),
    Attribution(
        "JetBrains Mono (Nerd Fonts patched)",
        "SIL Open Font License 1.1",
        "Bundled monospace terminal font with box-drawing/powerline glyphs.",
    ),
    Attribution(
        "AndroidX / Jetpack Compose / Material 3",
        "Apache License 2.0",
        "UI toolkit, Room, DataStore, Hilt, Navigation, Biometric.",
    ),
    Attribution(
        "SLF4J",
        "MIT License",
        "Logging facade used by sshj.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "SSH Term is licensed under Apache-2.0 and includes the following components:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            attributions.forEach { a ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            a.license,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            a.note,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
