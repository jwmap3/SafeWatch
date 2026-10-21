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
    // Whatever a caption writer saw fit to blank out is muted at every setting but Off.
    private val censoredLevel = if (settings.language != Strictness.OFF) 3 else 0
    private val settings = settings

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
        if (censoredLevel == 0 && exact.isEmpty() && prefixes.isEmpty() && contains.isEmpty() && phrases.isEmpty()) return emptyList()
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
        // Captions that arrive already censored ("f***", "sh*t", "[ __ ]", "[bleep]") still mark spoken profanity.
        // YouTube's automatic captions write every swear word as "[ __ ]".
        if (censoredLevel > 0) {
            for (pattern in CENSORED) {
                pattern.findAll(text).forEach { out += WordMatch(it.range.first, it.range.last + 1, censoredLevel) }
            }
            // Three symbols in a row stand for a swear word too ("#$%"), when they are not all the same.
            GRAWLIX.findAll(text).filter { m -> m.value.length == 3 && m.value.filter { it in "@#$%&*" }.toSet().size >= 2 }
                .forEach { out += WordMatch(it.range.first, it.range.last + 1, censoredLevel) }
            // Words with letters blanked, dashed or swapped for symbols ("f--k", "*ss", "a\$\$", "sh!t", "b1tch"), checked
            // against the word list so that ordinary dashes and symbols are left alone.
            for (m in MASKED.findAll(text)) {
                val found = masked(m.value) ?: continue
                out += WordMatch(m.range.first + found.first, m.range.first + found.second, found.third)
            }
        }
        return out.sortedBy { it.start }
    }

    fun containsProfanity(text: String): Boolean = find(text).isNotEmpty()

    /**
     * Whether [raw], a run of letters with blanks or symbols in it, is a word from the list written that way. Returns where
     * the word sits inside [raw] and its level, or null when it is not one.
     */
    private fun masked(raw: String): Triple<Int, Int, Int>? {
        // Punctuation at the ends is just punctuation ("wow!", "-and"). Dashes after a word are a broken-off sentence
        // ("I was--"), unless they plainly stand for the rest of the word ("f---", "b--").
        var from = 0
        var to = raw.length
        while (from < to && raw[from] in "-!") from++
        while (to > from && raw[to - 1] == '!') to--
        var dashes = 0
        while (to - dashes > from && raw[to - dashes - 1] == '-') dashes++
        val before = raw.substring(from, to - dashes)
        val keepDashes = dashes >= 3 || (dashes == 2 && before.length == 1 && before.lowercase() !in setOf("a", "i"))
        if (!keepDashes) to -= dashes
        val word = raw.substring(from, to)
        if (word.none { it.isLetter() }) return null
        val blanks = BLANKS.findAll(word).toList()
        val swaps = word.any { it in "@\$!" || it.isDigit() }
        if (blanks.isEmpty() && !swaps) return null
        if (word.all { it.isLetterOrDigit() } && word.count { it.isDigit() } > word.count { it.isLetter() }) return null // 4x4, mp4
        // The word as a pattern: blanks stand for a few letters each, symbols for the letters they look like.
        val pattern = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            val blank = blanks.firstOrNull { it.range.first == i }
            if (blank != null) {
                pattern.append("\\p{L}{1,").append(blank.value.length + 2).append("}")
                i = blank.range.last + 1
                continue
            }
            pattern.append(when (c.lowercaseChar()) {
                '@', '4' -> "a"
                '\$', '5' -> "s"
                '!', '1' -> "[il]"
                '0' -> "o"
                '3' -> "e"
                '7' -> "t"
                in 'a'..'z' -> c.lowercaseChar().toString()
                else -> if (c.isLetter()) Regex.escape(c.lowercase()) else return null
            })
            i++
        }
        val regex = Regex(pattern.toString())
        val wanted = allowed
        var level = 0
        for ((spelled, group) in SPELLINGS) {
            if (spelled in wanted || !regex.matches(spelled)) continue
            // Blanked out by the caption writer: always muted. Spelled with symbols: muted as the word itself would be.
            level = if (blanks.isNotEmpty()) censoredLevel else if (settings.mutes(group)) group.level else 0
            if (level > 0) break
        }
        return if (level > 0) Triple(from, to, level) else null
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
        private val CENSORED = listOf(
            Regex("\\p{L}+\\*+\\p{L}+|\\p{L}+\\*{2,}"),                 // f**k, sh*t, f***
            Regex("\\[[\\s\\u00A0\\u2000-\\u200B\\u202F\\u3000]*_+[\\s\\u00A0\\u2000-\\u200B\\u202F\\u3000]*\\]"),                      // [ __ ], as YouTube writes it
            Regex("(?<![\\p{L}_])\\p{L}{1,2}_{2,}\\p{L}{0,2}(?![\\p{L}_])"), // f___, s__t
            Regex("[\\[(<]\\s*(?:bleep\\w*|beep\\w*|expletive\\w*|censor\\w*|profanit\\w*|obscenit\\w*|swear\\w*|curs(?:e|es|ing)|" +
                "explicit(?: language)?|bad word\\w*)\\s*[\\])>]", RegexOption.IGNORE_CASE),            // [bleep], (expletive), [censored]
            Regex("[*-]\\s*bleep\\w*\\s*[*-]", RegexOption.IGNORE_CASE),                             // *bleep*, -bleep-
            Regex("(?=[@#$%&!*]*[@#$%&*])[@#$%&!*]{4,}"),              // @#$%!, ****
            Regex("[\\[(]?[■▪▮█▇▆▅▄▓▒░]{2,}[\\])]?"),                       // ■■■■, as some players black words out
            Regex("(?<![\\p{L}_])_{3,}(?![\\p{L}_])"),                          // a blank line where the word was: "you ___"
        )
        private val BLANKS = Regex("[*_#%&]+|-+")
        private val GRAWLIX = Regex("(?<![@#$%&!*])[@#$%&!*]{3}(?![@#$%&!*])")
        // A run of letters, digits and the symbols used to blank or disguise a word.
        private val MASKED = Regex("[\\p{L}\\p{N}@$!#%&*_-]*[\\p{L}\\p{N}][\\p{L}\\p{N}@$!#%&*_-]*")

        /** Every built-in word in its usual forms, to test a blanked-out word against. Blasphemy is left out ("G-d" is reverent). */
        private val SPELLINGS: List<Pair<String, WordGroup>> by lazy {
            val endings = listOf("", "s", "ed", "er", "ers", "ing", "in", "y", "ies", "head", "hole", "face", "ass")
            val starts = listOf("", "bull", "mother", "dip", "horse", "chicken", "holy", "dumb", "jack", "smart")
            val out = LinkedHashMap<String, WordGroup>()
            for (group in WordList.groups) {
                if (group.blasphemy) continue
                for (p in group.patterns) {
                    val t = p.trim().lowercase()
                    if (t.contains(' ')) continue
                    val stem = normalize(t)
                    if (stem.length < 3 && !t.endsWith("*")) { out.putIfAbsent(stem, group); continue }
                    when {
                        t.startsWith("*") && t.endsWith("*") -> for (a in starts) for (b in endings) out.putIfAbsent(a + stem + b, group)
                        t.endsWith("*") -> for (b in endings) out.putIfAbsent(stem + b, group)
                        else -> out.putIfAbsent(stem, group)
                    }
                }
            }
            out.entries.map { it.key to it.value }
        }

        fun normalize(word: String): String =
            word.lowercase().filter { it.isLetterOrDigit() }
    }
}
