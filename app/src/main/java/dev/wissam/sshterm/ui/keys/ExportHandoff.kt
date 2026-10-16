package dev.wissam.sshterm.ui.keys

/** One export attempt. Pause/disposal invalidates it permanently, even after a later unlock.
 * Call on the UI thread: checking authorization and handing off must not suspend.
 */
internal class ExportHandoff {
    private var active = true
    fun invalidate(): Boolean {
        val wasActive = active
        active = false
        return wasActive
    }

    fun deliver(isResumed: Boolean, isUnlocked: Boolean, share: () -> Unit): Boolean {
        if (!active || !isResumed || !isUnlocked) {
            active = false
            return false
        }
        active = false // One-shot, including reentrant or duplicate callbacks.
        share()
        return true
    }
}
