package com.safewatch.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A running record of what the filters did: which captions were found, when the sound was
 * muted and for how long, what was blocked. It is kept on the phone only, and shown under
 * Settings > Filter report so that a miss can be explained afterwards.
 *
 * It holds times, counts and page addresses. It never holds caption text.
 */
object FilterLog {
    private const val KEEP = 500
    private val lines = ArrayDeque<String>()
    private var loaded = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(text: String) {
        lines.addLast("${clock.format(Date())}  $text")
        while (lines.size > KEEP) lines.removeFirst()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "filter-report.txt")

    /** Brings back what was recorded before the app was last closed. */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val old = try { file(ctx).readLines() } catch (e: Exception) { emptyList() }
        val now = ArrayList(lines)
        lines.clear()
        (old + now).takeLast(KEEP).forEach { lines.addLast(it) }
    }

    @Synchronized
    fun save(ctx: Context) {
        load(ctx)
        try { file(ctx).writeText(lines.joinToString("\n")) } catch (e: Exception) { /* only a record */ }
    }

    @Synchronized
    fun text(ctx: Context): String {
        load(ctx)
        return if (lines.isEmpty()) "Nothing recorded yet. Play a video, then come back." else lines.joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        lines.clear()
        loaded = true
        try { file(ctx).delete() } catch (e: Exception) { /* nothing to remove */ }
    }
}
