package dev.wissam.sshterm.security.keystore

import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.wissam.sshterm.core.keys.AesGcmFileStore
import dev.wissam.sshterm.core.keys.KeyFileAuthorizer
import dev.wissam.sshterm.security.requireDisposableAuditEmulator
import dev.wissam.sshterm.ui.MainActivity
import dev.wissam.sshterm.ui.security.AuthenticationOperation
import dev.wissam.sshterm.ui.security.showAuthPrompt
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before

/** Operator-assisted real biometric tests. Never enrolls, unlocks, injects fingerprints or fakes success. */
abstract class BiometricKeystoreFixture {
    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val context get() = instrumentation.targetContext
    protected val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    protected val files = AesGcmFileStore()
    protected lateinit var directory: File
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val ownedAliases = mutableSetOf<String>()
    private val activeOperations = mutableListOf<AuthenticationOperation>() // Main-thread only.
    protected val approvals = AtomicInteger()
    protected val prompts = AtomicInteger()
    protected val pendingPlatformAttempts = AtomicInteger()
    protected abstract val mode: String

    @Before fun requireExplicitRuntimeMode() {
        requireDisposableAuditEmulator()
        assertEquals("Select the matching run; missing prerequisites are failures, never skipped approvals",
            mode, InstrumentationRegistry.getArguments().getString("keystoreBiometricMode"))
        val secure = checkNotNull(context.getSystemService(KeyguardManager::class.java)).isDeviceSecure
        val strong = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (mode == "enrolled") {
            assertTrue("Parent must configure a PIN on the disposable emulator first", secure)
            assertEquals("Parent must enroll a usable strong fingerprint; do not treat this failure as a pass",
                BiometricManager.BIOMETRIC_SUCCESS, strong)
        } else {
            assertFalse("Run noEnrollment before configuring PIN/fingerprint", secure)
            assertNotEquals(BiometricManager.BIOMETRIC_SUCCESS, strong)
        }
        // Only new files under a test-owned random directory; no DB/preferences/app key files touched.
        directory = File(context.cacheDir, "keystore-runtime-${UUID.randomUUID()}")
        check(directory.mkdir())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        if (mode == "enrolled") {
            // MainActivity automatically prompts on cold open. Complete that real prompt first;
            // replacing it immediately races AndroidX's activity-scoped cancellation callback.
            // No synthetic unlock: the operator must authenticate through the actual app prompt.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(80)
            while (activity.appLock.locked.value && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse("Complete the real Unlock SSH Term prompt before the per-operation test", activity.appLock.locked.value)
        }
    }

    protected fun ownNewAlias(alias: String) { ownedAliases += alias }

    /** The production store has fixed aliases. Reserve them only after proving both absent.
     * Run this suite exclusively in the disposable audit UID, never alongside app use/other tests.
     */
    protected fun reserveFreshWrapperAliases() {
        val aliases = listOf("sshterm-key-file-master", "sshterm-key-file-auth-v2")
        aliases.forEach { assertFalse("Existing alias $it: refusing to reuse or delete it; choose a fresh disposable audit installation", store.containsAlias(it)) }
        ownedAliases.addAll(aliases)
    }

    protected fun newLegacyFile(): Pair<File, ByteArray> {
        reserveFreshWrapperAliases()
        val plaintext = "disposable key-file runtime payload ${UUID.randomUUID()}".toByteArray()
        val file = File(directory, "key-${UUID.randomUUID()}")
        files.encryptToFile(plaintext, file)
        assertArrayEquals(plaintext, files.decryptFromFile(file))
        return file to plaintext
    }

    protected fun realCipherAuthorizer(cancel: Boolean = false) = KeyFileAuthorizer { request ->
        actualPrompt(BiometricPrompt.CryptoObject(request.cipher), "${request.purpose}", cancel)
    }

    /** Only showAuthPrompt's actual platform callback can return true. Cancellation tests explicitly
     * cancel the real returned operation after authenticate() has been initiated, without user input.
     */
    protected suspend fun actualPrompt(
        crypto: BiometricPrompt.CryptoObject? = null,
        purpose: String,
        cancel: Boolean = false,
    ): Boolean = withTimeout(80_000) {
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var operation: AuthenticationOperation? = null // Main-thread only.
            continuation.invokeOnCancellation { main.post { operation?.cancel() } }
            main.post {
                if (!continuation.isActive) return@post
                val sequence = prompts.incrementAndGet()
                val title = if (cancel) "CANCEL TEST — do not touch sensor" else "APPROVE $sequence: $purpose"
                Log.i(TAG, "PROMPT_START sequence=$sequence cancel=$cancel purpose=$purpose")
                operation = showAuthPrompt(
                    activity, title, "Disposable audit emulator only", strongOnly = true,
                    cryptoObject = crypto,
                    canAuthorize = { continuation.isActive && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                ) { allowed ->
                    if (allowed) approvals.incrementAndGet()
                    Log.i(TAG, "PROMPT_RESULT sequence=$sequence allowed=$allowed")
                    if (continuation.isActive) continuation.resume(allowed)
                }
                val started = checkNotNull(operation)
                activeOperations += started
                if (!started.isComplete) pendingPlatformAttempts.incrementAndGet()
                if (cancel) main.postDelayed({ started.cancel() }, 1_000)
                // Start is not proof the dialog is visible; operator must see the APPROVE prompt
                // on screen before supplying the enrolled fingerprint through emulator controls.
            }
        }
    }

    @After fun cleanupOnlyTestOwnedMaterial() {
        try {
            if (::scenario.isInitialized) {
                instrumentation.runOnMainSync { activeOperations.forEach { it.cancel() }; activeOperations.clear() }
                scenario.close()
            }
        } finally {
            try { ownedAliases.forEach { alias -> if (store.containsAlias(alias)) store.deleteEntry(alias) } }
            finally {
                if (::directory.isInitialized) assertTrue("Could not remove test-only ciphertext directory", directory.deleteRecursively())
            }
        }
    }

    protected companion object { const val TAG = "SSHKEY_CRYPTO_RUNTIME" }
}
