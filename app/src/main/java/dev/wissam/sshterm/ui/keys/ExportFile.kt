package dev.wissam.sshterm.ui.keys

import java.io.File

/** Unique URI per export: a previously granted URI must never reveal a later key export. */
internal fun createKeyExportFile(cacheDirectory: File, keyName: String): File {
    val directory = File(cacheDirectory, "exports")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create export directory" }
    val label = keyName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "ssh_key" }
    return File.createTempFile("key_${label}_", "_id_ed25519", directory)
}
