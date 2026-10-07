package com.safewatch.core

/** A filtered word or phrase found at [start] until [end] (exclusive) in a text. */
data class WordMatch(val start: Int, val end: Int, val level: Int)

/** Finds the words in a piece of text that the given settings want muted. */
class ProfanityMatcher(settings: FilterSettings) {

    private class Token(val text: String, val start: Int, val end: Int)

    private val exact = HashMap<String, Int>()
    private val prefixes = ArrayList<Pair<String, Int>>()
    private val contains = ArrayList<Pair<String, Int>>()
    private val phrases = ArrayList<Pair<List<String>, Int>>()
    private val allowed = settings.allowedWords.map(::normalize).filter { it.isNotEmpty() }.toSet()
    private val censoredLevel = if (settings.language.filters(2)) 2 else 0

    init {
        if (settings.language != Strictness.OFF) {
            for (group in WordList.groups) {
                if (settings.mutes(group)) group.patterns.forEach { add(it, group.level) }
            }
            settings.customWords.forEach { add(it, 3) }
        }
    }

    private fun add(pattern: String, level: Int) {
        val p = pattern.trim().lowercase()
        val words = p.split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }
        when {
            words.isEmpty() -> Unit
            words.size > 1 -> phrases += words to level
            p.startsWith("*") && p.endsWith("*") -> contains += words[0] to level
            p.endsWith("*") -> prefixes += words[0] to level
            else -> exact[words[0]] = maxOf(exact[words[0]] ?: 0, level)
        }
    }

    fun find(text: String): List<WordMatch> {
        if (exact.isEmpty() && prefixes.isEmpty() && contains.isEmpty() && phrases.isEmpty()) return emptyList()
        val tokens = TOKEN.findAll(text).map { Token(normalize(it.value), it.range.first, it.range.last + 1) }.toList()
        val out = ArrayList<WordMatch>()

        for (t in tokens) {
            if (t.text in allowed) continue
            var level = exact[t.text] ?: 0
            for ((p, l) in prefixes) if (l > level && t.text.startsWith(p)) level = l
            for ((p, l) in contains) if (l > level && t.text.contains(p)) level = l
            if (level > 0) out += WordMatch(t.start, t.end, level)
        }
        for ((words, level) in phrases) {
            for (i in 0..tokens.size - words.size) {
                if (words.indices.all { tokens[i + it].text == words[it] }) {
                    out += WordMatch(tokens[i].start, tokens[i + words.size - 1].end, level)
                }
            }
        }
        // Captions that arrive already censored ("f***", "[ __ ]") still mark spoken profanity.
        if (censoredLevel > 0) {
            CENSORED.findAll(text).forEach { out += WordMatch(it.range.first, it.range.last + 1, censoredLevel) }
        }
        return out.sortedBy { it.start }
    }

    fun containsProfanity(text: String): Boolean = find(text).isNotEmpty()

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
        private val CENSORED = Regex("\\p{L}+\\*{2,}\\p{L}*|\\[\\s*_+\\s*]")

        fun normalize(word: String): String =
            word.lowercase().filter { it.isLetterOrDigit() }
    }
}
