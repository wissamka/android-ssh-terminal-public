package dev.wissam.sshterm.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.wissam.sshterm.ui.hosts.KnownHostsScreen
import dev.wissam.sshterm.ui.keys.KeysScreen
import dev.wissam.sshterm.ui.profiles.ForwardsScreen
import dev.wissam.sshterm.ui.profiles.ProfileEditScreen
import dev.wissam.sshterm.ui.profiles.ProfilesScreen
import dev.wissam.sshterm.ui.security.AuthenticationOperation
import dev.wissam.sshterm.ui.security.AppLockManager
import dev.wissam.sshterm.ui.security.showAuthPrompt
import dev.wissam.sshterm.ui.terminal.TerminalScreen
import dev.wissam.sshterm.ui.terminal.TerminalViewModel
import dev.wissam.sshterm.ui.theme.SshTermTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var billing: dev.wissam.sshterm.core.billing.PlayBillingRepository
    @Inject lateinit var appLock: AppLockManager
    @Inject lateinit var settingsRepo: dev.wissam.sshterm.data.settings.SettingsRepository

    private var unlockOperation: AuthenticationOperation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the persisted terminal theme synchronously before any TerminalView/emulator is
        // constructed (the emulator copies the shared color scheme at creation time).
        val initialSettings = kotlinx.coroutines.runBlocking {
            settingsRepo.settings.first()
        }
        dev.wissam.sshterm.core.terminal.TerminalThemes.applyGlobal(initialSettings.themeId)
        appLock.lockTimeoutMillis = initialSettings.autoLockSeconds * 1000L
        // Continue collecting while stopped, so a timeout change is not tied to recreation.
        lifecycleScope.launch {
            settingsRepo.settings.collect { settings ->
                appLock.lockTimeoutMillis = settings.autoLockSeconds * 1000L
            }
        }
        // Protect terminal content and private-key dialogs in every build variant.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        val onboardingSeen = initialSettings.onboardingSeen
        setContent {
            SshTermTheme {
                val locked by appLock.locked.collectAsState()
                var showOnboarding by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(!onboardingSeen)
                }
                // Dispose sensitive content and its dialog windows while locked. Navigation
                // restoration must not preserve interactive content behind a visual overlay.
                when {
                    locked -> LockScreen(onUnlockRequest = { promptUnlock() })
                    showOnboarding -> dev.wissam.sshterm.ui.onboarding.OnboardingScreen(onDone = {
                        showOnboarding = false
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            settingsRepo.setOnboardingSeen()
                        }
                    })
                    else -> {
                        @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
                        val widthClass =
                            androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this@MainActivity)
                                .widthSizeClass
                        AppNav(
                            isExpanded = widthClass ==
                                androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshLockTimeout()
        appLock.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        billing.refresh()
        if (appLock.locked.value) promptUnlock()
    }

    override fun onStop() {
        // Device-credential fallback can stop this activity; the owned prompt survives until
        // completion or destruction. The normal lock timeout still applies on return.
        // If it expires (including Instant), key/export UI is disposed and must be retried
        // after unlocking. Zero-timeout credential roundtrips still need Android validation.
        super.onStop()
        refreshLockTimeout()
        appLock.onAppBackgrounded()
    }

    private fun refreshLockTimeout() {
        // Reconcile persisted state before a lifecycle decision even if the collector has not
        // resumed yet. DataStore is local; this mirrors the initial settings read in onCreate.
        appLock.lockTimeoutMillis = kotlinx.coroutines.runBlocking {
            settingsRepo.settings.first().autoLockSeconds * 1000L
        }
    }

    private fun promptUnlock() {
        if (unlockOperation?.isComplete == false) return
        unlockOperation = showAuthPrompt(this, "Unlock SSH Term") { success ->
            if (success) appLock.unlock()
        }
    }
}

@Composable
private fun LockScreen(onUnlockRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(">_", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.displayMedium)
            Text("Locked", modifier = Modifier.padding(8.dp))
            Button(onClick = onUnlockRequest) { Text("Unlock") }
        }
    }
}

@Composable
private fun AppNav(isExpanded: Boolean) {
    // Single activity-scoped terminal VM so sessions/tab state survive navigation.
    val terminalViewModel: TerminalViewModel = hiltViewModel()
    if (isExpanded) {
        // Tablet / large-screen master-detail: the profile list is a persistent left rail and
        // every other screen (terminal, editors, settings) renders in the right detail pane.
        val detailNav = rememberNavController()
        val initialRoute = androidx.compose.runtime.remember {
            if (terminalViewModel.isConnecting.value || terminalViewModel.prompt.value != null || terminalViewModel.sessions.value.isNotEmpty()) "terminal" else "welcome"
        }
        androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxSize(),
            ) {
                ProfilesPane(detailNav, terminalViewModel)
            }
            androidx.compose.material3.VerticalDivider()
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                NavHost(navController = detailNav, startDestination = initialRoute) {
                    composable("welcome") { WelcomePane() }
                    appDetailRoutes(
                        navController = detailNav,
                        terminalViewModel = terminalViewModel,
                        onExitTerminal = {
                            detailNav.navigate("welcome") {
                                popUpTo("welcome") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    } else {
        val navController = rememberNavController()
        val initialRoute = androidx.compose.runtime.remember {
            if (terminalViewModel.isConnecting.value || terminalViewModel.prompt.value != null || terminalViewModel.sessions.value.isNotEmpty()) "terminal" else "profiles"
        }
        NavHost(navController = navController, startDestination = initialRoute) {
            composable("profiles") { ProfilesPane(navController, terminalViewModel) }
            appDetailRoutes(
                navController = navController,
                terminalViewModel = terminalViewModel,
                onExitTerminal = {
                    navController.navigate("profiles") {
                        popUpTo("profiles") { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/** The profile list, wired to drive [navController]. Reused as a full screen (phone) or left
 *  rail (tablet); connecting/editing routes the given controller, which on a tablet is the
 *  right-pane detail controller. */
@Composable
private fun ProfilesPane(
    navController: androidx.navigation.NavHostController,
    terminalViewModel: TerminalViewModel,
) {
    val sessions by terminalViewModel.sessions.collectAsState()
    val endedSessions by terminalViewModel.recoveryCards.collectAsState()
    ProfilesScreen(
        onConnect = { profileId ->
            terminalViewModel.connect(profileId)
            navController.navigate("terminal")
        },
        onEdit = { id -> navController.navigate("profile_edit?" + "id=${id ?: -1}") },
        onOpenKeys = { navController.navigate("keys") },
        onOpenLocalShell = {
            terminalViewModel.openLocalShell()
            navController.navigate("terminal")
        },
        onOpenKnownHosts = { navController.navigate("known_hosts") },
        onOpenSnippets = { navController.navigate("snippets") },
        onOpenIdentities = { navController.navigate("identities") },
        onOpenSettings = { navController.navigate("settings") },
        onQuickConnect = { connString, keyId ->
            val target = terminalViewModel.parseQuickConnect(connString)
            if (target != null) {
                terminalViewModel.quickConnect(target, keyId)
                navController.navigate("terminal")
            }
        },
        activeSessionCount = sessions.size,
        endedSessions = endedSessions,
        onDismissRecovery = terminalViewModel::dismissRecovery,
        onBackToTerminal = { navController.navigate("terminal") },
    )
}

/** Empty-state shown in the tablet detail pane when nothing is selected. */
@Composable
private fun WelcomePane() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ">_",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Select a profile to connect",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** All non-list destinations, shared between the phone (single-pane) and tablet (detail-pane)
 *  nav graphs. [onExitTerminal] is where the terminal returns when its last session ends. */
private fun androidx.navigation.NavGraphBuilder.appDetailRoutes(
    navController: androidx.navigation.NavHostController,
    terminalViewModel: TerminalViewModel,
    onExitTerminal: () -> Unit,
) {
    composable(
        route = "profile_edit?id={id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
    ) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")?.takeIf { it >= 0 }
        ProfileEditScreen(
            profileId = id,
            onDone = { navController.popBackStack() },
            onEditForwards = { pid -> navController.navigate("forwards/$pid") },
        )
    }
    composable(
        route = "forwards/{id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType }),
    ) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id") ?: return@composable
        ForwardsScreen(profileId = id, onBack = { navController.popBackStack() })
    }
    composable("keys") {
        KeysScreen(onBack = { navController.popBackStack() })
    }
    composable("known_hosts") {
        KnownHostsScreen(onBack = { navController.popBackStack() })
    }
    composable("snippets") {
        dev.wissam.sshterm.ui.snippets.SnippetsScreen(onBack = { navController.popBackStack() })
    }
    composable("settings") {
        dev.wissam.sshterm.ui.settings.SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenLicenses = { navController.navigate("licenses") },
            onOpenPurchase = { navController.navigate("purchase") },
        )
    }
    composable("purchase") {
        dev.wissam.sshterm.ui.purchase.PurchaseScreen(onBack = { navController.popBackStack() })
    }
    composable("licenses") {
        dev.wissam.sshterm.ui.settings.LicensesScreen(onBack = { navController.popBackStack() })
    }
    composable("identities") {
        dev.wissam.sshterm.ui.identities.IdentitiesScreen(onBack = { navController.popBackStack() })
    }
    composable("terminal") {
        TerminalScreen(
            viewModel = terminalViewModel,
            onNavigateToProfiles = onExitTerminal,
            onReconnect = { profileId -> terminalViewModel.connect(profileId) },
        )
    }
}
