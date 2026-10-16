package dev.wissam.sshterm.ui.autocomplete

import dev.wissam.sshterm.data.db.CommandHistoryEntity

/**
 * Frequency x recency-decay ranking (the z/autojump/Firefox-awesomebar family of algorithms),
 * with exact-prefix matches sorted strictly ahead of substring matches.
 */
object FrecencyRanker {

    data class Suggestion(
        val command: String,
        val score: Double,
        val fromSeed: Boolean = false,
        /** Non-null when this suggestion is a saved snippet; [command] holds the snippet body. */
        val snippetName: String? = null,
    )

    private const val HALF_LIFE_MS = 7.0 * 24 * 60 * 60 * 1000 // one week
    private const val PROFILE_TIER_BOOST = 2.0     // this host's own history beats the global tier
    private const val PREFIX_MATCH_BOOST = 1000.0  // weighting within a match tier
    private const val CONTAINS_MATCH_BOOST = 10.0

    fun rank(
        input: String,
        history: List<CommandHistoryEntity>,
        seedCommands: List<String>,
        profileId: Long?,
        now: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): List<Suggestion> {
        val query = input.trim()

        val scored = HashMap<String, Suggestion>()

        for (entry in history) {
            val match = matchFactor(entry.command, query) ?: continue
            val ageMs = (now - entry.lastUsedAt).coerceAtLeast(0)
            val decay = Math.pow(0.5, ageMs / HALF_LIFE_MS)
            val tier = if (entry.profileId != null && entry.profileId == profileId) PROFILE_TIER_BOOST else 1.0
            val score = entry.useCount * (0.25 + decay) * tier * match
            val existing = scored[entry.command]
            if (existing == null || existing.score < score) {
                scored[entry.command] = Suggestion(entry.command, score)
            }
        }

        // Seed dictionary: useful before per-user history accumulates; always outranked by
        // anything the user actually ran (real history scores >= 1.25 * match factor).
        for (seed in seedCommands) {
            if (scored.containsKey(seed)) continue
            val match = matchFactor(seed, query) ?: continue
            scored[seed] = Suggestion(seed, 0.05 * match, fromSeed = true)
        }

        return scored.values.sortedWith(
            compareByDescending<Suggestion> { query.isNotEmpty() && it.command.startsWith(query) }
                .thenByDescending { it.score }
                .thenBy { it.command }
        ).take(limit.coerceAtLeast(0))
    }

    /** null = no match; otherwise a multiplier expressing match quality. */
    private fun matchFactor(command: String, query: String): Double? {
        if (query.isEmpty()) return 1.0
        return when {
            command.startsWith(query) && command != query -> PREFIX_MATCH_BOOST
            command.contains(query) && command != query -> CONTAINS_MATCH_BOOST
            else -> null
        }
    }
}
