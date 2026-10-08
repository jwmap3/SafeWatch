package com.safewatch.core

import java.util.TreeMap

/**
 * What is known about a video before the viewer gets there.
 *
 * A second, hidden copy of the video plays a few seconds in front of the one
 * being watched. Each picture taken from it is recorded here as a sample: a
 * position in the video and the nudity level found at it. The player then
 * asks, for the position it is showing, whether to hide the picture.
 *
 * The answer errs on the side of hiding. A position is hidden when the sample
 * just before it or the one just after it was flagged, or when any flagged
 * sample lies within [reachMs] of it. So the blur goes up [reachMs] before the
 * first flagged picture, stays through a scene even if one picture in the
 * middle was missed, and comes down [reachMs] after the last.
 */
class LookAhead(private val reachMs: Long = 2000) {
    private val samples = TreeMap<Long, Int>()

    /** The widest gap between two samples that still counts as having looked at everything between them. */
    @Volatile var maxGapMs: Long = 3000

    @Synchronized
    fun add(positionMs: Long, level: Int) {
        // A second look at the same moment keeps the worse of the two findings.
        val slot = positionMs / SLOT_MS * SLOT_MS
        samples[slot] = maxOf(level, samples[slot] ?: 0)
    }

    /** True when the video around [positionMs] has been looked at, so [levelAt] can be relied on. */
    @Synchronized
    fun covers(positionMs: Long): Boolean {
        val before = samples.floorKey(positionMs) ?: return false
        val after = samples.ceilingKey(positionMs) ?: return false
        return after - before <= maxGapMs
    }

    /** The highest level found near [positionMs]; 0 when nothing was found or nothing is known. */
    @Synchronized
    fun levelAt(positionMs: Long): Int {
        var level = maxOf(samples.floorEntry(positionMs)?.takeIf { positionMs - it.key <= maxGapMs }?.value ?: 0,
            samples.ceilingEntry(positionMs)?.takeIf { it.key - positionMs <= maxGapMs }?.value ?: 0)
        for (found in samples.subMap(positionMs - reachMs, true, positionMs + reachMs, true).values) level = maxOf(level, found)
        return level
    }

    /** How far past [positionMs] the looking has got without a break, in milliseconds. */
    @Synchronized
    fun knownAheadOf(positionMs: Long): Long {
        var at = samples.floorKey(positionMs) ?: return 0
        while (true) {
            val next = samples.higherKey(at) ?: break
            if (next - at > maxGapMs) break
            at = next
        }
        return (at - positionMs).coerceAtLeast(0)
    }

    /** Every stretch that would be hidden at [minLevel] or above, for keeping between viewings. */
    @Synchronized
    fun stretches(minLevel: Int): List<LongRange> {
        val out = ArrayList<LongRange>()
        for ((at, level) in samples) {
            if (level < minLevel) continue
            val start = (at - reachMs).coerceAtLeast(0)
            val end = at + reachMs
            val last = out.lastOrNull()
            if (last != null && start <= last.last + maxGapMs) out[out.size - 1] = last.first..maxOf(last.last, end) else out += start..end
        }
        return out
    }

    @get:Synchronized
    val size: Int get() = samples.size

    @Synchronized
    fun clear() = samples.clear()

    private companion object {
        const val SLOT_MS = 100L
    }
}
