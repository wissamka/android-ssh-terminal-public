package dev.wissam.sshterm.core.keys

/** Android's legacy API uses -1; setUserAuthenticationParameters uses 0 for every use.
 * Positive time windows and unknown negative values must never be treated as per-operation.
 */
internal object KeyAuthenticationPolicy {
    fun isPerOperation(required: Boolean, durationSeconds: Int): Boolean =
        required && (durationSeconds == -1 || durationSeconds == 0)
}
