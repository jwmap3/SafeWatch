package com.safewatch.app.player

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.safewatch.app.MainActivity
import com.safewatch.app.R
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.TagStore
import com.safewatch.app.detect.NudityDetector
import com.safewatch.app.ui.SceneDialog
import com.safewatch.app.ui.Ui
import com.safewatch.core.Action
import com.safewatch.core.Category
import com.safewatch.core.CueTagger
import com.safewatch.core.FilterEngine
import com.safewatch.core.FilterSettings
import com.safewatch.core.MediaKey
import com.safewatch.core.ProfanityMatcher
import com.safewatch.core.Ranges
import com.safewatch.core.Strictness
import com.safewatch.core.SubtitleParser
import com.safewatch.core.Tag
import java.io.File
import java.util.concurrent.Executors

/**
 * Plays a video file from the phone with the filters applied.
 *
 * Language: load the film's subtitle file and the filtered words are muted.
 * Nudity: frames are checked while playing, and "Scan" checks the whole film
 * ahead of time so the blur or skip lands exactly on the scene.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var cover: View
    private lateinit var status: TextView
    private lateinit var markButton: TextView
    private lateinit var scanButton: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var uri: Uri
    private var key = ""
    private var title = ""
    private var settings = FilterSettings()
    private var engine = FilterEngine(emptyList(), settings)
    private var detector: NudityDetector? = null
    private var checking = false
    private var lastCheckAt = 0L
    private var liveHideUntil = 0L
    private var hidden = false
    private var mutedByFilter = false
    private var markStartMs: Long? = null
    @Volatile private var scanning = false

    private val pickSubtitles = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(picked)!!.bufferedReader().use { it.readText() }
            val cues = SubtitleParser.parse(text)
            if (cues.isEmpty()) {
                Ui.toast(this, "No subtitle lines found in that file")
            } else {
                subtitleFile().writeText(text)
                rebuildEngine()
                Ui.toast(this, "Subtitles loaded: ${cues.size} lines")
            }
        } catch (e: Exception) {
            Ui.toast(this, "Could not read that file")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data
        if (source == null) {
            finish()
            return
        }
        uri = source
        setContentView(R.layout.activity_player)
        Ui.fitSystemBars(this, findViewById(R.id.root), lightBars = false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        cover = findViewById(R.id.cover)
        status = findViewById(R.id.status)
        status.background = Ui.rounded(Color.argb(170, 0, 0, 0), Ui.dp(this, 16).toFloat())

        describeFile()
        buildBar(findViewById(R.id.bar))

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    private fun describeFile() {
        var name = uri.lastPathSegment ?: "video"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Keep the name taken from the address.
        }
        title = name
        key = MediaKey.forFile(name, size)
    }

    private fun buildBar(bar: LinearLayout) {
        val pad = Ui.dp(this, 4)
        bar.setPadding(pad, pad, pad, pad)
        markButton = Ui.pill(this, MARK_START, filled = false) { onMarkTapped() }
        scanButton = Ui.barButton(this, "Scan") { onScanTapped() }
        bar.addView(Ui.barButton(this, "Done") { finish() })
        bar.addView(Ui.barButton(this, "Subtitles") { pickSubtitles.launch(arrayOf("*/*")) })
        bar.addView(Ui.spacer(this))
        bar.addView(markButton)
        bar.addView(Ui.spacer(this))
        bar.addView(scanButton)
        bar.addView(Ui.iconButton(this, R.drawable.ic_cast, "Send to TV", R.color.text) { Ui.sendToTv(this) })
        bar.addView(Ui.barButton(this, "Filters") { MainActivity.open(this, MainActivity.TAB_FILTERS) })
    }

    override fun onResume() {
        super.onResume()
        if (!::player.isInitialized) return
        settings = Prefs.settings(this)
        rebuildEngine()
        if (detector == null && settings.nudity != Strictness.OFF) {
            background.execute {
                val opened = NudityDetector.open(applicationContext)
                ui.post { if (isDestroyed) opened?.close() else detector = opened }
            }
        }
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        scanning = false
        ui.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        val open = detector
        detector = null
        background.execute { open?.close() }
        background.shutdown()
        super.onDestroy()
    }

    private fun subtitleFile(): File =
        File(File(filesDir, "subtitles").apply { mkdirs() }, MediaKey.fileName(key) + ".txt")

    /** Combines saved scenes with mute ranges worked out from the subtitles and the current word settings. */
    private fun rebuildEngine() {
        val saved = TagStore.load(this, key)
        val sub = subtitleFile()
        val fromSubtitles = if (sub.exists() && settings.language != Strictness.OFF) {
            CueTagger.tagsFor(SubtitleParser.parse(sub.readText()), ProfanityMatcher(settings))
        } else emptyList()
        engine = FilterEngine(saved + fromSubtitles, settings)
    }

    // ---- Playback loop ----

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val state = engine.stateAt(player.currentPosition)
            val skipTo = state.skipToMs
            if (skipTo != null) {
                val duration = player.duration
                player.seekTo(if (duration > 0) minOf(skipTo, duration) else skipTo)
            }
            val mute = state.mute || skipTo != null
            if (mute != mutedByFilter) {
                mutedByFilter = mute
                player.volume = if (mute) 0f else 1f
            }
            val hide = state.blur || now < liveHideUntil
            if (hide != hidden) {
                hidden = hide
                playerView.videoSurfaceView?.let { Ui.setHidden(it, cover, hide) }
            }
            if (player.isPlaying && !checking && now - lastCheckAt >= CHECK_EVERY_MS) checkFrame(now)
            ui.postDelayed(this, TICK_MS)
        }
    }

    /** Checks the frame on screen. The frame is read before any blur is applied, so it can be checked while hidden. */
    private fun checkFrame(now: Long) {
        val det = detector ?: return
        if (settings.nudity == Strictness.OFF) return
        val surface = playerView.videoSurfaceView as? TextureView ?: return
        if (surface.width == 0 || surface.height == 0) return
        val scale = 320f / maxOf(surface.width, surface.height)
        val frame = surface.getBitmap(
            (surface.width * scale).toInt().coerceAtLeast(1),
            (surface.height * scale).toInt().coerceAtLeast(1),
        ) ?: return
        lastCheckAt = now
        checking = true
        val testing = Prefs.testingBlur(this)
        background.execute {
            val level = try { det.maxLevel(frame, testing) } catch (e: Exception) { 0 }
            ui.post {
                checking = false
                if (level > 0 && settings.nudity.filters(level)) liveHideUntil = SystemClock.elapsedRealtime() + LIVE_HOLD_MS
            }
        }
    }

    // ---- Marking a scene by hand ----

    private fun onMarkTapped() {
        val start = markStartMs
        if (start == null) {
            markStartMs = player.currentPosition
            markButton.text = MARK_END
            return
        }
        val end = player.currentPosition
        markStartMs = null
        markButton.text = MARK_START
        if (end <= start) {
            Ui.toast(this, "The end has to come after the start")
            return
        }
        SceneDialog.show(this, start, end) { tag ->
            TagStore.add(this, key, title, tag)
            rebuildEngine()
            Ui.toast(this, "Scene saved")
        }
    }

    // ---- Scanning the whole film ahead of time ----

    private fun onScanTapped() {
        if (scanning) {
            scanning = false
            return
        }
        val det = detector
        if (det == null) {
            Ui.toast(this, "Import the detection model in Filters first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Scan this video for nudity?")
            .setMessage("The whole video is checked once and the scenes found are remembered. This can take a while for a full film; you can keep watching.")
            .setPositiveButton("Scan") { _, _ -> startScan(det) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startScan(det: NudityDetector) {
        val duration = player.duration
        if (duration <= 0) {
            Ui.toast(this, "The video has not finished loading yet")
            return
        }
        scanning = true
        scanButton.text = "Stop"
        showStatus("Scanning 0%")
        Thread {
            val samples = ArrayList<Pair<Long, Int>>()
            val retriever = MediaMetadataRetriever()
            var finished = false
            try {
                retriever.setDataSource(this, uri)
                var t = 0L
                while (t < duration && scanning) {
                    val frame = retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                    if (frame != null) samples += t to det.maxLevel(frame)
                    if (samples.size % 10 == 0) {
                        val percent = (t * 100 / duration).toInt()
                        ui.post { if (scanning) showStatus("Scanning $percent%") }
                    }
                    t += SCAN_STEP_MS
                }
                finished = scanning
            } catch (e: Exception) {
                // Reported below as an unfinished scan.
            } finally {
                try { retriever.release() } catch (e: Exception) { /* already released */ }
            }
            val found = Ranges.fromSamples(samples, SCAN_STEP_MS, Category.NUDITY, Action.BLUR)
            ui.post {
                if (isDestroyed) return@post
                scanning = false
                scanButton.text = "Scan"
                if (finished) {
                    // A new scan replaces the last one; scenes marked by hand are kept.
                    val kept = TagStore.load(this, key).filter { it.source != Tag.SOURCE_SCAN }
                    TagStore.save(this, key, title, kept + found)
                    rebuildEngine()
                    showStatus(if (found.isEmpty()) "Scan finished: nothing found" else "Scan finished: ${found.size} scenes found", 4000)
                } else {
                    showStatus("Scan stopped", 2500)
                }
            }
        }.start()
    }

    private fun showStatus(text: String, hideAfterMs: Long = 0) {
        status.text = text
        status.visibility = View.VISIBLE
        ui.removeCallbacks(hideStatus)
        if (hideAfterMs > 0) ui.postDelayed(hideStatus, hideAfterMs)
    }

    private val hideStatus = Runnable { status.visibility = View.GONE }

    companion object {
        private const val MARK_START = "Mark scene"
        private const val MARK_END = "End scene"
        private const val TICK_MS = 40L
        private const val CHECK_EVERY_MS = 200L
        private const val LIVE_HOLD_MS = 1500L
        private const val SCAN_STEP_MS = 1000L
    }
}
