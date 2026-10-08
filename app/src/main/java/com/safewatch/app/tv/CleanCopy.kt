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
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExoPlayerAssetLoader
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
import com.safewatch.core.StreamInfo
import com.safewatch.core.Strictness
import com.safewatch.core.Superclean
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
 * Something that can be made into a clean copy: a video file on the phone, a link straight to a
 * video file, or a website's stream (HLS or DASH, given in [stream]) whose pieces are not locked.
 * [captions] are addresses of caption files that go with it. [key] is the name scenes marked for
 * it are kept under.
 */
data class CleanSource(
    val title: String,
    val key: String,
    val address: String,
    val captions: List<String> = emptyList(),
    val referrer: String = "",
    val stream: String = "",
    /** A Superclean the viewer asked for: what Claude is to take out, as [Superclean.Wishes] JSON; empty for none. */
    val superclean: String = "",
) {
    fun toJson(): String = JSONObject().put("title", title).put("key", key).put("address", address)
        .put("captions", JSONArray(captions)).put("referrer", referrer).put("stream", stream).put("superclean", superclean).toString()

    companion object {
        const val HLS = "hls"
        const val DASH = "dash"

        fun fromJson(text: String): CleanSource {
            val o = JSONObject(text)
            val caps = o.optJSONArray("captions") ?: JSONArray()
            // Copies made before Superclean had "deep" for what is now a Superclean with the usual choices.
            val superclean = o.optString("superclean").ifEmpty { if (o.optBoolean("deep")) Superclean.Wishes(Superclean.DEFAULT).toJson() else "" }
            return CleanSource(o.getString("title"), o.getString("key"), o.getString("address"),
                (0 until caps.length()).map { caps.getString(it) }, o.optString("referrer"), o.optString("stream"), superclean)
        }

        /** Whether an address is a stream's manifest, and which kind. */
        fun streamKind(address: String): String = when {
            Regex("\\.m3u8(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(address) -> HLS
            Regex("\\.mpd(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(address) -> DASH
            else -> ""
        }

        /** Whether a video address points at a whole file that can be fetched (not a stream cut into pieces). */
        fun isWholeFile(address: String): Boolean {
            if (address.startsWith("content:") || address.startsWith("file:")) return true
            if (!address.startsWith("http")) return false
            return !Regex("\\.(m3u8|mpd|ism)(\\?|$)|/manifest", RegexOption.IGNORE_CASE).containsMatchIn(address)
        }
    }
}

/** A finished clean copy, kept in the app's own storage. [source] is what it was made from, to make it again. */
data class CleanCopyFile(val file: File, val title: String, val summary: String, val madeAt: Long, val durationMs: Long, val source: CleanSource? = null) {
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
        // A new Superclean replaces what an earlier one found, since the family may have chosen differently.
        val tags = ArrayList(TagStore.load(context, source.key).filter { source.superclean.isEmpty() || it.source != Tag.SOURCE_CLAUDE })
        val notes = ArrayList<String>()

        // Cursing, from the captions.
        val cues = source.captions.flatMap { readCaptions(it) }
        if (settings.language != Strictness.OFF) {
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

        // A Superclean, if asked for: Claude looks at the pictures and reads the captions for what the family chose.
        if (source.superclean.isNotEmpty()) {
            if (Prefs.claudeKey(context).isEmpty()) {
                notes += "Superclean was skipped: there is no Claude key in Settings"
            } else {
                val wishes = Superclean.Wishes.fromJson(source.superclean)
                val found = SupercleanRun(context, settings, wishes, report, ::check).run(input, durationMs, cues)
                tags += found.tags
                notes += found.note
                if (found.finished) {
                    // The phone's own player uses them too, next time this video plays.
                    val kept = TagStore.load(context, source.key).filter { it.source != Tag.SOURCE_CLAUDE }
                    TagStore.save(context, source.key, source.title, kept + found.tags)
                }
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
            .put("durationMs", plan.keep.sumOf { it.last - it.first }).put("key", source.key).put("source", source.toJson()).toString())
        work.listFiles()?.forEach { it.delete() }
        return CleanCopyFile(output, source.title, summary, System.currentTimeMillis(), durationMs)
    }

    private fun check() {
        if (cancelled) throw IOException("Stopped")
    }

    // ---- Getting the file ----

    private fun fetch(source: CleanSource, into: File): File {
        val address = source.address
        if (source.stream.isNotEmpty()) return fetchStream(source, into)
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
                val type = c.contentType.orEmpty().lowercase()
                if (type.contains("mpegurl") || type.contains("dash+xml")) {
                    // Not a file after all, but a stream's list of pieces.
                    return fetchStream(source.copy(address = url, stream = if (type.contains("dash")) CleanSource.DASH else CleanSource.HLS), into)
                }
                if (type.startsWith("text/")) throw IOException("The link leads to a page, not a video file")
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

    // ---- Getting a stream that comes in pieces ----

    /**
     * Saves a website's stream as one file. The manifest is read first, and the stream is refused if
     * its pieces are encrypted or it is live. The pieces are then gathered by the same player engine
     * the app uses, signed in as the browser is, and written out as one ordinary video.
     */
    private fun fetchStream(source: CleanSource, into: File): File {
        report("Checking the stream", -1)
        val manifest = httpText(source.address, source.referrer)
        val kind = StreamInfo.kindOf(manifest) ?: throw IOException("The video's list of pieces could not be read")
        val media = ArrayList<String>()
        if (kind == StreamInfo.Kind.HLS && StreamInfo.hlsIsMaster(manifest)) {
            // The best picture, and the sound if it comes separately: both are checked for locks.
            StreamInfo.hlsVariants(manifest).firstOrNull()?.let { media += httpText(URL(URL(source.address), it).toString(), source.referrer) }
            StreamInfo.hlsRenditions(manifest, "AUDIO").firstOrNull()?.let { media += httpText(URL(URL(source.address), it).toString(), source.referrer) }
        }
        StreamInfo.refusal(manifest, media)?.let { throw IOException(it) }
        check()

        val item = MediaItem.Builder().setUri(source.address)
            .setMimeType(if (kind == StreamInfo.Kind.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
            .build()
        val output = File(into.parentFile, into.name + ".mp4")
        export(Composition.Builder(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(item).build()))).build(),
            output, "Downloading the stream", loaderFor(source))
        return output
    }

    /** Reads a stream's pieces the way the browser would: its cookies, and the page it came from. */
    private fun loaderFor(source: CleanSource): AssetLoader.Factory {
        val headers = HashMap<String, String>()
        if (source.referrer.startsWith("http")) {
            headers["Referer"] = source.referrer
            val page = Uri.parse(source.referrer)
            headers["Origin"] = "${page.scheme}://${page.authority}"
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers)
        val withCookies = ResolvingDataSource.Factory(http) { spec ->
            val cookie = try { CookieManager.getInstance().getCookie(spec.uri.toString()) } catch (e: Exception) { null }
            if (cookie.isNullOrEmpty()) spec else spec.withAdditionalHeaders(mapOf("Cookie" to cookie))
        }
        return ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT,
            DefaultMediaSourceFactory(withCookies))
    }

    private fun httpText(address: String, referrer: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", AGENT)
            if (referrer.startsWith("http")) c.setRequestProperty("Referer", referrer)
            CookieManager.getInstance().getCookie(address)?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 200..299) throw IOException("The site refused the video (${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
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
        return if (CaptionFormats.recognises(text)) try { CaptionFormats.parse(text) } catch (e: Throwable) { emptyList() } else emptyList()
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
        if (plan.keep.sumOf { it.last - it.first } < 1000) {
            throw IOException("Everything in this video would be cut out, so there is no copy to make. Try Blur instead of Cut out.")
        }
        // Every video is first written once as an ordinary MP4, whatever it came as, so the cuts, blurs and mutes are
        // always made from a file that can be jumped about in (some downloads have no index for that).
        val plain = File(input.parentFile, "plain.mp4")
        export(Composition.Builder(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).build())))
            .build(), plain, "Getting the video ready", null)
        check()
        transformPieces(plain, output, plan)
    }

    private fun transformPieces(input: File, output: File, plan: CleanPlan) {
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
        export(Composition.Builder(EditedMediaItemSequence(pieces)).build(), output, "Making the clean copy", null)
    }

    /** Writes [composition] to [output] as an MP4 any TV plays, reporting progress as [step]. */
    private fun export(composition: Composition, output: File, step: String, loader: AssetLoader.Factory?) {
        report(step, 0)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        main.post {
            try {
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264) // plays on every TV
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .apply { if (loader != null) setAssetLoaderFactory(loader) }
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
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) report(step, holder.progress)
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
            if (it.message != "Stopped") android.util.Log.i("SafeWatch", "$step failed", it)
            val why = generateSequence(it as Throwable) { e -> e.cause }.drop(1).mapNotNull { e -> e.message }.firstOrNull()
            throw IOException(if (it.message == "Stopped") "Stopped" else "$step failed: ${it.message}" + (why?.let { w -> " ($w)" } ?: ""))
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
                CleanCopyFile(video, o.optString("title", "Video"), o.optString("summary"), o.optLong("madeAt"), o.optLong("durationMs"),
                    o.optString("source").takeIf { it.isNotEmpty() }?.let { try { CleanSource.fromJson(it) } catch (e: Exception) { null } })
            } catch (e: Exception) { null }
        }.sortedByDescending { it.madeAt }

        fun delete(copy: CleanCopyFile) = delete(copy.file)

        fun delete(video: File) {
            video.delete()
            File(video.parentFile, video.name.removeSuffix(".mp4") + ".json").delete()
        }

        /** A clean copy is for one viewing: anything a day old is deleted, whether or not it was played. */
        fun deleteOld(context: Context) {
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60_000L
            folder(context).listFiles().orEmpty().filter { it.lastModified() < dayAgo }.forEach { it.delete() }
            File(context.cacheDir, "clean-work").listFiles().orEmpty().filter { it.lastModified() < dayAgo }.forEach { it.delete() }
        }
    }
}
