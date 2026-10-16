package dev.wissam.sshterm.service

/** Main-thread ownership of one foreground-service lifetime. Retire before requesting stop. */
internal class ServiceGeneration {
    private var next = 0L
    var current: Long? = null
        private set

    fun acquire(): Long = current ?: (++next).also { current = it }
    fun retire(generation: Long) {
        if (current == generation) current = null
    }
    fun retireCurrent() { current = null }
    fun isCurrent(generation: Long): Boolean = current == generation
}
