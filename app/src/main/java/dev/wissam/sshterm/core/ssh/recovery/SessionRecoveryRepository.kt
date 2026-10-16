package dev.wissam.sshterm.core.ssh.recovery

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRecoveryRepository internal constructor(storage: RecoveryStorage) {
    @Inject constructor(@ApplicationContext context: Context) : this(atomicStorage(context))

    private val state = RecoveryState(RecoveryJournal(storage))
    val ended = state.ended
    val persistenceError = state.persistenceError
    fun opened(entry: EndedSession) = state.opened(entry)
    fun ended(token: String) = state.ended(token)
    fun dismiss(token: String) = state.dismiss(token)
    fun closed(token: String) = state.dismiss(token)
    fun retryPendingRemovals() = state.retryPendingRemovals()

    companion object {
        private fun atomicStorage(context: Context): RecoveryStorage {
            val file = AtomicFile(File(context.noBackupFilesDir, "session-recovery-v1"))
            return object : RecoveryStorage {
                override fun read(): String = try { file.openRead().bufferedReader().use { it.readText() } }
                    catch (_: java.io.FileNotFoundException) { "" }
                override fun write(value: String) {
                    val stream = file.startWrite()
                    try {
                        stream.write(value.toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (t: Throwable) { file.failWrite(stream); throw t }
                }
            }
        }
    }
}
