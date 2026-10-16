package dev.wissam.sshterm.ui.keys

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExportFileTest {
    @Test fun namesCannotEscapeAndExistingUriIsNeverOverwritten() {
        val directory = Files.createTempDirectory("sshterm-export-test").toFile()
        try {
            val first = createKeyExportFile(directory, "../../other/key").apply { writeText("first encrypted export") }
            val second = createKeyExportFile(directory, "../../other/key").apply { writeText("second encrypted export") }
            assertEquals(directory.resolve("exports").canonicalFile, first.parentFile.canonicalFile)
            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertEquals("first encrypted export", first.readText())
            assertTrue(createKeyExportFile(directory, "").isFile)
            assertTrue(createKeyExportFile(directory, "x".repeat(10000)).name.length < 100)
        } finally { directory.deleteRecursively() }
    }
}
