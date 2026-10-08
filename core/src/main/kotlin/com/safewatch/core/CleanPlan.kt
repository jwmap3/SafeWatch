package com.safewatch.core

/**
 * How to make a clean copy of a video: which parts to keep (skipped scenes are cut out), and,
 * within what is kept, which stretches to play silent and which to blur. All in milliseconds
 * of the original video.
 */
data class CleanPlan(
    val durationMs: Long,
    val keep: List<LongRange>,
    val mute: List<LongRange>,
    val blur: List<LongRange>,
) {
    val cutMs: Long get() = durationMs - keep.sumOf { it.last - it.first }

    /** Whether the sound is off at [positionMs] of the original video. */
    fun mutedAt(positionMs: Long): Boolean = inside(mute, positionMs)

    /** Whether the picture is blurred at [positionMs] of the original video. */
    fun blurredAt(positionMs: Long): Boolean = inside(blur, positionMs)

    /** A line for the viewer saying what was done. */
    fun summary(): String {
        val parts = ArrayList<String>()
        if (mute.isNotEmpty()) parts += "${mute.size} ${if (mute.size == 1) "word" else "words"} muted"
        if (blur.isNotEmpty()) parts += "${blur.size} ${if (blur.size == 1) "scene" else "scenes"} blurred"
        val cuts = keep.size - 1 + (if (keep.firstOrNull()?.first ?: 0L > 0L) 1 else 0) + (if ((keep.lastOrNull()?.last ?: durationMs) < durationMs) 1 else 0)
        if (cutMs > 0) parts += "${maxOf(1, cuts)} ${if (cuts == 1) "scene" else "scenes"} cut (${(cutMs + 500) / 1000} s)"
        return if (parts.isEmpty()) "Nothing needed filtering" else parts.joinToString(", ")
    }

    private fun inside(ranges: List<LongRange>, at: Long): Boolean {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = ranges[mid]
            when {
                at < r.first -> hi = mid - 1
                at >= r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}

object CleanPlanner {
    private const val SHORTEST_PIECE_MS = 300L

    /** Plans a clean copy of a video [durationMs] long from what is known about it and what the viewer filters. */
    fun plan(durationMs: Long, tags: List<Tag>, settings: FilterSettings): CleanPlan {
        val active = FilterEngine(tags, settings).activeTags
        fun ranges(action: Action) = join(active.filter { it.action == action }.map {
            it.startMs.coerceIn(0, durationMs)..it.endMs.coerceIn(0, durationMs)
        }.filter { it.last > it.first })
        val skip = ranges(Action.SKIP)
        val keep = ArrayList<LongRange>()
        var at = 0L
        for (s in skip) {
            if (s.first - at >= SHORTEST_PIECE_MS) keep += at..s.first
            at = maxOf(at, s.last)
        }
        if (durationMs - at >= SHORTEST_PIECE_MS || keep.isEmpty()) keep += at..durationMs
        return CleanPlan(durationMs, keep, without(ranges(Action.MUTE), skip), without(ranges(Action.BLUR), skip))
    }

    /** Joins overlapping or touching ranges. */
    fun join(ranges: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    private fun without(ranges: List<LongRange>, cut: List<LongRange>): List<LongRange> {
        var out = ranges
        for (c in cut) out = out.flatMap { r ->
            when {
                c.last <= r.first || c.first >= r.last -> listOf(r)
                else -> listOfNotNull((r.first..c.first).takeIf { c.first > r.first }, (c.last..r.last).takeIf { c.last < r.last })
            }
        }
        return out
    }
}
