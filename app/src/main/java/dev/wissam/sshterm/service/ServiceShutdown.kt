package dev.wissam.sshterm.service

/** Platform shutdown must precede any transport cleanup; each step runs even if a prior one fails. */
internal fun shutdownSessionService(
    removeNotification: () -> Unit,
    stopService: () -> Unit,
    endSessions: () -> Unit,
) {
    try {
        removeNotification()
    } finally {
        try { stopService() } finally { endSessions() }
    }
}
