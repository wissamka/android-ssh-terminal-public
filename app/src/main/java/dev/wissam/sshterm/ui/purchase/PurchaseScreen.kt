package dev.wissam.sshterm.ui.purchase

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wissam.sshterm.core.billing.BillingAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseScreen(onBack: () -> Unit, viewModel: PurchaseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current.activity()
    LaunchedEffect(viewModel) { viewModel.restore() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Free & Pro") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (viewModel.ownerDevelopmentPro) "Owner development Pro"
                else if (state.entitlement.pro) "Pro is active" else "One-time Pro upgrade",
                style = MaterialTheme.typography.headlineSmall)
            Text(if (viewModel.ownerDevelopmentPro) "Unlimited profiles and sessions for this owner development build. This is not a purchase or a verified Play entitlement."
                else "Pro includes unlimited saved profiles and active sessions, with no subscription.")
            Text(if (viewModel.ownerDevelopmentPro) "Owner development access is enabled. Checkout is disabled."
                else if (viewModel.enforcesFreeLimits)
                "Free allows ${viewModel.freeLimits.profiles} saved profiles and ${viewModel.freeLimits.sessions} active session(s), including local shells. These limits apply only to new creation."
                else "Purchases are unavailable in this build. Profile and session limits are not enforced; current use remains available.")
            Text("Security protections, keys and exports stay free. Existing profiles remain usable and editable, even above the limit. Running sessions are never closed by billing checks.")
            Text(state.message)
            if (state.entitlement.pro && state.entitlement.availability != BillingAvailability.AVAILABLE) {
                Text("Using your saved, verified purchase while Google Play is unavailable.")
            }
            Button(onClick = { activity?.let(viewModel::purchase) },
                enabled = !viewModel.ownerDevelopmentPro && activity != null && state.price != null && !state.busy &&
                    !state.entitlement.pro && !state.entitlement.pending) {
                Text(state.price?.let { "Buy Pro — $it" } ?: "Purchase unavailable")
            }
            OutlinedButton(onClick = viewModel::restore,
                enabled = !state.busy && state.entitlement.availability != BillingAvailability.UNCONFIGURED) {
                Text("Restore purchases / Retry")
            }
            Text("Restore with the Google Play account used to purchase Pro, including after reinstalling. Pending payments do not activate Pro. Billing checks never close your running sessions.")
            Text("Purchases are processed by Google Play. This app adds no cloud account or developer-operated billing server.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
