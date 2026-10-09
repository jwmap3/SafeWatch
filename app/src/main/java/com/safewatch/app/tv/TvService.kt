package com.safewatch.app.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.safewatch.app.R
import com.safewatch.app.data.FilterLog
import com.safewatch.app.data.Prefs
import com.safewatch.core.tv.FileServer
import com.safewatch.core.tv.LoungeClient
import com.safewatch.core.tv.LoungeScreen
import com.safewatch.core.tv.Tv
import com.safewatch.core.tv.TvDevice
import com.safewatch.core.tv.TvYouTubeFollower
import com.safewatch.core.tv.TvYouTubeListener
import com.safewatch.core.tv.TvYouTubeRemote
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** What the TV work is doing, for the TV screen to show. Changes are announced on the main thread. */
object TvState {
    data class Job(val title: String, val step: String, val percent: Int, val error: String? = null, val made: CleanCopyFile? = null)

    @Volatile var job: Job? = null
    /** Whether the background work has answered since the last time something was asked of it. */
    @Volatile var serviceAnswered = false
    /** Copies waiting their turn, by title, after the one being made. */
    @Volatile var queued: List<String> = emptyList()
    @Volatile var playingTitle: String? = null
    @Volatile var playingOn: TvDevice? = null
    @Volatile var paused = false

    /** A YouTube video playing in the TV's own YouTube app, filtered from the phone. */
    @Volatile var youtubeTitle: String? = null
    @Volatile var youtubeOn: String? = null
    @Volatile var youtubeStatus: String = ""
    /** Why the last YouTube filtering ended, to show once. */
    @Volatile var youtubeEnded: String? = null

    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun changed() = main.post { listeners.forEach { it() } }

    /** The last copy that could not be made and why, kept until dismissed, so it is still shown after the app restarts. */
    fun lastFailure(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val title = p.getString("failedTitle", null) ?: return null
        return title to p.getString("failedWhy", "").orEmpty()
    }

    fun rememberFailure(ctx: Context, title: String, why: String) =
        ctx.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().putString("failedTitle", title).putString("failedWhy", why).apply()

    fun clearFailure(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().remove("failedTitle").remove("failedWhy").apply()
}

/**
 * Keeps the TV work going with the phone's screen off: making a clean copy, handing a clean copy
 * to the TV while it plays, and filtering a YouTube video playing in the TV's own YouTube app. The
 * TV fetches a clean copy from the phone as it goes, so the phone has to stay on the Wi-Fi, but it
 * can be locked and in a pocket.
 */
@UnstableApi
class TvService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var making: CleanCopy? = null
    private var foreground = false
    private val waiting = ArrayDeque<Pair<CleanSource, String?>>()
    private var server: FileServer? = null
    /** A website's video passed on to the TV through the phone while it plays in the TV's own player. */
    private var proxy: com.safewatch.core.tv.StreamProxy? = null
    @Volatile private var castRound = 0
    private var awake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var servingSince = 0L
    private var servingFile: File? = null
    private var remote: TvYouTubeRemote? = null
    private var remoteRound = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TvState.serviceAnswered = true
        FilterLog.add("TV work: ${intent?.action ?: "restarted"}")
        CleanCopy.deleteOld(this)
        when (intent?.action) {
            ACTION_PREPARE -> prepare(CleanSource.fromJson(intent.getStringExtra(EXTRA_SOURCE) ?: return START_NOT_STICKY), intent.getStringExtra(EXTRA_REPLACES))
            ACTION_CANCEL -> making?.cancelled = true
            ACTION_DROP -> intent.getStringExtra(EXTRA_TITLE)?.let { title ->
                waiting.removeAll { it.first.title == title }
                TvState.queued = waiting.map { it.first.title }
                TvState.changed()
                finishIfIdle()
            }
            ACTION_PLAY -> play(File(intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY), intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                TvDevice(TvDevice.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "DLNA"), intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    intent.getStringExtra(EXTRA_LOCATION).orEmpty(), intent.getStringExtra(EXTRA_CONTROL).orEmpty()))
            ACTION_CAST -> cast(CleanSource.fromJson(intent.getStringExtra(EXTRA_SOURCE) ?: return START_NOT_STICKY),
                TvDevice(TvDevice.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "DLNA"), intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    intent.getStringExtra(EXTRA_LOCATION).orEmpty(), intent.getStringExtra(EXTRA_CONTROL).orEmpty(),
                    intent.getStringExtra(EXTRA_RENDERING).orEmpty()))
            ACTION_PAUSE -> pauseOrResume()
            ACTION_STOP -> stopPlaying(tellTv = true)
            ACTION_YOUTUBE -> startYouTube(intent)
            ACTION_YOUTUBE_STOP -> stopYouTube(null)
        }
        return START_NOT_STICKY
    }

    // ---- Making a clean copy ----

    private fun prepare(source: CleanSource, replaces: String? = null) {
        if (making != null) {
            // One copy is made at a time; the rest wait their turn.
            if (waiting.none { it.first.address == source.address && it.first.superclean == source.superclean }) waiting += source to replaces
            TvState.queued = waiting.map { it.first.title }
            TvState.changed()
            return
        }
        val maker = CleanCopy(applicationContext) { step, percent ->
            TvState.job = TvState.Job(source.title, step, percent)
            TvState.changed()
            refresh()
        }
        making = maker
        TvState.job = TvState.Job(source.title, "Starting", -1)
        TvState.changed()
        hold()
        refresh()
        FilterLog.add("clean copy started: ${source.title}")
        worker.execute {
            val result = try {
                val made = maker.make(source)
                // Made again (with a Superclean): the new copy takes the old one's place.
                replaces?.let { CleanCopy.delete(File(it)) }
                FilterLog.add("clean copy made: ${made.summary}")
                Log.i("SafeWatch", "clean copy made: ${made.summary}")
                TvState.clearFailure(applicationContext)
                TvState.Job(source.title, "Ready", 100, made = made)
            } catch (e: Exception) {
                Log.i("SafeWatch", "clean copy failed", e)
                FilterLog.add("clean copy not made: ${e.message}")
                Log.i("SafeWatch", "clean copy not made: ${e.message}")
                if (e.message != "Stopped") TvState.rememberFailure(applicationContext, source.title, e.message ?: "Something went wrong")
                TvState.Job(source.title, "Not made", -1, error = e.message ?: "Something went wrong")
            }
            ui.post {
                making = null
                TvState.job = result
                TvState.changed()
                refresh()
                if (result.made != null) notifyDone(result)
                val next = waiting.removeFirstOrNull()
                TvState.queued = waiting.map { it.first.title }
                if (next != null) prepare(next.first, next.second) else finishIfIdle()
            }
        }
    }

    // ---- Playing on the TV ----

    private fun play(file: File, title: String, device: TvDevice) {
        stopPlaying(tellTv = false)
        val address = TvFinder.phoneAddress(this)
        if (address == null) {
            // Started as a foreground service, it has to show itself before it may stop.
            val note = Notification.Builder(this, channel(this)).setSmallIcon(R.drawable.ic_cast).setContentTitle("edenOS").build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTE_ID, note)
            TvState.job = TvState.Job(title, "Not sent", -1, error = "The phone is not on Wi-Fi")
            TvState.changed()
            finishIfIdle()
            return
        }
        val serving = FileServer(file).start(address)
        server = serving
        servingFile = file
        servingSince = System.currentTimeMillis()
        TvState.playingTitle = title
        TvState.playingOn = device
        TvState.paused = false
        TvState.changed()
        hold()
        refresh()
        control.execute {
            try {
                Tv.play(device, serving.url(address.hostAddress ?: ""), title)
                FilterLog.add("sent to ${device.name}: $title")
            } catch (e: Exception) {
                FilterLog.add("could not send to ${device.name}: ${e.message}")
                val why = "${device.name} did not accept the video (${e.message}). Make sure the phone and TV are on the same " +
                    "Wi-Fi, that the router does not separate devices (AP isolation / guest network), and allow edenOS on the TV if it asks."
                ui.post {
                    stopPlaying(tellTv = false)
                    TvState.rememberFailure(applicationContext, title, why)
                    TvState.job = TvState.Job(title, "Not sent", -1, error = why)
                    TvState.changed()
                }
            }
        }
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 60_000)
    }

    /** Stops handing out the file once the TV has clearly finished with it. */
    private val watchdog = object : Runnable {
        override fun run() {
            val serving = server ?: return
            val now = System.currentTimeMillis()
            val quiet = now - maxOf(serving.lastRequestAt, servingSince)
            if ((serving.requests == 0 && now - servingSince > 3 * 60_000) || quiet > IDLE_MS) {
                FilterLog.add(if (serving.requests == 0) "the TV never asked for the video" else "the TV finished with the video")
                stopPlaying(tellTv = false)
                return
            }
            ui.postDelayed(this, 60_000)
        }
    }

    // ---- Casting: the TV plays the video in its own player, and the phone keeps it clean ----

    /**
     * Has the TV play a video itself, at full quality, while the phone follows along: it mutes the TV for each
     * curse word and jumps past scenes found earlier. A website's video comes to the TV through the phone, fetched
     * as the site's player fetched it; a file on the phone is handed out by the phone.
     */
    private fun cast(source: CleanSource, device: TvDevice) {
        stopPlaying(tellTv = false)
        val address = TvFinder.phoneAddress(this)
        if (address == null) {
            val note = Notification.Builder(this, channel(this)).setSmallIcon(R.drawable.ic_cast).setContentTitle("edenOS").build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTE_ID, note)
            TvState.job = TvState.Job(source.title, "Not sent", -1, error = "The phone is not on Wi-Fi")
            TvState.changed()
            finishIfIdle()
            return
        }
        val phone = address.hostAddress ?: ""
        val round = ++castRound
        val videoUrl: String
        if (source.address.startsWith("file:")) {
            val serving = FileServer(File(android.net.Uri.parse(source.address).path ?: "")).start(address)
            server = serving
            servingSince = System.currentTimeMillis()
            videoUrl = serving.url(phone)
        } else {
            val passing = com.safewatch.core.tv.StreamProxy { url ->
                val headers = LinkedHashMap(source.requestHeaders(script = source.stream.isNotEmpty()))
                try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }?.let { headers["Cookie"] = it }
                headers
            }.start(address)
            proxy = passing
            videoUrl = passing.url(phone, source.address)
        }
        TvState.playingTitle = source.title
        TvState.playingOn = device
        TvState.paused = false
        TvState.changed()
        hold()
        refresh()
        FilterLog.add("casting to ${device.name}: ${source.title}")
        control.execute {
            try {
                when (device.kind) {
                    TvDevice.Kind.ROKU -> com.safewatch.core.tv.Roku.play(device, videoUrl, source.title,
                        when (source.stream) { CleanSource.HLS -> "hls"; CleanSource.DASH -> "dash"; else -> "mp4" })
                    TvDevice.Kind.DLNA -> com.safewatch.core.tv.Dlna.play(device, videoUrl, source.title,
                        when (source.stream) { CleanSource.HLS -> "application/vnd.apple.mpegurl"; CleanSource.DASH -> "application/dash+xml"
                            else -> if (source.address.contains(".webm", true)) "video/webm" else "video/mp4" })
                }
                FilterLog.add("${device.name} is playing it")
            } catch (e: Exception) {
                FilterLog.add("could not cast to ${device.name}: ${e.message}")
                ui.post {
                    if (round != castRound) return@post
                    stopPlaying(tellTv = false)
                    TvState.rememberFailure(applicationContext, source.title, "${device.name} did not accept the video: ${e.message}")
                    TvState.job = TvState.Job(source.title, "Not sent", -1, error = "${device.name} did not accept the video: ${e.message}")
                    TvState.changed()
                }
                return@execute
            }
            follow(source, device, round)
        }
    }

    /** Keeps the TV clean while it plays: asks where it is about once a second and mutes or jumps as needed. */
    private fun follow(source: CleanSource, device: TvDevice, round: Int) {
        val settings = Prefs.settings(this)
        val tags = ArrayList(com.safewatch.app.data.TagStore.load(this, source.key))
        val cues = source.captions.flatMap { CleanCopy.readCaptions(it) }
        if (settings.language != com.safewatch.core.Strictness.OFF) tags += com.safewatch.core.CueTagger.tagsFor(cues, com.safewatch.core.ProfanityMatcher(settings))
        val active = com.safewatch.core.FilterEngine(tags, settings).activeTags
        // The TV cannot blur, so scenes that would be blurred are jumped past instead.
        val follower = com.safewatch.core.tv.CastFollower(
            active.filter { it.action == com.safewatch.core.Action.MUTE }.map { it.startMs..it.endMs },
            active.filter { it.action != com.safewatch.core.Action.MUTE }.map { it.startMs..it.endMs })
        FilterLog.add("keeping the TV clean: ${cues.size} caption lines, ${active.count { it.action == com.safewatch.core.Action.MUTE }} " +
            "places to mute, ${active.count { it.action != com.safewatch.core.Action.MUTE }} scenes to jump past")
        var known = -1L
        var knownAt = 0L
        var askedAt = 0L
        var rokuMuted = false
        var misses = 0
        while (round == castRound && TvState.playingOn == device) {
            val now = System.currentTimeMillis()
            if (now - askedAt >= 1000) {
                askedAt = now
                val at = try {
                    when (device.kind) {
                        TvDevice.Kind.ROKU -> com.safewatch.core.tv.Roku.position(device)
                        TvDevice.Kind.DLNA -> com.safewatch.core.tv.Dlna.position(device)
                    }
                } catch (e: Exception) { null }
                if (at != null && at > 0) { known = at; knownAt = System.currentTimeMillis(); misses = 0 } else misses++
                if (misses > 120) { FilterLog.add("${device.name} stopped answering"); break }
            }
            if (known >= 0 && !TvState.paused) {
                val estimate = known + (System.currentTimeMillis() - knownAt)
                for (command in follower.at(estimate)) {
                    try {
                        when (command) {
                            is com.safewatch.core.tv.CastFollower.Command.Mute -> when (device.kind) {
                                TvDevice.Kind.DLNA -> com.safewatch.core.tv.Dlna.mute(device, command.on)
                                // A Roku's mute button switches the sound off and on, so it is pressed only when needed.
                                TvDevice.Kind.ROKU -> if (rokuMuted != command.on) { com.safewatch.core.tv.Roku.press(device, "VolumeMute"); rokuMuted = command.on }
                            }
                            is com.safewatch.core.tv.CastFollower.Command.Seek -> if (device.kind == TvDevice.Kind.DLNA) {
                                com.safewatch.core.tv.Dlna.seek(device, command.toMs)
                                known = command.toMs
                                knownAt = System.currentTimeMillis()
                            }
                        }
                    } catch (e: Exception) {
                        Log.i("SafeWatch", "cast control: ${e.message}")
                    }
                }
            }
            Thread.sleep(200)
        }
        if (rokuMuted) try { com.safewatch.core.tv.Roku.press(device, "VolumeMute") } catch (e: Exception) { /* gone */ }
        if (device.kind == TvDevice.Kind.DLNA && follower.muted) try { com.safewatch.core.tv.Dlna.mute(device, false) } catch (e: Exception) { /* gone */ }
        // The TV stopped answering (turned off, or the video ended long ago): the phone stops passing the video on.
        ui.post { if (round == castRound) stopPlaying(tellTv = false) }
    }

    private fun pauseOrResume() {
        val device = TvState.playingOn ?: return
        val pause = !TvState.paused
        TvState.paused = pause
        TvState.changed()
        refresh()
        control.execute { try { Tv.pauseOrResume(device, pause) } catch (e: Exception) { /* the TV's own remote still works */ } }
    }

    private fun stopPlaying(tellTv: Boolean) {
        val device = TvState.playingOn
        if (tellTv && device != null) control.execute { try { Tv.stop(device) } catch (e: Exception) { /* already stopped */ } }
        ui.removeCallbacks(watchdog)
        castRound++
        proxy?.stop()
        proxy = null
        server?.stop()
        server = null
        // Copies are kept now (Settings decides for how long), so playing one through no longer deletes it.
        servingFile = null
        TvState.playingTitle = null
        TvState.playingOn = null
        TvState.paused = false
        TvState.changed()
        refresh()
        finishIfIdle()
    }

    // ---- YouTube in the TV's own app ----

    private fun startYouTube(intent: Intent) {
        // A video already being filtered on the TV gives way to the new one.
        remote?.let { old -> remoteRound++; old.stop(); remote = null }
        val screen = LoungeScreen(intent.getStringExtra(EXTRA_SCREEN) ?: return, intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
            intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { "TV" })
        val videoId = intent.getStringExtra(EXTRA_VIDEO) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "YouTube video" }
        val follower = TvYouTubeFollower(videoId, YouTubeTv.rangesFromJson(intent.getStringExtra(EXTRA_MUTE)),
            YouTubeTv.rangesFromJson(intent.getStringExtra(EXTRA_SKIPS)))
        val round = ++remoteRound
        TvState.youtubeTitle = title
        TvState.youtubeOn = screen.name
        TvState.youtubeStatus = "Connecting to ${screen.name}…"
        TvState.youtubeEnded = null
        TvState.changed()
        val r = TvYouTubeRemote(LoungeClient(), screen, follower, intent.getLongExtra(EXTRA_AT, 0),
            hideCaptions = !Prefs.showCaptions(this),
            listener = object : TvYouTubeListener {
                override fun status(text: String) {
                    ui.post {
                        if (round != remoteRound) return@post
                        TvState.youtubeStatus = text
                        TvState.changed()
                        refresh()
                    }
                }

                override fun finished(reason: String) {
                    ui.post {
                        if (round != remoteRound) return@post
                        FilterLog.add("YouTube on TV ended: $reason")
                        endYouTube(reason)
                    }
                }
            },
            onNewToken = { YouTubeTv.save(applicationContext, it) })
        remote = r
        hold()
        refresh()
        FilterLog.add("YouTube on TV: $title on ${screen.name}, ${follower.timeline.count} stretches to mute")
        Thread({
            try {
                r.run()
            } catch (e: Exception) {
                Log.i("SafeWatch", "YouTube on TV stopped", e)
                ui.post { if (round == remoteRound) endYouTube("Stopped: ${e.message}") }
            }
        }, "edenOS YouTube on TV").start()
    }

    private fun stopYouTube(reason: String?) {
        val r = remote ?: return
        remoteRound++
        r.stop()
        endYouTube(reason)
    }

    private fun endYouTube(reason: String?) {
        remote?.stop()
        remote = null
        TvState.youtubeTitle = null
        TvState.youtubeOn = null
        TvState.youtubeStatus = ""
        TvState.youtubeEnded = reason
        TvState.changed()
        if (reason != null) notifyEnded(reason)
        refresh()
        finishIfIdle()
    }

    private fun notifyEnded(reason: String) {
        val note = Notification.Builder(this, channel(this))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("YouTube on TV")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { getSystemService(NotificationManager::class.java).notify(ENDED_ID, note) } catch (e: SecurityException) { /* notifications not allowed */ }
    }

    // ---- Staying awake, and the notification that says so ----

    private fun hold() {
        if (awake == null) {
            awake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeWatch:tv").apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SafeWatch:tv").apply { setReferenceCounted(false); acquire() }
        }
    }

    /**
     * Stops the service once nothing is left to do. Looked at a moment later, so that work which ends
     * one thing to start the next (a new video for the TV) does not stop the service in between.
     */
    /** Android 15 and later: the daily allowance for background work ran out while a copy was being made. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val title = TvState.job?.title ?: "Video"
        making?.cancelled = true
        TvState.rememberFailure(applicationContext, title, "Android's daily allowance for working in the background ran out " +
            "before the copy was finished. Try again later, with edenOS open.")
        Log.i("SafeWatch", "background time ran out")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishIfIdle() {
        ui.post {
            if (making != null || server != null || proxy != null || remote != null) return@post
            try { awake?.release() } catch (e: Exception) { /* already released */ }
            awake = null
            try { wifiLock?.release() } catch (e: Exception) { /* already released */ }
            wifiLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
    }

    private fun refresh() {
        if (making == null && server == null && proxy == null && remote == null) return
        val text = when {
            server != null || proxy != null -> (if (TvState.paused) "Paused on " else "Playing on ") + (TvState.playingOn?.name ?: "TV")
            making == null && remote != null -> TvState.youtubeStatus
            else -> TvState.job?.let { it.step + if (it.percent >= 0) " ${it.percent}%" else "" } ?: "Working"
        }
        val title = when {
            server != null || proxy != null -> TvState.playingTitle ?: "edenOS"
            making == null && remote != null -> TvState.youtubeTitle ?: "YouTube on TV"
            else -> "Clean copy: ${TvState.job?.title ?: ""}"
        }
        val builder = Notification.Builder(this, channel(this))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        val percent = TvState.job?.percent ?: -1
        if (server == null && proxy == null && making != null) builder.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        if (server != null || proxy != null) {
            builder.addAction(Notification.Action.Builder(null, if (TvState.paused) "Play" else "Pause", action(ACTION_PAUSE)).build())
            builder.addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP)).build())
        } else if (making != null) {
            builder.addAction(Notification.Action.Builder(null, "Stop", action(ACTION_CANCEL)).build())
        }
        if (remote != null) builder.addAction(Notification.Action.Builder(null, "Stop filtering YouTube", action(ACTION_YOUTUBE_STOP)).build())
        val playing = if (server != null || proxy != null || remote != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        // Making a copy is media processing to Android 15 and later, and a data sync before; each has its own daily
        // allowance, so if one is refused the other is tried. If Android refuses both, the copy stops with a reason.
        val kinds = if (making == null) listOf(0) else if (Build.VERSION.SDK_INT >= 35) listOf(MEDIA_PROCESSING, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        var refused: Exception? = null
        for (kind in kinds) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, builder.build(), kind or playing)
                else startForeground(NOTE_ID, builder.build())
                foreground = true
                return
            } catch (e: Exception) {
                Log.i("SafeWatch", "Android refused to let the TV work carry on in the background", e)
                refused = e
            }
        }
        if (making != null && !foreground) {
            making?.cancelled = true
            val why = "Android would not let edenOS keep working in the background (${refused?.message ?: "no reason given"}). " +
                "Open edenOS and try again; if it keeps happening, restart the phone."
            TvState.rememberFailure(applicationContext, TvState.job?.title ?: "Video", why)
            TvState.job = TvState.Job(TvState.job?.title ?: "Video", "Not made", -1, error = why)
            TvState.changed()
        }
    }

    private fun notifyDone(job: TvState.Job) {
        val note = Notification.Builder(this, channel(this))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Clean copy ready")
            .setContentText("${job.title}: ${job.made?.summary.orEmpty()}")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { getSystemService(NotificationManager::class.java).notify(DONE_ID, note) } catch (e: SecurityException) { /* notifications not allowed */ }
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(this, name.hashCode(), Intent(this, TvService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        server?.stop()
        server = null
        remote?.stop()
        remote = null
        making?.cancelled = true
        worker.shutdown()
        control.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PREPARE = "prepare"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_DROP = "drop"
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_CAST = "cast"
        private const val EXTRA_RENDERING = "rendering"
        private const val ACTION_STOP = "stop"
        private const val ACTION_YOUTUBE = "youtube"
        private const val ACTION_YOUTUBE_STOP = "youtubeStop"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_REPLACES = "replaces"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_CONTROL = "control"
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_AT = "at"
        private const val EXTRA_MUTE = "mute"
        private const val EXTRA_SKIPS = "skips"
        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, from Android 15. */
        private const val MEDIA_PROCESSING = 1 shl 13
        private const val NOTE_ID = 7
        private const val DONE_ID = 8
        private const val ENDED_ID = 9
        private const val IDLE_MS = 30 * 60_000L

        private fun channel(ctx: Context): String {
            val id = "tv"
            val manager = ctx.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(NotificationChannel(id, "TV", NotificationManager.IMPORTANCE_LOW))
            }
            return id
        }

        private fun start(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun prepare(ctx: Context, source: CleanSource, replaces: String? = null) =
            start(ctx, Intent(ctx, TvService::class.java).setAction(ACTION_PREPARE).putExtra(EXTRA_SOURCE, source.toJson())
                .putExtra(EXTRA_REPLACES, replaces))

        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_CANCEL))

        /** Takes a copy that is waiting its turn off the list. */
        fun drop(ctx: Context, title: String) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_DROP).putExtra(EXTRA_TITLE, title))

        fun play(ctx: Context, copy: CleanCopyFile, device: TvDevice) = start(ctx, Intent(ctx, TvService::class.java).setAction(ACTION_PLAY)
            .putExtra(EXTRA_FILE, copy.file.absolutePath).putExtra(EXTRA_TITLE, copy.title).putExtra(EXTRA_KIND, device.kind.name)
            .putExtra(EXTRA_NAME, device.name).putExtra(EXTRA_LOCATION, device.location).putExtra(EXTRA_CONTROL, device.controlUrl))

        /** Plays [source] in the TV's own player, at full quality, with the phone keeping it clean. */
        fun cast(ctx: Context, source: CleanSource, device: TvDevice) = start(ctx, Intent(ctx, TvService::class.java).setAction(ACTION_CAST)
            .putExtra(EXTRA_SOURCE, source.toJson()).putExtra(EXTRA_KIND, device.kind.name).putExtra(EXTRA_NAME, device.name)
            .putExtra(EXTRA_LOCATION, device.location).putExtra(EXTRA_CONTROL, device.controlUrl).putExtra(EXTRA_RENDERING, device.renderingUrl))

        fun pauseOrResume(ctx: Context) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_PAUSE))

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_STOP))

        /** Plays a YouTube video in the TV's own YouTube app and filters it from the phone. */
        fun youtube(ctx: Context, video: YouTubeTv.Video, screen: LoungeScreen) = start(ctx, Intent(ctx, TvService::class.java)
            .setAction(ACTION_YOUTUBE).putExtra(EXTRA_SCREEN, screen.screenId).putExtra(EXTRA_TOKEN, screen.token)
            .putExtra(EXTRA_NAME, screen.name).putExtra(EXTRA_VIDEO, video.id).putExtra(EXTRA_TITLE, video.title)
            .putExtra(EXTRA_AT, video.atMs).putExtra(EXTRA_MUTE, YouTubeTv.rangesToJson(video.mute))
            .putExtra(EXTRA_SKIPS, YouTubeTv.rangesToJson(video.skips)))

        fun stopYouTube(ctx: Context) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_YOUTUBE_STOP))
    }
}
