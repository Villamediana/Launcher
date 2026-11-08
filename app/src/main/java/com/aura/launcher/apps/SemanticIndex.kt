package com.aura.launcher.apps

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Scored(val entry: AppEntry, val score: Float, val reason: MatchKind)

enum class MatchKind { EXACT, PREFIX, WORD, FUZZY, SEMANTIC }

/**
 * Ranking for the drawer's search field.
 *
 * Three signals, in descending authority:
 *
 *  1. **lexical** — exact, prefix, word-start and initials on the app's name,
 *  2. **fuzzy** — character-trigram cosine, so typos and partial words land,
 *  3. **category** — Android's own `ApplicationInfo.category`.
 *
 * Usage counts then break ties toward what this person actually opens.
 * Matching stays a pure string operation so the field can respond on every
 * keystroke.
 */
class SemanticIndex(
    private val apps: List<AppEntry>,
    private val usage: Map<String, Int> = emptyMap(),
) {

    private val trigrams: Map<String, Set<String>> =
        apps.associate { it.key to trigramsOf(it.normalized) }

    /** Every semantic word known for an app: its category's. */
    private val semanticWords: Map<String, Set<String>> = apps.associate { app ->
        app.key to CategoryWords.of(app.category).toSet()
    }

    private val maxUsage: Int = usage.values.maxOrNull() ?: 0

    fun search(rawQuery: String, limit: Int = 30): List<Scored> {
        val query = normalizeText(rawQuery)
        if (query.isBlank()) {
            return apps.sortedByDescending { usage[it.key] ?: 0 }
                .take(limit)
                .map { Scored(it, 0f, MatchKind.WORD) }
        }

        val queryTokens = query.split(' ').filter { it.isNotBlank() }
        val queryTrigrams = trigramsOf(query)

        val out = ArrayList<Scored>(apps.size)
        for (app in apps) {
            var best = 0f
            var kind = MatchKind.FUZZY
            val label = app.normalized

            // --- lexical ---------------------------------------------------
            when {
                label == query -> {
                    best = 1.00f; kind = MatchKind.EXACT
                }

                label.startsWith(query) -> {
                    best = 0.94f; kind = MatchKind.PREFIX
                }

                app.tokens.any { it.startsWith(query) } -> {
                    best = 0.88f; kind = MatchKind.PREFIX
                }

                label.contains(query) -> {
                    best = 0.78f; kind = MatchKind.WORD
                }
            }

            // Initials: "gp" -> "Google Play". Short queries only, or it fires
            // on everything.
            if (best < 0.86f && query.length in 2..4 && app.tokens.size > 1) {
                val initials = app.tokens.mapNotNull { it.firstOrNull() }.joinToString("")
                if (initials.startsWith(query)) {
                    best = max(best, 0.86f); kind = MatchKind.PREFIX
                }
            }

            // Every query word appears somewhere: "goog pho" -> Google Photos.
            if (best < 0.80f && queryTokens.size > 1 &&
                queryTokens.all { q -> app.tokens.any { it.startsWith(q) } }
            ) {
                best = max(best, 0.84f); kind = MatchKind.WORD
            }

            // --- fuzzy -----------------------------------------------------
            if (best < 0.78f) {
                val cos = cosine(queryTrigrams, trigrams[app.key].orEmpty())
                val sub = if (isSubsequence(query, label)) 0.34f else 0f
                val fuzzy = max(cos * 0.72f, sub)
                if (fuzzy > best) {
                    best = fuzzy; kind = MatchKind.FUZZY
                }
            }

            // --- semantic --------------------------------------------------
            val semantic = semanticScore(app, query, queryTokens)
            if (semantic > best) {
                best = semantic; kind = MatchKind.SEMANTIC
            }

            if (best <= 0.12f) continue

            // --- personalisation -------------------------------------------
            val used = usage[app.key] ?: 0
            val familiarity = if (maxUsage == 0) 0f else (ln(1.0 + used) / ln(1.0 + maxUsage)).toFloat()
            // Gentle on purpose: enough to break ties, never enough to put a
            // frequently used app above a direct name match on another one.
            val final = best + familiarity * 0.10f - app.label.length * 0.0004f

            out += Scored(app, final, kind)
        }

        return out.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.entry.label.length }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.label }
        ).take(limit)
    }

    /** Single best guess, or null when nothing is confident enough. */
    fun resolve(query: String, minScore: Float = 0.45f): AppEntry? =
        search(query, limit = 1).firstOrNull()?.takeIf { it.score >= minScore }?.entry

    /**
     * How well the query matches what the model said this app is for.
     *
     * Capped below an exact lexical hit so typing a real app name always wins:
     * "Gmail" must open Gmail even though half a dozen apps are described with
     * the word "mail".
     */
    private fun semanticScore(app: AppEntry, query: String, queryTokens: List<String>): Float {
        val words = semanticWords[app.key].orEmpty()
        if (words.isEmpty()) return 0f

        // Whole-phrase hit: the model wrote "rede social" and that is what was typed.
        if (query.length >= 3 && words.any { it == query }) return 0.80f
        if (query.length >= 4 && words.any { it.contains(query) || query.contains(it) }) {
            return 0.72f
        }

        var matched = 0
        for (token in queryTokens) {
            if (token.length < 3) continue
            if (words.any { it == token || it.startsWith(token) || token.startsWith(it) }) matched++
        }
        if (matched == 0) return 0f

        val coverage = matched.toFloat() / queryTokens.size.coerceAtLeast(1)
        return min(0.78f, 0.55f + 0.18f * coverage)
    }

    private companion object {

        fun trigramsOf(s: String): Set<String> {
            val padded = "  $s "
            if (padded.length < 3) return setOf(padded)
            val out = HashSet<String>(padded.length)
            for (i in 0..padded.length - 3) out += padded.substring(i, i + 3)
            return out
        }

        fun cosine(a: Set<String>, b: Set<String>): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val small = if (a.size < b.size) a else b
            val large = if (a.size < b.size) b else a
            var shared = 0
            for (t in small) if (t in large) shared++
            return shared / sqrt(a.size.toDouble() * b.size.toDouble()).toFloat()
        }

        /** "wapp" matches "whatsapp" — cheap ordered-character containment. */
        fun isSubsequence(needle: String, haystack: String): Boolean {
            if (needle.isEmpty()) return true
            var i = 0
            for (c in haystack) {
                if (c == needle[i]) {
                    i++
                    if (i == needle.length) return true
                }
            }
            return false
        }
    }
}
