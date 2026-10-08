package com.safewatch.core

/**
 * Reads a whole caption file in whichever format a video player downloaded it:
 * WebVTT or SRT, TTML (used by Netflix, Prime Video and others), or YouTube's
 * own two formats.
 *
 * Having the whole file means every line's time is known before it is spoken,
 * so muting can start on time instead of when the words appear on screen.
 * YouTube's automatic captions go further and time each word.
 */
object CaptionFormats {
    private val TAGS = Regex("<[^>]*>")

    /** True when [text] looks like a caption file this can read. Only the start of the text is examined. */
    fun recognises(text: String): Boolean {
        val head = text.take(1500)
        return head.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT") ||
            Regex("<tt[\\s:>]").containsMatchIn(head) ||
            head.contains("<timedtext") ||
            (head.contains("\"events\"") && (text.contains("\"segs\"") || text.contains("tStartMs"))) ||
            Regex("\\d\\d:\\d\\d:\\d\\d[,.]\\d{3}\\s*-->").containsMatchIn(head)
    }

    fun parse(text: String): List<Cue> {
        val head = text.take(1500)
        return when {
            head.contains("\"events\"") && text.contains("tStartMs") -> youtubeJson(text)
            head.contains("<timedtext") -> youtubeXml(text)
            Regex("<tt[\\s:>]").containsMatchIn(head) -> ttml(text)
            else -> SubtitleParser.parse(text)
        }
    }

    // ---- TTML ----

    private fun ttml(text: String): List<Cue> {
        val tickRate = Regex("tickRate=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 10_000_000.0
        val frameRate = Regex("frameRate=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 30.0
        val cues = ArrayList<Cue>()
        for (p in Regex("<(?:\\w+:)?p\\b([^>]*)>(.*?)</(?:\\w+:)?p>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val attributes = p.groupValues[1]
            val begin = attribute(attributes, "begin")?.let { ttmlTime(it, tickRate, frameRate) } ?: continue
            val end = attribute(attributes, "end")?.let { ttmlTime(it, tickRate, frameRate) }
                ?: attribute(attributes, "dur")?.let { d -> ttmlTime(d, tickRate, frameRate)?.let { begin + it } }
                ?: continue
            val words = clean(p.groupValues[2].replace(Regex("<(?:\\w+:)?br\\s*/?>"), " "))
            if (words.isNotEmpty() && end > begin) cues += Cue(begin, end, words)
        }
        return cues
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("(?:^|\\s)$name=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

    /** TTML writes times as a clock (01:02:03.5 or 01:02:03:12 with frames), or a number with a unit. */
    private fun ttmlTime(value: String, tickRate: Double, frameRate: Double): Long? {
        val v = value.trim()
        Regex("^(\\d+):(\\d\\d):(\\d\\d)(?:[.,](\\d+)|:(\\d+))?$").find(v)?.let { m ->
            val whole = (m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000
            val fraction = when {
                m.groupValues[4].isNotEmpty() -> ("0." + m.groupValues[4]).toDouble() * 1000
                m.groupValues[5].isNotEmpty() -> m.groupValues[5].toDouble() / frameRate * 1000
                else -> 0.0
            }
            return whole + fraction.toLong()
        }
        Regex("^([\\d.]+)(h|m|s|ms|f|t)$").find(v)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1000
                "ms" -> n
                "f" -> n / frameRate * 1000
                else -> n / tickRate * 1000
            }.toLong()
        }
        return null
    }

    // ---- YouTube ----

    // {"events":[{"tStartMs":1200,"dDurationMs":3000,"segs":[{"utf8":"so"},{"utf8":" what","tOffsetMs":320}]}]}
    // Automatic captions carry a time for each word, which is used to mute the word alone.
    private fun youtubeJson(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val events = Regex("\\{[^{}]*\"tStartMs\"\\s*:\\s*(\\d+)[^{}]*?(?:\"segs\"\\s*:\\s*\\[(.*?)])?[^{}]*}", RegexOption.DOT_MATCHES_ALL)
        for (event in events.findAll(text)) {
            val start = event.groupValues[1].toLong()
            val length = Regex("\"dDurationMs\"\\s*:\\s*(\\d+)").find(event.value)?.groupValues?.get(1)?.toLong() ?: 2000
            val segs = Regex("\\{[^{}]*}").findAll(event.groupValues[2]).map { seg ->
                val words = Regex("\"utf8\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(seg.value)?.groupValues?.get(1).orEmpty()
                val offset = Regex("\"tOffsetMs\"\\s*:\\s*(\\d+)").find(seg.value)?.groupValues?.get(1)?.toLong()
                unescapeJson(words) to offset
            }.toList()
            if (segs.isEmpty()) continue
            val timedWords = segs.count { it.second != null }
            if (timedWords > 0) {
                segs.forEachIndexed { i, (words, offset) ->
                    val from = start + (offset ?: 0)
                    val to = segs.getOrNull(i + 1)?.second?.let { start + it } ?: minOf(from + 900, start + length)
                    val clean = clean(words)
                    if (clean.isNotEmpty() && to > from) cues += Cue(from, to, clean)
                }
            } else {
                val line = clean(segs.joinToString("") { it.first })
                if (line.isNotEmpty()) cues += Cue(start, start + length, line)
            }
        }
        return cues
    }

    // <timedtext><body><p t="1200" d="3000">so <s t="320">what</s></p></body></timedtext>
    private fun youtubeXml(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (p in Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val start = attribute(p.groupValues[1], "t")?.toLongOrNull() ?: continue
            val length = attribute(p.groupValues[1], "d")?.toLongOrNull() ?: 2000
            val line = clean(p.groupValues[2])
            if (line.isNotEmpty()) cues += Cue(start, start + length, line)
        }
        return cues
    }

    private fun clean(s: String): String = s.replace(TAGS, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun unescapeJson(s: String): String = s.replace("\\n", " ").replace("\\\"", "\"").replace("\\/", "/")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\\\", "\\")
}
