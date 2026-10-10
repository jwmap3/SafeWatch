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
    @Volatile private var scanning = false

    // A scrubbed movie, already cleaned: a simpler bar, and it can be shown on the TV with edenTV mode.
    private var isCopy = false
    private var wantsTv = false
    private var tv: com.safewatch.app.tv.TvStage? = null
    private var remote: View? = null
    private var stopWatchingTv: (() -> Unit)? = null

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
        isCopy = intent.hasExtra(EXTRA_TITLE)
        if (isCopy) title = intent.getStringExtra(EXTRA_TITLE) ?: title
        wantsTv = intent.getBooleanExtra(EXTRA_TV, false) || com.safewatch.app.tv.TvMode.active
        if (isCopy) buildCopyBar(findViewById(R.id.bar)) else buildBar(findViewById(R.id.bar))

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
        scanButton = Ui.barButton(this, "Scan") { onScanTapped() }
        bar.addView(Ui.barButton(this, "Done") { finish() })
        bar.addView(Ui.barButton(this, "Subtitles") { pickSubtitles.launch(arrayOf("*/*")) })
        bar.addView(Ui.spacer(this))
        bar.addView(scanButton)
        bar.addView(Ui.barButton(this, "Superclean") {
            val captions = listOfNotNull(subtitleFile().takeIf { it.exists() }?.absolutePath)
            com.safewatch.app.tv.SupercleanActivity.start(this, com.safewatch.app.tv.CleanSource(title, key, uri.toString(), captions))
        })
        bar.addView(Ui.iconButton(this, R.drawable.ic_cast, "Send to TV", R.color.text) {
            val captions = listOfNotNull(subtitleFile().takeIf { it.exists() }?.absolutePath)
            Ui.sendToTv(this, com.safewatch.app.tv.CleanSource(title, key, uri.toString(), captions))
        })
        bar.addView(Ui.barButton(this, "Filters") { MainActivity.open(this, MainActivity.TAB_FILTERS) })
    }

    /** The bar for a scrubbed movie: it is already clean, so only Done, edenTV mode and Send to TV. */
    private fun buildCopyBar(bar: LinearLayout) {
        val pad = Ui.dp(this, 4)
        bar.setPadding(pad, pad, pad, pad)
        bar.addView(Ui.barButton(this, "Done") { finish() })
        bar.addView(TextView(this).apply {
            text = title
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Ui.color(context, R.color.text))
            setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Ui.iconButton(this, R.drawable.ic_tv, "edenTV mode", R.color.text) { startTv() })
        bar.addView(Ui.iconButton(this, R.drawable.ic_cast, "Send to TV", R.color.text) {
            val file = File(uri.path ?: return@iconButton)
            val copy = com.safewatch.app.tv.CleanCopy.all(this).firstOrNull { it.file == file } ?: return@iconButton
            com.safewatch.app.tv.TvActivity.findTv(this) { device ->
                player.pause()
                com.safewatch.app.tv.TvActivity.sendCopy(this, copy, device)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (wantsTv) watchForTv()
    }

    override fun onStop() {
        stopWatchingTv?.invoke()
        stopWatchingTv = null
        leaveTv()
        super.onStop()
    }

    // ---- edenTV mode: the picture on the TV, the phone as its remote ----

    private fun startTv() {
        wantsTv = true
        watchForTv()
    }

    private fun watchForTv() {
        if (stopWatchingTv == null) stopWatchingTv = com.safewatch.app.tv.TvMode.watch(this) { d -> if (d == null) leaveTv() else goOnTv(d) }
        val display = com.safewatch.app.tv.TvMode.display(this)
        if (display != null) { goOnTv(display); return }
        // Not connected yet: open Smart View, and the movie moves onto the TV as soon as it connects.
        showStatus("Choose your TV in Smart View", 6000)
        Ui.openScreenCasting(this)
    }

    private fun goOnTv(display: android.view.Display) {
        if (tv != null || isFinishing) return
        val screen = findViewById<View>(R.id.screen)
        val parent = screen.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(screen)
        val params = screen.layoutParams
        parent.removeView(screen)
        val stage = com.safewatch.app.tv.TvStage(this, display)
        stage.show()
        stage.root.addView(screen, android.widget.FrameLayout.LayoutParams(-1, -1))
        stage.setOnDismissListener { if (tv === stage) leaveTv() }
        tv = stage
        val pad = com.safewatch.app.tv.RemotePad(this, tvTarget, display.name) { wantsTv = false; leaveTv() }
        parent.addView(pad, index, params)
        remote = pad
        findViewById<View>(R.id.bar).visibility = View.GONE
        player.play()
        ui.postDelayed({ if (tv != null) playerView.showController() }, 600)
    }

    private fun leaveTv() {
        val stage = tv ?: return
        tv = null
        val screen = findViewById<View>(R.id.screen)
        (screen.parent as? android.view.ViewGroup)?.removeView(screen)
        val pad = remote
        remote = null
        val parent = pad?.parent as? android.view.ViewGroup
        if (pad != null && parent != null) {
            val index = parent.indexOfChild(pad)
            val params = pad.layoutParams
            parent.removeView(pad)
            parent.addView(screen, index, params)
        }
        try { if (stage.isShowing) stage.dismiss() } catch (e: Exception) { /* the TV went away first */ }
        findViewById<View>(R.id.bar).visibility = View.VISIBLE
    }

    private fun seekBy(ms: Long) {
        val to = (player.currentPosition + ms).coerceAtLeast(0)
        player.seekTo(if (player.duration > 0) minOf(to, player.duration) else to)
        playerView.showController()
    }

    private val tvTarget = object : com.safewatch.app.tv.TvTarget {
        override val prefersPointer = false
        override fun arrow(keyCode: Int) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10_000)
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10_000)
                else -> playerView.showController()
            }
        }
        override fun ok() = media("toggle")
        override fun back() { wantsTv = false; leaveTv() }
        override fun home() { if (com.safewatch.app.tv.TvMode.active) com.safewatch.app.tv.TvModeActivity.open(this@PlayerActivity) else { wantsTv = false; leaveTv() } }
        override fun type() = Ui.toast(this@PlayerActivity, "Nothing to type here")
        override fun media(command: String) {
            when {
                command == "toggle" -> { if (player.isPlaying) player.pause() else player.play(); playerView.showController() }
                command.startsWith("skip:") -> seekBy((command.removePrefix("skip:").toLongOrNull() ?: 0) * 1000)
            }
        }
        override fun pointer(dx: Float, dy: Float) {}
        override fun click() = media("toggle")
        // The scroll strip: every so far along skips ten seconds.
        private var carry = 0f
        override fun scroll(dy: Float) {
            carry += dy
            val step = Ui.dp(this@PlayerActivity, 120)
            while (carry > step) { carry -= step; seekBy(10_000) }
            while (carry < -step) { carry += step; seekBy(-10_000) }
        }
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
        private const val TICK_MS = 40L
        private const val CHECK_EVERY_MS = 200L
        private const val LIVE_HOLD_MS = 1500L
        private const val SCAN_STEP_MS = 1000L

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TV = "tv"

        /** Plays a scrubbed copy, already cleaned: on this phone, or with [onTv], on the TV in edenTV mode with the phone as the remote. */
        fun openCopy(ctx: android.content.Context, file: java.io.File, title: String, onTv: Boolean = false) = ctx.startActivity(
            android.content.Intent(ctx, PlayerActivity::class.java).setData(Uri.fromFile(file)).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TV, onTv))
    }
}
