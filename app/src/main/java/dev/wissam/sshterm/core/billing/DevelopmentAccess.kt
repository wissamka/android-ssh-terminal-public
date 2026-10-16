package dev.wissam.sshterm.core.billing

/** Owner access is a development capability, never a verified purchase or cached receipt. */
object DevelopmentAccess {
    fun ownerPro(debugBuild: Boolean, requested: Boolean): Boolean = debugBuild && requested
}
