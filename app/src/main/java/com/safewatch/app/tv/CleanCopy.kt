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
    /** The browser's own name for itself (user agent) when the video was found, since some sites only serve that browser. */
    val agent: String = "",
    /** The request headers the site's player sent for this video, to send the same. */
    val headers: Map<String, String> = emptyMap(),
) {
    /** What to send with every request for this video: the player's own headers, with the user agent and page. */
    fun requestHeaders(script: Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent
        if (referrer.startsWith("http")) {
            out["Referer"] = referrer
            if (script) Uri.parse(referrer).let { page -> out["Origin"] = "${page.scheme}://${page.authority}" }
        }
        for ((k, v) in headers) {
            out.keys.firstOrNull { it.equals(k, true) }?.let { out.remove(it) }
            out[k] = v
        }
        return out
    }

    val userAgent: String get() = agent.ifEmpty { CleanCopy.AGENT }

    fun toJson(): String = JSONObject().put("title", title).put("key", key).put("address", address)
        .put("captions", JSONArray(captions)).put("referrer", referrer).put("stream", stream).put("superclean", superclean).put("agent", agent)
        .put("headers", JSONObject(headers as Map<*, *>)).toString()

    companion object {
        const val HLS = "hls"
        const val DASH = "dash"

        fun fromJson(text: String): CleanSource {
            val o = JSONObject(text)
            val caps = o.optJSONArray("captions") ?: JSONArray()
            // Copies made before Superclean had "deep" for what is now a Superclean with the usual choices.
            val superclean = o.optString("superclean").ifEmpty { if (o.optBoolean("deep")) Superclean.Wishes(Superclean.DEFAULT).toJson() else "" }
            return CleanSource(o.getString("title"), o.getString("key"), o.getString("address"),
                (0 until caps.length()).map { caps.getString(it) }, o.optString("referrer"), o.optString("stream"), superclean, o.optString("agent"),
                o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> h.optString(k) } }.orEmpty())
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

    /** The cover picture made from the video, or null if there is none. */
    val thumb: File? get() = File(file.parentFile, file.nameWithoutExtension + ".jpg").takeIf { it.exists() }
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
class CleanCopy(private val context: Context, private val tell: (step: String, percent: Int) -> Unit) {
    @Volatile var cancelled = false

    // Each step reports its own 0 to 100; the viewer sees one bar for the whole copy, each step taking its share.
    private var shareFrom = 0
    private var shareTo = 100

    private fun share(from: Int, to: Int) {
        shareFrom = from
        shareTo = to
    }

    private var lastStep = ""

    private fun report(step: String, percent: Int) {
        if (step != lastStep) { lastStep = step; com.safewatch.app.data.FilterLog.add("copy: $step") }
        reportShare(step, percent)
    }

    private fun reportShare(step: String, percent: Int) =
        tell(step, if (percent < 0) -1 else shareFrom + (shareTo - shareFrom) * percent.coerceIn(0, 100) / 100)

    /** Runs every step. Call off the main thread. Returns the copy, or throws with a reason a person can read. */
    fun make(source: CleanSource): CleanCopyFile {
        val work = File(context.cacheDir, "clean-work").apply { mkdirs() }
        work.listFiles()?.forEach { it.delete() }
        val superclean = source.superclean.isNotEmpty() && Prefs.claudeKey(context).isNotEmpty()
        // A one-tap Superclean looks up the title's Parents Guide while the video downloads.
        val asked = if (source.superclean.isNotEmpty()) Superclean.Wishes.fromJson(source.superclean) else null
        val guide = if (superclean && asked?.autoGuide == true) java.util.concurrent.FutureTask {
            try { SupercleanRun.lookUpGuide(context, source.title, source.referrer) to null } catch (e: Exception) { null to (e.message ?: "it could not be reached") }
        }.also { Thread(it).start() } else null
        share(0, if (superclean) 25 else 35)
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
            share(if (superclean) 25 else 35, if (superclean) 35 else 55)
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
                var wishes = asked ?: Superclean.Wishes(Superclean.DEFAULT)
                guide?.let { lookup ->
                    report("Superclean: reading the IMDb Parents Guide", 100)
                    val (found, failed) = lookup.get()
                    when {
                        found != null && !found.isEmpty -> {
                            val scenes = wishes.scenesFrom(found)
                            wishes = wishes.copy(guide = wishes.guide + scenes)
                            notes += "the IMDb Parents Guide" + (if (found.title.isNotEmpty()) " for ${found.title}" else "") +
                                " added ${scenes.size} ${if (scenes.size == 1) "scene" else "scenes"} to look for"
                        }
                        failed != null -> notes += "the Parents Guide could not be read ($failed)"
                        else -> notes += "no Parents Guide was found for this title"
                    }
                }
                share(35, 75)
                val found = SupercleanRun(context, settings, wishes, { step, percent -> report(step, percent) }, ::check).run(input, durationMs, cues)
                tags += found.tags
                notes += found.note
                // Keep a running estimate of what has been spent on the key.
                val model = Prefs.claudeModel(context)
                var spent = SupercleanRun.costEstimate(durationMs, model)
                if (wishes.autoGuide || wishes.guide.isNotEmpty()) spent += if (model == com.safewatch.core.ClaudeApi.HAIKU) 0.01 else 0.10
                Prefs.addSupercleanSpent(context, Math.round(spent * 100))
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
        // A cover picture, taken a little way into the finished copy (or, failing that, from the original).
        val cover = File(folder(context), "$id.jpg")
        if (!makeThumb(output, cover)) makeThumb(input, cover)
        com.safewatch.app.data.FilterLog.add("copy: cover picture " + if (cover.exists()) "made (${cover.length() / 1024} KB)" else "not made")
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
            // Exactly as the site's player asked for it.
            for ((k, v) in source.requestHeaders(script = false)) c.setRequestProperty(k, v)
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
        val manifest = httpText(source.address, source)
        val kind = StreamInfo.kindOf(manifest) ?: throw IOException("The video's list of pieces could not be read")
        val media = ArrayList<String>()
        if (kind == StreamInfo.Kind.HLS && StreamInfo.hlsIsMaster(manifest)) {
            // The best picture, and the sound if it comes separately: both are checked for locks.
            StreamInfo.hlsVariants(manifest).firstOrNull()?.let { media += httpText(URL(URL(source.address), it).toString(), source) }
            StreamInfo.hlsRenditions(manifest, "AUDIO").firstOrNull()?.let { media += httpText(URL(URL(source.address), it).toString(), source) }
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

    /** Reads a stream's pieces the way the browser would: its cookies, and the headers the site's player sent. */
    private fun loaderFor(source: CleanSource): AssetLoader.Factory {
        val headers = source.requestHeaders(script = true)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(source.userAgent)
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

    private fun httpText(address: String, source: CleanSource): String {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            for ((k, v) in source.requestHeaders(script = true)) c.setRequestProperty(k, v)
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

    private fun readCaptions(address: String): List<com.safewatch.core.Cue> = Companion.readCaptions(address)

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

    /** The picture's bitrate for the copy: at least what the original had, and plenty for its size, so it looks as good on a big TV. */
    private var bitrate = 0

    private fun bitrateFor(file: File): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val original = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) 0 else maxOf((w.toLong() * h * 30 / 5).toInt(), original).coerceIn(4_000_000, 50_000_000)
        } catch (e: Exception) {
            0
        } finally {
            try { r.release() } catch (e: Exception) { /* already released */ }
        }
    }

    private fun transform(input: File, output: File, plan: CleanPlan) {
        bitrate = bitrateFor(input)
        if (plan.keep.sumOf { it.last - it.first } < 1000) {
            throw IOException("Everything in this video would be cut out, so there is no copy to make. Try Blur instead of Cut out.")
        }
        // Every video is first written once as an ordinary MP4, whatever it came as, so the cuts, blurs and mutes are
        // always made from a file that can be jumped about in (some downloads have no index for that).
        val plain = File(input.parentFile, "plain.mp4")
        val ready = shareTo
        share(ready, ready + (100 - ready) / 2)
        export(Composition.Builder(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).build())))
            .build(), plain, "Getting the video ready", null)
        check()
        transformPieces(plain, output, plan)
    }

    private fun transformPieces(input: File, output: File, plan: CleanPlan) {
        share(shareTo, 100)
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
                    .apply {
                        if (bitrate > 0) setEncoderFactory(androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .setEnableFallback(true).build())
                    }
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
            val why = generateSequence(it as Throwable) { e -> e.cause }.drop(1).mapNotNull { e -> e.message }.lastOrNull()
            throw IOException(if (it.message == "Stopped") "Stopped" else "$step failed: ${it.message}" + (why?.let { w -> " ($w)" } ?: ""))
        }
    }

    companion object {
        /** The caption lines in a caption file, from the web (signed in as the browser is) or the phone. */
        fun readCaptions(address: String): List<com.safewatch.core.Cue> {
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

        const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

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

        /**
         * Takes a frame from [video] and writes it as a cover picture. Several places are tried, and the
         * brightest is kept, so the cover is not a black fade or a blurred scene. Returns whether it worked.
         */
        fun makeThumb(video: File, into: File): Boolean {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(video.absolutePath)
                val length = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val places = (if (length > 0) listOf(minOf(20_000L, length / 3), length / 4, length / 2, length / 8, length * 2 / 3) else listOf(0L))
                    .map { it.coerceIn(0, maxOf(0, length - 500)) }.distinct()
                var best: android.graphics.Bitmap? = null
                var bestLight = -1
                for (at in places) {
                    val frame = grab(r, at * 1000) ?: continue
                    val light = brightness(frame)
                    if (light > bestLight) { best?.recycle(); best = frame; bestLight = light } else frame.recycle()
                    if (light >= 60) break // bright enough: no need to look further
                }
                val picture = best ?: r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return false
                val part = File(into.parentFile, into.name + ".part")
                part.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                picture.recycle()
                return part.length() > 0 && part.renameTo(into)
            } catch (e: Throwable) {
                return false
            } finally {
                try { r.release() } catch (e: Exception) { /* already released */ }
            }
        }

        /** One frame near [atUs], at most 640 wide. Phones differ in which way of asking works, so each is tried in turn. */
        private fun grab(r: MediaMetadataRetriever, atUs: Long): android.graphics.Bitmap? {
            val frame = try { r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360) } catch (e: Throwable) { null }
                ?: try { r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (e: Throwable) { null }
                ?: try { r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST) } catch (e: Throwable) { null }
                ?: try { r.frameAtTime } catch (e: Throwable) { null }
                ?: return null
            if (frame.width <= 640) return frame
            val scaled = android.graphics.Bitmap.createScaledBitmap(frame, 640, maxOf(1, 640 * frame.height / frame.width), true)
            if (scaled !== frame) frame.recycle()
            return scaled
        }

        /** How light a picture is, 0 (black) to 255 (white), from a spread of points across it. */
        private fun brightness(b: android.graphics.Bitmap): Int {
            var sum = 0L
            var n = 0
            for (y in 1..8) for (x in 1..12) {
                val c = b.getPixel(x * (b.width - 1) / 13, y * (b.height - 1) / 9)
                sum += (299 * android.graphics.Color.red(c) + 587 * android.graphics.Color.green(c) + 114 * android.graphics.Color.blue(c)) / 1000
                n++
            }
            return (sum / n).toInt()
        }

        private val coverTried = java.util.Collections.synchronizedSet(HashSet<String>())

        /**
         * Makes the cover pictures that are missing (copies made before covers, or whose cover failed), in the
         * background, then calls [done] on the main thread if any were made. Each copy is tried once per run of the app.
         */
        fun makeMissingThumbs(context: Context, copies: List<CleanCopyFile>, done: () -> Unit) {
            val missing = copies.filter { it.thumb == null && coverTried.add(it.file.absolutePath) }
            if (missing.isEmpty()) return
            Thread {
                var made = 0
                for (copy in missing) {
                    if (makeThumb(copy.file, File(copy.file.parentFile, copy.file.nameWithoutExtension + ".jpg"))) made++
                }
                if (made > 0) android.os.Handler(android.os.Looper.getMainLooper()).post(done)
            }.start()
        }

        fun delete(copy: CleanCopyFile) = delete(copy.file)

        fun delete(video: File) {
            video.delete()
            val base = video.name.removeSuffix(".mp4")
            File(video.parentFile, "$base.json").delete()
            File(video.parentFile, "$base.jpg").delete()
        }

        /** Clean copies are kept for as long as Settings says (a week by default), then deleted to save space. */
        fun deleteOld(context: Context) {
            val days = Prefs.keepCopiesDays(context)
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60_000L
                folder(context).listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().filter { it.lastModified() < cutoff }.forEach { delete(it) }
            }
            // Half-finished work is always cleared after a day.
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60_000L
            File(context.cacheDir, "clean-work").listFiles().orEmpty().filter { it.lastModified() < dayAgo }.forEach { it.delete() }
        }
    }
}
