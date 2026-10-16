package dev.wissam.sshterm.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Point(val icon: ImageVector, val title: String, val body: String)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val points = listOf(
        Point(
            Icons.Default.Terminal, "A real terminal in your pocket",
            "VT100/xterm terminal support for interactive server tools. Session tabs use a foreground " +
                "notification; Android may still end background connections.",
        ),
        Point(
            Icons.Default.VpnKey, "Two kinds of keys",
            "Keystore keys are non-exportable through the app; their inspected protection level is shown. " +
                "Software keys are encrypted, with an optional biometric-bound storage upgrade.",
        ),
        Point(
            Icons.Default.Lock, "Local-first & private",
            "Profiles and keys stay local unless you export them. No developer account or telemetry. " +
                "SSH connects to your servers; configured Pro purchases use Google Play.",
        ),
        Point(
            Icons.Default.Bolt, "Built for one-handed use",
            "An extra-keys row with sticky Ctrl/Alt, and ▲/▼ autocomplete ranked by how often and " +
                "recently you run each command.",
        ),
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.size(16.dp))
            Text(
                ">_ SSH Term",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            points.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        p.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            p.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Get started")
            }
        }
    }
}
