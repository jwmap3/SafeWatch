package com.safewatch.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.TagStore
import com.safewatch.app.detect.NudityDetector
import com.safewatch.core.Action
import com.safewatch.core.CaptionFormats
import com.safewatch.core.Category
import com.safewatch.core.CleanPlan
import com.safewatch.core.CleanPlanner
import com.safewatch.core.CueTagger
import com.safewatch.core.ProfanityMatcher
import com.safewatch.core.Ranges
import com.safewatch.core.Strictness
import com.safewatch.core.Tag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Something that can be made into a clean copy: a video file on the phone, or a link straight
 * to a video file. [captions] are addresses of caption files that go with it. [key] is the
 * name scenes marked for it are kept under.
 */
data class CleanSource(val title: String, val key: String, val address: String, val captions: List<String> = emptyList(), val referrer: String = "") {
    fun toJson(): String = JSONObject().put("title", title).put("key", key).put("address", address)
        .put("captions", JSONArray(captions)).put("referrer", referrer).toString()

    companion object {
        fun fromJson(text: String): CleanSource {
            val o = JSONObject(text)
            val caps = o.optJSONArray("captions") ?: JSONArray()
            return CleanSource(o.getString("title"), o.getString("key"), o.getString("address"),
                (0 until caps.length()).map { caps.getString(it) }, o.optString("referrer"))
        }

        /** Whether a video address points at a whole file that can be fetched (not a stream cut into pieces). */
        fun isWholeFile(address: String): Boolean {
            if (address.startsWith("content:") || address.startsWith("file:")) return true
            if (!address.startsWith("http")) return false
            return !Regex("\\.(m3u8|mpd|ism)(\\?|$)|/manifest", RegexOption.IGNORE_CASE).containsMatchIn(address)
        }
    }
}

/** A finished clean copy, kept in the app's own storage. */
data class CleanCopyFile(val file: File, val title: String, val summary: String, val madeAt: Long, val durationMs: Long) {
    val sizeText: String get() {
        val mb = file.length() / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
}

/**
 * Makes clean copies: the video with the filtering built in, so any TV can play it as an
 * ordinary file and nothing on the phone needs to be running to filter it.
 *
 * The steps: get the file; find the cursing from its captions; find nudity by checking the
 * whole video, a picture at a time; then write a new file in which the cursing is silent and
 * the nudity is blurred (or cut out, if the viewer chose Skip). Scenes marked by hand are
 * included. All of it happens on the phone.
 */
@UnstableApi
class CleanCopy(private val context: Context, private val report: (step: String, percent: Int) -> Unit) {
    @Volatile var cancelled = false

    /** Runs every step. Call off the main thread. Returns the copy, or throws with a reason a person can read. */
    fun make(source: CleanSource): CleanCopyFile {
        val work = File(context.cacheDir, "clean-work").apply { mkdirs() }
        work.listFiles()?.forEach { it.delete() }
        val input = fetch(source, File(work, "original"))
        check()
        val durationMs = durationOf(input)
        if (durationMs <= 0) throw IOException("This file does not look like a video the phone can read")

        val settings = Prefs.settings(context)
        val tags = ArrayList(TagStore.load(context, source.key))
        val notes = ArrayList<String>()

        // Cursing, from the captions.
        if (settings.language != Strictness.OFF) {
            val cues = source.captions.flatMap { readCaptions(it) }
            if (cues.isEmpty()) notes += "no captions were found, so cursing could not be muted"
            else tags += CueTagger.tagsFor(cues, ProfanityMatcher(settings))
        }
        check()

        // Nudity, from checking the pictures, unless this video was checked before.
        if (settings.nudity != Strictness.OFF && tags.none { it.source == Tag.SOURCE_SCAN }) {
            val found = scan(input, durationMs)
            if (found == null) notes += "nudity detection is not set up, so nothing was blurred"
            else {
                tags += found
                val kept = TagStore.load(context, source.key).filter { it.source != Tag.SOURCE_SCAN }
                TagStore.save(context, source.key, source.title, kept + found) // the live player benefits too
            }
        }
        check()

        val plan = CleanPlanner.plan(durationMs, tags, settings)
        val id = System.currentTimeMillis().toString()
        val output = File(folder(context), "$id.mp4")
        transform(input, output, plan)
        check()
        val summary = (listOf(plan.summary()) + notes).joinToString("; ")
        File(folder(context), "$id.json").writeText(JSONObject()
            .put("title", source.title).put("summary", summary).put("madeAt", System.currentTimeMillis())
            .put("durationMs", plan.keep.sumOf { it.last - it.first }).put("key", source.key).toString())
        work.listFiles()?.forEach { it.delete() }
        return CleanCopyFile(output, source.title, summary, System.currentTimeMillis(), durationMs)
    }

    private fun check() {
        if (cancelled) throw IOException("Stopped")
    }

    // ---- Getting the file ----

    private fun fetch(source: CleanSource, into: File): File {
        val address = source.address
        if (address.startsWith("content:") || address.startsWith("file:")) {
            report("Copying the video", 0)
            context.contentResolver.openInputStream(Uri.parse(address))!!.use { input ->
                into.outputStream().use { input.copyTo(it, 256 * 1024) }
            }
            return into
        }
        var url = address
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", AGENT)
            if (source.referrer.isNotEmpty()) c.setRequestProperty("Referer", source.referrer)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("The site sent the download nowhere")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("The site refused the download ($code)")
                val type = c.contentType.orEmpty()
                if (type.startsWith("text/") || type.contains("mpegurl") || type.contains("dash+xml")) {
                    throw IOException("This video comes in streaming pieces, not as one file, so it cannot be saved")
                }
                val total = c.contentLengthLong
                var done = 0L
                var lastShown = -1
                c.inputStream.use { input ->
                    into.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            check()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastShown) {
                                lastShown = percent
                                report("Downloading" + if (total <= 0) " (${done / (1024 * 1024)} MB)" else "", percent)
                            }
                        }
                    }
                }
                return into
            } finally {
                c.disconnect()
            }
        }
        throw IOException("The download was sent elsewhere too many times")
    }

    private fun durationOf(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            try { r.release() } catch (e: Exception) { /* already released */ }
        }
    }

    private fun readCaptions(address: String): List<com.safewatch.core.Cue> {
        val text = try {
            if (address.startsWith("http")) {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", AGENT)
                CookieManager.getInstance().getCookie(address)?.let { c.setRequestProperty("Cookie", it) }
                try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            } else {
                File(address.removePrefix("file://")).readText()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return if (CaptionFormats.recognises(text)) try { CaptionFormats.parse(text) } catch (e: Exception) { emptyList() } else emptyList()
    }

    // ---- Finding nudity ----

    /** Checks a picture every second or so through the whole video. Null when detection is not set up. */
    private fun scan(file: File, durationMs: Long): List<Tag>? {
        val detector = NudityDetector.open(context) ?: return null
        val step = if (durationMs < 20 * 60_000) 500L else 1000L
        // "Test the blur" counts faces, which are smaller than bodies, so it looks at larger pictures.
        val testing = Prefs.testingBlur(context)
        val size = if (testing) 640 else 320
        val samples = ArrayList<Pair<Long, Int>>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            var t = 0L
            while (t < durationMs) {
                check()
                val frame: Bitmap? = try {
                    retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, size, size)
                } catch (e: Exception) { null }
                if (frame != null) samples += t to (try { detector.maxLevel(frame, testing) } catch (e: Exception) { 0 })
                report("Checking the pictures for nudity", (t * 100 / durationMs).toInt())
                t += step
            }
        } finally {
            try { retriever.release() } catch (e: Exception) { /* already released */ }
            detector.close()
        }
        return Ranges.fromSamples(samples, step, Category.NUDITY, Action.BLUR)
    }

    // ---- Writing the clean copy ----

    private fun transform(input: File, output: File, plan: CleanPlan) {
        report("Making the clean copy", 0)
        val pieces = plan.keep.map { piece ->
            val clipped = piece.first > 0 || piece.last < plan.durationMs
            val item = MediaItem.Builder().setUri(Uri.fromFile(input)).apply {
                if (clipped) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(piece.first).setEndPositionMs(piece.last).build())
            }.build()
            val video = if (plan.blur.isEmpty()) emptyList() else listOf(StretchBlur(plan, piece.first))
            val audio = if (plan.mute.isEmpty()) emptyList() else listOf(SilenceProcessor(plan, piece.first))
            EditedMediaItem.Builder(item).setEffects(Effects(audio, video)).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(pieces)).build()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        main.post {
            try {
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264) // plays on every TV
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            failure.set(exportException)
                            done.countDown()
                        }
                    })
                    .build()
                transformer = t
                t.start(composition, output.absolutePath)
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (done.count == 0L) return
                        if (cancelled) {
                            t.cancel()
                            failure.set(IOException("Stopped"))
                            done.countDown()
                            return
                        }
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) report("Making the clean copy", holder.progress)
                        main.postDelayed(this, 1000)
                    }
                }
                main.postDelayed(poll, 1000)
            } catch (e: Exception) {
                failure.set(e)
                done.countDown()
            }
        }
        done.await()
        failure.get()?.let {
            output.delete()
            throw IOException(if (it.message == "Stopped") "Stopped" else "The clean copy could not be made: ${it.message}")
        }
    }

    companion object {
        private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun folder(context: Context): File = File(context.filesDir, "clean").apply { mkdirs() }

        /** Every clean copy made so far, newest first. */
        fun all(context: Context): List<CleanCopyFile> = folder(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { meta ->
            val video = File(meta.parentFile, meta.name.removeSuffix(".json") + ".mp4")
            if (!video.exists()) return@mapNotNull null
            try {
                val o = JSONObject(meta.readText())
                CleanCopyFile(video, o.optString("title", "Video"), o.optString("summary"), o.optLong("madeAt"), o.optLong("durationMs"))
            } catch (e: Exception) { null }
        }.sortedByDescending { it.madeAt }

        fun delete(copy: CleanCopyFile) {
            copy.file.delete()
            File(copy.file.parentFile, copy.file.name.removeSuffix(".mp4") + ".json").delete()
        }
    }
}
