package dev.wissam.sshterm.ui.autocomplete

import dev.wissam.sshterm.data.db.CommandHistoryEntity
import org.junit.Assert.*
import org.junit.Test

class FrecencyRankerTest {
    @Test fun prefixAlwaysBeatsVeryFrequentSubstringIncludingSeedPrefixes() {
        val history = listOf(
            CommandHistoryEntity(command = "sudo git status", useCount = Int.MAX_VALUE, lastUsedAt = 1000),
            CommandHistoryEntity(command = "git diff", useCount = 1, lastUsedAt = 0),
        )
        val result = FrecencyRanker.rank("git", history, listOf("git status"), null, now = 1000)
        assertEquals(listOf("git diff", "git status", "sudo git status"), result.map { it.command })
    }
    @Test fun frecencyStillOrdersWithinMatchTier() {
        val history = listOf(
            CommandHistoryEntity(command = "git diff", useCount = 1, lastUsedAt = 1000),
            CommandHistoryEntity(command = "git status", useCount = 20, lastUsedAt = 1000),
        )
        assertEquals("git status", FrecencyRanker.rank("git", history, emptyList(), null, now = 1000).first().command)
        assertEquals("git status", FrecencyRanker.rank("", history, emptyList(), null, now = 1000).first().command)
    }
    @Test fun excludesExactCommandAndUnrelatedMatchesAndDeduplicatesSeeds() {
        val history = listOf(CommandHistoryEntity(command = "git diff", lastUsedAt = 1000))
        assertEquals(listOf("git diff"), FrecencyRanker.rank("git", history,
            listOf("git", "git diff", "ls"), null, now = 1000).map { it.command })
    }
}
