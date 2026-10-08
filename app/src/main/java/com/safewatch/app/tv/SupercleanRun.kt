package com.safewatch.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.util.Log
import com.safewatch.app.data.Prefs
import com.safewatch.core.ClaudeApi
import com.safewatch.core.Cue
import com.safewatch.core.FilterSettings
import com.safewatch.core.Superclean
import com.safewatch.core.Tag
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * A Superclean: on top of the phone's own checks, Claude looks at the video's pictures (small frames, two
 * seconds apart, nine to a sheet) and reads its captions, and says where the things the family chose to take
 * out are, including the scenes they ticked from the title's Parents Guide. Used only when the viewer asks
 * for it, with their own Anthropic API key, billed to their Anthropic account.
 */
class SupercleanRun(
    private val context: Context,
    private val settings: FilterSettings,
    private val wishes: Superclean.Wishes,
    private val report: (step: String, percent: Int) -> Unit,
    private val check: () -> Unit,
) {
    /** What Claude found; [finished] is false when it stopped part way (the key was refused). */
    data class Result(val tags: List<Tag>, val note: String, val finished: Boolean = true)

    private val api = claude(context)
    @Volatile private var keyRefused = false
    @Volatile private var failedParts = 0

    fun run(video: File, durationMs: Long, cues: List<Cue>): Result {
        val step = stepFor(durationMs)
        val looks = wishes.pictureChoices.isNotEmpty() || wishes.guide.isNotEmpty()
        val reads = wishes.wordChoices.isNotEmpty() || wishes.guide.isNotEmpty()
        val scenes = if (looks) pictures(video, durationMs, step) else emptyList()
        if (keyRefused) return Result(emptyList(), "Superclean stopped: the Claude key was not accepted", finished = false)
        val mutes = if (reads && cues.isNotEmpty()) words(cues) else emptyList()
        if (keyRefused) return Result(scenes, "Superclean stopped part way: the Claude key was not accepted", finished = false)
        val missed = failedParts
        val verb = if (wishes.cut) "cut" else "blur"
        val note = "Superclean: Claude found ${scenes.size} ${if (scenes.size == 1) "scene" else "scenes"} to $verb and " +
            "${mutes.size} ${if (mutes.size == 1) "place" else "places"} to mute" +
            (if (reads && cues.isEmpty()) " (no captions to read)" else "") +
            (if (missed > 0) "; $missed ${if (missed == 1) "part" else "parts"} of the video could not be checked by Claude" else "")
        return Result(scenes + mutes, note)
    }

    // ---- Pictures ----

    private fun pictures(video: File, durationMs: Long, step: Long): List<Tag> {
        val perSheet = Superclean.ACROSS * Superclean.DOWN
        val times = (0 until ceil(durationMs.toDouble() / step).toInt()).map { it * step }
        val sheets = times.chunked(perSheet)
        val batches = sheets.chunked(SHEETS_PER_CALL)
        val system = Superclean.pictureSystem(wishes)
        val pool = Executors.newFixedThreadPool(3)
        val flags = ArrayList<Superclean.Flag>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val pending = ArrayList<java.util.concurrent.Future<List<Superclean.Flag>>>()
            var made = 0
            for (batch in batches) {
                val images = batch.map { sheet ->
                    check()
                    made += sheet.size
                    report("Superclean: getting pictures ready for Claude", (made * 100L / times.size).toInt())
                    sheetOf(retriever, sheet)
                }
                pending += pool.submit(Callable { askAboutPictures(system, images) })
                // Answers are collected as they come, so pictures are never held for long.
                while (pending.size > 3) flags += pending.removeAt(0).get()
                if (keyRefused) break
            }
            pending.forEachIndexed { i, f ->
                report("Superclean: Claude is looking at the pictures", 90 + i * 10 / maxOf(1, pending.size))
                flags += f.get()
            }
        } finally {
            pool.shutdownNow()
            try { retriever.release() } catch (e: Exception) { /* already released */ }
        }
        return Superclean.scenes(flags, step, wishes)
    }

    /** Nine frames in a grid, each with its time printed on it, as one JPEG. */
    private fun sheetOf(retriever: MediaMetadataRetriever, times: List<Long>): ByteArray {
        val sheet = Bitmap.createBitmap(CELL_W * Superclean.ACROSS, CELL_H * Superclean.DOWN, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val back = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        val line = Paint().apply { color = Color.rgb(60, 60, 60) }
        times.forEachIndexed { i, t ->
            val left = (i % Superclean.ACROSS) * CELL_W
            val top = (i / Superclean.ACROSS) * CELL_H
            val frame = try {
                retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, CELL_W, CELL_H)
            } catch (e: Exception) { null }
            if (frame != null) {
                // Fitted inside its cell, keeping its shape.
                val scale = minOf(CELL_W.toFloat() / frame.width, CELL_H.toFloat() / frame.height)
                val w = (frame.width * scale).toInt()
                val h = (frame.height * scale).toInt()
                val x = left + (CELL_W - w) / 2
                val y = top + (CELL_H - h) / 2
                canvas.drawBitmap(frame, null, Rect(x, y, x + w, y + h), null)
                frame.recycle()
            }
            val label = Superclean.label(t)
            canvas.drawRect(left.toFloat(), top.toFloat(), left + text.measureText(label) + 10f, top + 24f, back)
            canvas.drawText(label, left + 5f, top + 19f, text)
            canvas.drawRect(left.toFloat(), top.toFloat(), left + 1f, top + CELL_H.toFloat(), line)
            canvas.drawRect(left.toFloat(), top.toFloat(), left + CELL_W.toFloat(), top + 1f, line)
        }
        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 72, out)
        sheet.recycle()
        return out.toByteArray()
    }

    private fun askAboutPictures(system: String, sheets: List<ByteArray>): List<Superclean.Flag> {
        if (keyRefused) return emptyList()
        val parts = sheets.map { ClaudeApi.Part.Jpeg(it) } + ClaudeApi.Part.Text(Superclean.pictureAsk())
        val answer = ask(system, parts, 3000) ?: return emptyList()
        return Superclean.readFlags(answer)
    }

    // ---- Captions ----

    private fun words(cues: List<Cue>): List<Tag> {
        val found = ArrayList<Pair<Cue, Superclean.Mute>>()
        val chunks = cues.chunked(LINES_PER_CALL)
        val system = Superclean.wordsSystem(wishes)
        chunks.forEachIndexed { i, chunk ->
            check()
            report("Superclean: Claude is reading the captions", i * 100 / chunks.size)
            val answer = ask(system, listOf(ClaudeApi.Part.Text(Superclean.wordsAsk(chunk.map { it.text }))), 4000) ?: return@forEachIndexed
            for (mute in Superclean.readMutes(answer)) chunk.getOrNull(mute.line - 1)?.let { found += it to mute }
        }
        return Superclean.muteTags(found, settings)
    }

    /** One call to Claude, tried again if Anthropic is busy. Null when it could not be done. */
    private fun ask(system: String, parts: List<ClaudeApi.Part>, maxTokens: Int): String? {
        var wait = 4000L
        repeat(5) { attempt ->
            if (keyRefused) return null
            try {
                return api.ask(system, parts, maxTokens)
            } catch (e: ClaudeApi.Failure) {
                Log.i("SafeWatch", "superclean: ${e.message}")
                if (e.status == 401 || e.status == 403) { keyRefused = true; return null }
                if (!e.worthRetrying || attempt == 4) { failedParts++; return null }
                Thread.sleep(maxOf(wait, e.retryAfterMs))
                wait *= 2
            }
        }
        failedParts++
        return null
    }

    companion object {
        private const val CELL_W = 320
        private const val CELL_H = 180
        private const val SHEETS_PER_CALL = 10
        private const val LINES_PER_CALL = 250

        /**
         * The made-up key the automatic test phone uses: Claude is then the test computer's stand-in for Anthropic
         * (tools/test-site.py), so Superclean can be tried there without a real key. Real keys never go there.
         */
        private const val DEVICE_CHECK_KEY = "test-key-for-the-device-check"

        fun claude(context: Context): ClaudeApi {
            val key = Prefs.claudeKey(context)
            val model = Prefs.claudeModel(context)
            return if (key == DEVICE_CHECK_KEY) ClaudeApi(key, model, "http://10.0.2.2:8765") else ClaudeApi(key, model)
        }

        /** Frames two seconds apart, or three for videos over three hours. */
        fun stepFor(durationMs: Long): Long = if (durationMs > 3 * 3_600_000L) 3000L else 2000L

        /**
         * About what a Superclean costs, in US dollars, at Anthropic's published prices (Sonnet 5.5: $2 per
         * million tokens read and $10 written; Haiku 5.5: $0.10 and $0.50 for requests this size).
         */
        fun costEstimate(durationMs: Long, model: String): Double {
            val sheets = ceil(durationMs.toDouble() / stepFor(durationMs) / (Superclean.ACROSS * Superclean.DOWN))
            val calls = ceil(sheets / SHEETS_PER_CALL)
            // A 960 by 540 sheet is 35 by 20 tiles of 28 pixels: 700 tokens. The list of choices adds about 1,200
            // a call; captions roughly 15,000 an hour.
            val read = sheets * 700 + calls * 1_200 + durationMs / 3_600_000.0 * 18_000
            val written = calls * 300 + durationMs / 3_600_000.0 * 2_500
            return if (model == ClaudeApi.HAIKU) read * 0.10 / 1e6 + written * 0.50 / 1e6 else read * 2.0 / 1e6 + written * 10.0 / 1e6
        }

        fun costText(durationMs: Long, model: String): String {
            val dollars = costEstimate(durationMs, model)
            return if (dollars < 0.10) "a few cents" else "about $" + String.format(java.util.Locale.US, "%.2f", dollars)
        }

        /**
         * About what looking up a Parents Guide costs: a few web searches ($10 per thousand) and reading the
         * pages Claude finds (up to about 40,000 tokens).
         */
        fun guideCostText(model: String): String = if (model == ClaudeApi.HAIKU) "a few cents" else "about 10 cents"

        /**
         * Has Claude look up a title's IMDb Parents Guide. Takes a while (Claude searches the web and reads
         * the guide); call off the main thread. Throws [ClaudeApi.Failure] when it cannot be done.
         */
        fun lookUpGuide(context: Context, title: String, page: String): Superclean.Guide {
            val api = claude(context)
            val question = listOf(ClaudeApi.Part.Text(Superclean.guideAsk(title, page)))
            val answer = try {
                api.ask(Superclean.guideSystem(), question, maxTokens = 6000, timeoutMs = 240_000, tools = Superclean.GUIDE_TOOLS)
            } catch (e: ClaudeApi.Failure) {
                // An organisation can turn off Claude's reading of web pages; searching alone still finds most guides.
                if (e.status != 400) throw e
                Log.i("SafeWatch", "parents guide: ${e.message}; searching only")
                try {
                    api.ask(Superclean.guideSystem(), question, maxTokens = 6000, timeoutMs = 240_000, tools = Superclean.SEARCH_TOOLS)
                } catch (f: ClaudeApi.Failure) {
                    val said = f.message.orEmpty()
                    if (f.status == 400 && (said.contains("web search", true) || said.contains("web_search", true))) {
                        throw ClaudeApi.Failure("Web search is turned off for your Anthropic account. It can be turned back on at " +
                            "platform.claude.com/settings/capabilities.", 400)
                    }
                    throw f
                }
            }
            return Superclean.readGuide(answer)
        }
    }
}
