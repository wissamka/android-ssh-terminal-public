package dev.wissam.sshterm.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Own creation through registration even if the UI disappears. Never delete on an uncertain save.
 * create must produce NEW, privately owned material; findSaved must check that material's identity,
 * not its display name. Callers serialize competing registrations/deletions around this operation.
 */
internal suspend fun <T : Any> registerNewKeyMaterial(
    create: () -> T,
    save: suspend (T) -> T,
    findSaved: suspend (T) -> T?,
    deleteUnregistered: (T) -> Unit,
): T {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable + Dispatchers.IO) {
        val material = create()
        try {
            save(material)
        } catch (failure: Exception) {
            val saved = try { findSaved(material) } catch (lookupFailure: Exception) {
                // The insert may have committed. Preserve material if ownership cannot be proven.
                failure.addSuppressed(lookupFailure)
                throw failure
            }
            if (saved != null) saved else {
                try { deleteUnregistered(material) } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
    }
}
