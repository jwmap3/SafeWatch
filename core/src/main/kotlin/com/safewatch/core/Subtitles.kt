package com.safewatch.core

/** One subtitle line and when it is on screen. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Reads SRT and WebVTT subtitle files. */
object SubtitleParser {
    private val TIME = "(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})"
    private val TIMING = Regex("$TIME\\s*-->\\s*$TIME")
    private val MARKUP = Regex("<[^>]*>|\\{[^}]*}")

    fun parse(content: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val lines = content.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = TIMING.find(lines[i])
            i++
            if (m == null) continue
            val start = toMs(m.groupValues, 1)
            val end = toMs(m.groupValues, 5)
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !TIMING.containsMatchIn(lines[i])) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(lines[i].trim())
                i++
            }
            val clean = cleanText(text.toString())
            if (clean.isNotEmpty() && end > start) cues += Cue(start, end, clean)
        }
        return cues
    }

    fun cleanText(text: String): String = text.replace(MARKUP, "").replace(Regex("\\s+"), " ").trim()

    private fun toMs(g: List<String>, at: Int): Long {
        val h = g[at].ifEmpty { "0" }.toLong()
        val millis = g[at + 3].padEnd(3, '0').toLong()
        return ((h * 60 + g[at + 1].toLong()) * 60 + g[at + 2].toLong()) * 1000 + millis
    }
}

/**
 * Turns subtitle lines into mute ranges.
 *
 * Subtitles say when a whole line is on screen, not when each word is spoken,
 * so the word's moment is estimated from its position in the line and padded
 * on both sides. Short lines are muted whole.
 */
object CueTagger {
    const val PAD_BEFORE_MS = 400L
    const val PAD_AFTER_MS = 500L
    const val SHORT_CUE_MS = 1200L
    const val EDGE_MS = 150L

    fun tagsFor(cue: Cue, matcher: ProfanityMatcher): List<Tag> {
        val text = SubtitleParser.cleanText(cue.text)
        val matches = matcher.find(text)
        if (matches.isEmpty()) return emptyList()
        val length = cue.endMs - cue.startMs
        val out = matches.map { m ->
            if (length <= SHORT_CUE_MS || text.isEmpty()) {
                mute(cue.startMs - EDGE_MS, cue.endMs + EDGE_MS, m.level)
            } else {
                val from = cue.startMs + length * m.start / text.length - PAD_BEFORE_MS
                val to = cue.startMs + length * m.end / text.length + PAD_AFTER_MS
                mute(maxOf(from, cue.startMs - EDGE_MS), minOf(to, cue.endMs + EDGE_MS), m.level)
            }
        }
        return Ranges.merge(out)
    }

    fun tagsFor(cues: List<Cue>, matcher: ProfanityMatcher): List<Tag> =
        Ranges.merge(cues.flatMap { tagsFor(it, matcher) })

    private fun mute(from: Long, to: Long, level: Int) =
        Tag(maxOf(0, from), to, Category.LANGUAGE, Action.MUTE, level, Tag.SOURCE_CAPTIONS)
}

object Ranges {
    /** Joins overlapping tags that share a category and action, keeping the highest level. */
    fun merge(tags: List<Tag>): List<Tag> {
        val out = ArrayList<Tag>()
        for (t in tags.sortedWith(compareBy({ it.category }, { it.action }, { it.startMs }))) {
            val last = out.lastOrNull()
            if (last != null && last.category == t.category && last.action == t.action && t.startMs <= last.endMs) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, t.endMs), level = maxOf(last.level, t.level))
            } else {
                out += t
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Turns scan samples into tags. Each sample is a time and the level found
     * there (0 for nothing). Hits closer together than two steps become one
     * range, widened by one step on each side to cover the frames between samples.
     */
    fun fromSamples(samples: List<Pair<Long, Int>>, stepMs: Long, category: Category, action: Action): List<Tag> {
        val out = ArrayList<Tag>()
        var start = -1L
        var end = -1L
        var level = 0
        fun flush() {
            if (start >= 0) out += Tag(maxOf(0, start - stepMs), end + stepMs, category, action, level, Tag.SOURCE_SCAN)
            start = -1
            level = 0
        }
        for ((time, found) in samples.sortedBy { it.first }) {
            if (found <= 0) continue
            if (start >= 0 && time - end > 2 * stepMs) flush()
            if (start < 0) start = time
            end = time
            level = maxOf(level, found)
        }
        flush()
        return out
    }
}
