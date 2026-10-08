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
import com.safewatch.core.DeepClean
import com.safewatch.core.FilterSettings
import com.safewatch.core.Tag
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * A Deep clean: on top of the phone's own checks, Claude looks at the video's pictures (small frames, two
 * seconds apart, nine to a sheet) and reads its captions, and says what else to hide or mute. Used only
 * when the viewer asks for it, with their own Anthropic API key, billed to their Anthropic account.
 */
class DeepCleanRun(
    private val context: Context,
    private val settings: FilterSettings,
    private val report: (step: String, percent: Int) -> Unit,
    private val check: () -> Unit,
) {
    data class Result(val tags: List<Tag>, val note: String)

    private val api = ClaudeApi(Prefs.claudeKey(context), Prefs.claudeModel(context))
    @Volatile private var keyRefused = false

    fun run(video: File, durationMs: Long, cues: List<Cue>): Result {
        val step = stepFor(durationMs)
        val scenes = pictures(video, durationMs, step)
        if (keyRefused) return Result(emptyList(), "Deep clean stopped: the Claude key was not accepted")
        val words = if (cues.isNotEmpty()) words(cues) else emptyList()
        val missed = failedParts
        val note = "Claude found ${scenes.size} more ${if (scenes.size == 1) "scene" else "scenes"} to hide and " +
            "${words.size} more ${if (words.size == 1) "word" else "words"} to mute" +
            (if (cues.isEmpty()) " (no captions to read)" else "") +
            (if (missed > 0) "; $missed ${if (missed == 1) "part" else "parts"} of the video could not be checked by Claude" else "")
        return Result(scenes + words, note)
    }

    @Volatile private var failedParts = 0

    // ---- Pictures ----

    private fun pictures(video: File, durationMs: Long, step: Long): List<Tag> {
        val perSheet = DeepClean.ACROSS * DeepClean.DOWN
        val times = (0 until ceil(durationMs.toDouble() / step).toInt()).map { it * step }
        val sheets = times.chunked(perSheet)
        val batches = sheets.chunked(SHEETS_PER_CALL)
        val pool = Executors.newFixedThreadPool(3)
        val flags = ArrayList<DeepClean.Flag>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val pending = ArrayList<java.util.concurrent.Future<List<DeepClean.Flag>>>()
            var made = 0
            for (batch in batches) {
                val images = batch.map { sheet ->
                    check()
                    made += sheet.size
                    report("Deep clean: getting pictures ready for Claude", (made * 100L / times.size).toInt())
                    sheetOf(retriever, sheet)
                }
                pending += pool.submit(Callable { askAboutPictures(images) })
                // Answers are collected as they come, so pictures are never held for long.
                while (pending.size > 3) flags += pending.removeAt(0).get()
                if (keyRefused) break
            }
            pending.forEachIndexed { i, f ->
                report("Deep clean: Claude is looking at the pictures", 90 + i * 10 / maxOf(1, pending.size))
                flags += f.get()
            }
        } finally {
            pool.shutdownNow()
            try { retriever.release() } catch (e: Exception) { /* already released */ }
        }
        return DeepClean.scenes(flags, step)
    }

    /** Nine frames in a grid, each with its time printed on it, as one JPEG. */
    private fun sheetOf(retriever: MediaMetadataRetriever, times: List<Long>): ByteArray {
        val sheet = Bitmap.createBitmap(CELL_W * DeepClean.ACROSS, CELL_H * DeepClean.DOWN, Bitmap.Config.ARGB_8888)
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
            val left = (i % DeepClean.ACROSS) * CELL_W
            val top = (i / DeepClean.ACROSS) * CELL_H
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
            val label = DeepClean.label(t)
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

    private fun askAboutPictures(sheets: List<ByteArray>): List<DeepClean.Flag> {
        if (keyRefused) return emptyList()
        val parts = sheets.map { ClaudeApi.Part.Jpeg(it) } + ClaudeApi.Part.Text(DeepClean.pictureAsk())
        val answer = ask(DeepClean.pictureSystem(), parts) ?: return emptyList()
        return DeepClean.readFlags(answer)
    }

    // ---- Captions ----

    private fun words(cues: List<Cue>): List<Tag> {
        val found = ArrayList<Pair<Cue, List<String>>>()
        val chunks = cues.chunked(LINES_PER_CALL)
        chunks.forEachIndexed { i, chunk ->
            check()
            report("Deep clean: Claude is reading the captions", i * 100 / chunks.size)
            val answer = ask(DeepClean.wordsSystem(settings), listOf(ClaudeApi.Part.Text(DeepClean.wordsAsk(chunk.map { it.text })))) ?: return@forEachIndexed
            for ((line, words) in DeepClean.readWords(answer)) chunk.getOrNull(line - 1)?.let { found += it to words }
        }
        return DeepClean.wordTags(cues, found, settings)
    }

    /** One call to Claude, tried again if Anthropic is busy. Null when it could not be done. */
    private fun ask(system: String, parts: List<ClaudeApi.Part>): String? {
        var wait = 4000L
        repeat(5) { attempt ->
            if (keyRefused) return null
            try {
                return api.ask(system, parts)
            } catch (e: ClaudeApi.Failure) {
                Log.i("SafeWatch", "deep clean: ${e.message}")
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

        /** Frames two seconds apart, or three for videos over three hours. */
        fun stepFor(durationMs: Long): Long = if (durationMs > 3 * 3_600_000L) 3000L else 2000L

        /**
         * About what a Deep clean costs, in US dollars, at Anthropic's published prices (Sonnet 5.5: $2 per
         * million tokens read and $10 written; Haiku 5.5: $0.10 and $0.50 for requests this size).
         */
        fun costEstimate(durationMs: Long, model: String): Double {
            val sheets = ceil(durationMs.toDouble() / stepFor(durationMs) / (DeepClean.ACROSS * DeepClean.DOWN))
            val calls = ceil(sheets / SHEETS_PER_CALL)
            // A 960 by 540 sheet is 35 by 20 tiles of 28 pixels: 700 tokens. Captions add roughly 15,000 an hour.
            val read = sheets * 700 + calls * 400 + durationMs / 3_600_000.0 * 15_000
            val written = calls * 250 + durationMs / 3_600_000.0 * 2_000
            return if (model == ClaudeApi.HAIKU) read * 0.10 / 1e6 + written * 0.50 / 1e6 else read * 2.0 / 1e6 + written * 10.0 / 1e6
        }

        fun costText(durationMs: Long, model: String): String {
            val dollars = costEstimate(durationMs, model)
            return if (dollars < 0.10) "a few cents" else "about $" + String.format(java.util.Locale.US, "%.2f", dollars)
        }
    }
}
