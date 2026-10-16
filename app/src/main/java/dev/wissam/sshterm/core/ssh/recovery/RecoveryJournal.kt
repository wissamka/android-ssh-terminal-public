package dev.wissam.sshterm.core.ssh.recovery

/** Only opaque session tokens and saved-profile IDs cross the persistence boundary. */
data class EndedSession(val token: String, val profileId: Long?)

interface RecoveryStorage {
    fun read(): String
    fun write(value: String)
}

/** All persisted entries represent ended sessions after process recreation; never auto-connect. */
class RecoveryJournal(private val storage: RecoveryStorage) {
    private val entries = decode(storage.read()).toMutableList()
    fun snapshot(): List<EndedSession> = entries.toList()
    fun record(entry: EndedSession) {
        require(entry.token.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(entry.profileId == null || entry.profileId > 0)
        replace(entries.filterNot { it.token == entry.token } + entry)
    }
    fun dismiss(token: String) = replace(entries.filterNot { it.token == token })
    private fun replace(next: List<EndedSession>) {
        storage.write(next.joinToString("\n") { "${it.token}|${it.profileId ?: ""}" })
        entries.clear()
        entries.addAll(next)
    }
    private fun decode(value: String): List<EndedSession> = value.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 2 || !parts[0].matches(Regex("[a-zA-Z0-9-]{1,64}"))) return@mapNotNull null
        val id = if (parts[1].isEmpty()) null else parts[1].toLongOrNull()?.takeIf { it > 0 }
            ?: return@mapNotNull null
        EndedSession(parts[0], id)
    }.distinctBy { it.token }.toList()
}
