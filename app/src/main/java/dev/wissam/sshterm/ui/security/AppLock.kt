package dev.wissam.sshterm.ui.security

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure lifecycle policy; availability of an authenticator never disables the lock. */
internal class AppLockPolicy {
    var locked: Boolean = true
        private set
    var timeoutMillis: Long = 60_000L
        set(value) { field = value.coerceAtLeast(0L) }
    private var backgroundedAt: Long? = null

    fun background(now: Long) { backgroundedAt = now }

    fun foreground(now: Long) {
        val at = backgroundedAt ?: return
        if (now - at >= timeoutMillis) locked = true
        backgroundedAt = null
    }

    fun unlock() {
        locked = false
        backgroundedAt = null
    }
}

/** A single owned authentication attempt. Disposal denies before cancelling the platform UI. */
class AuthenticationOperation internal constructor(
    private val cancelPlatform: () -> Unit,
    private val onResult: (Boolean) -> Unit,
    private val ownerResumed: () -> Boolean = { true },
    private val canAuthorize: () -> Boolean = { true },
) {
    private var pendingSuccess = false
    private val completed = AtomicBoolean(false)
    val isComplete: Boolean get() = completed.get()

    internal fun complete(success: Boolean) {
        if (completed.get()) return
        if (success && !ownerResumed()) {
            pendingSuccess = true
            return
        }
        if (completed.compareAndSet(false, true)) onResult(success && canAuthorize())
    }

    internal fun onOwnerResumed() {
        if (pendingSuccess) complete(true)
    }

    fun cancel() {
        if (!completed.compareAndSet(false, true)) return
        // Invalidate first: cancelAuthentication can synchronously invoke a platform callback.
        try {
            cancelPlatform()
        } catch (_: RuntimeException) {
            // Cancellation failure must not turn disposal into authorization.
        } finally {
            onResult(false)
        }
    }
}

/** Availability permits showing a prompt, never authorizes the protected operation. */
internal fun requestAuthentication(
    canAuthenticate: () -> Boolean,
    authenticate: ((Boolean) -> Unit) -> Unit,
    onResult: (Boolean) -> Unit,
    cancelAuthentication: () -> Unit = {},
    ownerResumed: () -> Boolean = { true },
    canAuthorize: () -> Boolean = { true },
): AuthenticationOperation {
    val operation = AuthenticationOperation(cancelAuthentication, onResult, ownerResumed, canAuthorize)
    try {
        if (canAuthenticate()) authenticate(operation::complete) else operation.complete(false)
    } catch (_: RuntimeException) {
        operation.cancel()
    }
    return operation
}

/**
 * Locked on cold open and after the configured background timeout. This is a UI gate, not a
 * per-operation cryptographic gate: software-key wrapping is not bound to user authentication.
 * Weak biometrics accepted here do not satisfy a hardware key's strong-auth requirement.
 */
@Singleton
class AppLockManager @Inject constructor() {
    private val policy = AppLockPolicy()
    private val _locked = MutableStateFlow(policy.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    var lockTimeoutMillis: Long
        get() = policy.timeoutMillis
        set(value) { policy.timeoutMillis = value }

    fun onAppBackgrounded() { policy.background(SystemClock.elapsedRealtime()) }

    fun onAppForegrounded() {
        policy.foreground(SystemClock.elapsedRealtime())
        _locked.value = policy.locked
    }

    fun unlock() {
        policy.unlock()
        _locked.value = policy.locked
    }
}

// BiometricPrompt callbacks are activity-scoped in AndroidX. Cancel an older owner BEFORE
// creating a replacement, so disposing the older UI cannot cancel the replacement's prompt.
private val activityAuthentication = WeakHashMap<FragmentActivity, AuthenticationOperation>()

/** The caller owns the returned attempt and must cancel it when its UI is disposed. */
fun showAuthPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String? = null,
    canAuthorize: () -> Boolean = { true },
    strongOnly: Boolean = false,
    cryptoObject: BiometricPrompt.CryptoObject? = null,
    onResult: (Boolean) -> Unit,
): AuthenticationOperation {
    val authenticators = if (strongOnly || cryptoObject != null) BIOMETRIC_STRONG else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    activityAuthentication.remove(activity)?.cancel()
    var ownedOperation: AuthenticationOperation? = null
    var prompt: BiometricPrompt? = null
    var observer: LifecycleEventObserver? = null
    fun ownerIsActive() = !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    val operation = requestAuthentication(
        canAuthenticate = {
            ownerIsActive() &&
                BiometricManager.from(activity).canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS
        },
        authenticate = { finish ->
            val sheet = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                        finish(cryptoObject == null ||
                            (cryptoObject.signature != null && result.cryptoObject?.signature === cryptoObject.signature) ||
                            (cryptoObject.cipher != null && result.cryptoObject?.cipher === cryptoObject.cipher))

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                        finish(false)
                },
            )
            prompt = sheet
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setAllowedAuthenticators(authenticators)
                .apply { if (authenticators == BIOMETRIC_STRONG) setNegativeButtonText("Cancel") }
                .build()
            if (cryptoObject == null) sheet.authenticate(info) else sheet.authenticate(info, cryptoObject)
        },
        onResult = { success ->
            observer?.let { activity.lifecycle.removeObserver(it) }
            if (activityAuthentication[activity] === ownedOperation) activityAuthentication.remove(activity)
            onResult(success)
        },
        cancelAuthentication = { prompt?.cancelAuthentication() },
        // Credential activity results may precede onResume. Never authorize in the background
        // or before MainActivity has applied the background timeout in onStart.
        ownerResumed = { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        canAuthorize = { ownerIsActive() && canAuthorize() },
    )
    ownedOperation = operation
    if (!operation.isComplete) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> operation.onOwnerResumed()
                Lifecycle.Event.ON_DESTROY -> operation.cancel()
                // AndroidX's credential fallback intentionally stops the caller. Stop alone
                // cannot distinguish that roundtrip from Home; ownership and resume gate it.
                else -> Unit
            }
        }
        observer = lifecycleObserver
        activityAuthentication[activity] = operation
        activity.lifecycle.addObserver(lifecycleObserver)
        if (activity.isFinishing || activity.isDestroyed) operation.cancel()
    }
    return operation
}
