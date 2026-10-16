package dev.wissam.sshterm.security

import android.app.KeyguardManager
import android.os.Build
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot as isComposeRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogWindow
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import dev.wissam.sshterm.SshTermApplication
import dev.wissam.sshterm.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule

/** No replacement Application, content tree, policy, authenticator, or DI graph. */
abstract class SecurityRuntimeFixture {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected lateinit var activity: MainActivity
    private var originalTimeout: Int? = null

    @Before fun launchProductionActivity() {
        requireDisposableAuditEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Use the production Application/AndroidJUnitRunner, not HiltTestApplication",
            context.applicationContext is SshTermApplication)
        assertFalse("This suite requires an emulator with no PIN/password or enrolled biometrics",
            checkNotNull(context.getSystemService(KeyguardManager::class.java)).isDeviceSecure)
        assertTrue("No usable authenticator is allowed in this deterministic suite",
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) != BiometricManager.BIOMETRIC_SUCCESS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        originalTimeout = runBlocking { activity.settingsRepo.settings.first().autoLockSeconds }
        compose.onNodeWithText("Locked").assertIsDisplayed()
    }

    @After fun finishProductionActivity() {
        if (!::scenario.isInitialized) return
        try {
            originalTimeout?.let { timeout ->
                runBlocking { activity.settingsRepo.setAutoLockSeconds(timeout) }
            }
            scenario.onActivity {
                // Restore the singleton's lock for the next test through its real lifecycle API.
                it.appLock.lockTimeoutMillis = 0
                it.appLock.onAppBackgrounded()
                it.appLock.onAppForegrounded()
            }
        } finally {
            scenario.close()
        }
    }

    /** Test setup only; never evidence of successful biometric/device-credential authorization. */
    protected fun enterAppForUiTest() {
        val beforeUnlock = activitySnapshot()
        val clockBeforeUnlock = compose.mainClock.currentTime
        scenario.onActivity { it.appLock.unlock() }
        var stage = "waiting for onboarding or profile screen after unlock"
        try {
            // Compose idleness alone does not await asynchronous Flow/DataStore emissions.
            // Wait for a visible destination, rather than taking a one-time onboarding snapshot.
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Get started")).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onNodeWithContentDescription("SSH keys").isDisplayed()
            }
            if (compose.onAllNodes(androidx.compose.ui.test.hasText("Get started")).fetchSemanticsNodes().isNotEmpty()) {
                stage = "clicking onboarding Get started"
                compose.onNodeWithText("Get started").performScrollTo().performClick()
            }
            stage = "waiting for profile screen after onboarding"
            compose.waitUntil(10_000) {
                compose.onNodeWithContentDescription("SSH keys").isDisplayed()
            }
            // Preserve the original assertion: a hidden/missing action is never accepted.
            compose.onNodeWithContentDescription("SSH keys").assertIsDisplayed()
        } catch (failure: Throwable) {
            fun semantics(unmerged: Boolean) = runCatching {
                compose.onAllNodes(isComposeRoot(), useUnmergedTree = unmerged)
                    .printToString(maxDepth = Int.MAX_VALUE)
            }.getOrElse { "Semantics dump unavailable: $it" }
            val afterFailure = activitySnapshot(includeViews = true)
            throw AssertionError(
                "UI setup failed while $stage.\n" +
                    "Clock: autoAdvance=${compose.mainClock.autoAdvance}, " +
                    "before=$clockBeforeUnlock, after=${compose.mainClock.currentTime}\n" +
                    "Before unlock: $beforeUnlock\nAfter failure: $afterFailure\n" +
                    "Merged semantics (full depth):\n${semantics(false)}\n" +
                    "Unmerged semantics (full depth):\n${semantics(true)}",
                failure,
            )
        }
    }

    /** Read-only diagnostics from the actual scenario activity; never creates another VM/content. */
    private fun activitySnapshot(includeViews: Boolean = false): String = runCatching {
        var snapshot = "ActivityScenario callback did not run"
        scenario.onActivity { live ->
            val decor = live.window.decorView
            val bounds = Rect()
            val globallyVisible = decor.getGlobalVisibleRect(bounds)
            snapshot = buildString {
                append("activity=${System.identityHashCode(live)}, ")
                append("storedActivity=${System.identityHashCode(activity)}, same=${live === activity}, ")
                append("lifecycle=${live.lifecycle.currentState}, finishing=${live.isFinishing}, ")
                append("destroyed=${live.isDestroyed}, focus=${live.hasWindowFocus()}, ")
                append("appLock=${System.identityHashCode(live.appLock)}, ")
                append("storedAppLock=${System.identityHashCode(activity.appLock)}, ")
                append("locked=${live.appLock.locked.value}, storedLocked=${activity.appLock.locked.value}, ")
                append("application=${live.application.javaClass.name}, ")
                append("windowFlags=0x${Integer.toHexString(live.window.attributes.flags)}, ")
                append("windowVisibility=${decor.windowVisibility}, attached=${decor.isAttachedToWindow}, ")
                append("shown=${decor.isShown}, laidOut=${decor.isLaidOut}, ")
                append("globalVisible=$globallyVisible, bounds=$bounds, ")
                append("density=${live.resources.displayMetrics.density}, ")
                append("screenDp=${live.resources.configuration.screenWidthDp}x")
                append(live.resources.configuration.screenHeightDp)
                if (includeViews) {
                    append("\nActual decor view hierarchy (visibility: 0=visible, 4=invisible, 8=gone):\n")
                    var remaining = 200
                    fun dump(view: View, depth: Int) {
                        if (remaining-- <= 0) return
                        append("  ".repeat(depth))
                        append("${view.javaClass.name} id=${view.id} ")
                        append("size=${view.width}x${view.height} xy=${view.x},${view.y} ")
                        append("visibility=${view.visibility} alpha=${view.alpha} ")
                        append("shown=${view.isShown} attached=${view.isAttachedToWindow}\n")
                        if (view is ViewGroup) {
                            for (index in 0 until view.childCount) dump(view.getChildAt(index), depth + 1)
                        }
                    }
                    dump(decor, 0)
                    if (remaining <= 0) append("[view dump capped at 200 nodes]\n")
                }
            }
        }
        snapshot
    }.getOrElse { "Activity snapshot unavailable: $it" }

    protected fun assertActivitySecure() {
        scenario.onActivity {
            assertTrue("FLAG_SECURE must protect the actual activity window in every variant",
                it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    /** Check the real Dialog DecorView's window flags, not a duplicate Compose property. */
    protected fun assertDialogSecure(): View {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        lateinit var decor: View
        onView(isRoot()).inRoot(isDialogWindow()).check { view, error ->
            if (error != null) throw error
            decor = checkNotNull(view).rootView
            val params = decor.layoutParams as WindowManager.LayoutParams
            assertTrue("Sensitive dialog window must inherit FLAG_SECURE",
                params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        return decor
    }

    protected fun backgroundAndReturnWithInstantLock() {
        runBlocking { activity.settingsRepo.setAutoLockSeconds(0) }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Locked").assertIsDisplayed()
        assertActivitySecure()
    }
}

internal fun requireDisposableAuditEmulator() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val args = InstrumentationRegistry.getArguments()
    val target = instrumentation.targetContext.packageName
    check(args.getString("securityAuditDisposable") == "true") {
        "Explicit -e securityAuditDisposable true is required; never run against the owner's app"
    }
    check(args.getString("securityAuditTargetPackage") == target && target.endsWith(".audit")) {
        "Use a separate applicationId ending .audit and pass its exact securityAuditTargetPackage"
    }
    check(Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm") ||
        Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone")) {
        "Security runtime fixtures are restricted to the parent's disposable emulator"
    }
}
