package com.muna.livetranslate

import java.util.Locale

/**
 * Whisper sometimes gets stuck repeating itself ("I'm not saying I don't believe you.
 * I'm not saying I don't believe you. I'm not saying..."). This keeps one copy.
 */
object RepetitionFilter {

    private val SENTENCE = Regex("[^.!?؟。]+[.!?؟。]*")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun clean(text: String): String {
        val seen = ArrayList<String>()
        val kept = ArrayList<String>()
        for (match in SENTENCE.findAll(text)) {
            val key = normalize(match.value)
            if (key.isEmpty() || key in seen) continue
            // A cut-off repeat: only the beginning of a sentence already kept.
            if (seen.any { it.startsWith("$key ") }) continue
            seen.add(key)
            kept.add(match.value.trim())
        }
        return collapseWordLoops(kept.joinToString(" ").split(' ').filter { it.isNotEmpty() })
            .joinToString(" ")
    }

    /** "no no no no" -> "no"; "I mean it I mean it" -> "I mean it". */
    private fun collapseWordLoops(words: List<String>): List<String> {
        val keys = words.map { normalize(it) }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            var skipped = false
            for (n in 1..8) {
                if (i + 2 * n > words.size) break
                val block = keys.subList(i, i + n)
                var reps = 1
                while (i + (reps + 1) * n <= words.size &&
                    keys.subList(i + reps * n, i + (reps + 1) * n) == block
                ) reps++
                if (reps >= (if (n <= 2) 3 else 2)) {
                    out.addAll(words.subList(i, i + n))
                    i += reps * n
                    skipped = true
                    break
                }
            }
            if (!skipped) out.add(words[i++])
        }
        return out
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(NON_WORD, " ").trim()
}
