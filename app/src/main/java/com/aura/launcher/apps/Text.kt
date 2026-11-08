package com.aura.launcher.apps

import java.text.Normalizer

/**
 * The combining marks NFD leaves behind, compiled once.
 *
 * A Unicode property class is not a cheap pattern to compile, and this runs in
 * two places that call it in a loop: the trigram index builds it per memory and
 * per MCP tool on every request that consults them, and the app profiler runs it
 * per keyword. Rebuilding the same matcher a few hundred times to strip accents
 * off a word is work nothing asked for.
 */
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Strip diacritics and case so "Câmera" and "camera" collide. */
fun normalizeText(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .trim()
