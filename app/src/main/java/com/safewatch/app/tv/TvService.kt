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
    private var server: FileServer? = null
    private var awake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var servingSince = 0L
    private var servingFile: File? = null
    private var remote: TvYouTubeRemote? = null
    private var remoteRound = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CleanCopy.deleteOld(this)
        when (intent?.action) {
            ACTION_PREPARE -> prepare(CleanSource.fromJson(intent.getStringExtra(EXTRA_SOURCE) ?: return START_NOT_STICKY))
            ACTION_CANCEL -> making?.cancelled = true
            ACTION_PLAY -> play(File(intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY), intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                TvDevice(TvDevice.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "DLNA"), intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    intent.getStringExtra(EXTRA_LOCATION).orEmpty(), intent.getStringExtra(EXTRA_CONTROL).orEmpty()))
            ACTION_PAUSE -> pauseOrResume()
            ACTION_STOP -> stopPlaying(tellTv = true)
            ACTION_YOUTUBE -> startYouTube(intent)
            ACTION_YOUTUBE_STOP -> stopYouTube(null)
        }
        return START_NOT_STICKY
    }

    // ---- Making a clean copy ----

    private fun prepare(source: CleanSource) {
        if (making != null) return
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
                FilterLog.add("clean copy made: ${made.summary}")
                Log.i("SafeWatch", "clean copy made: ${made.summary}")
                TvState.Job(source.title, "Ready", 100, made = made)
            } catch (e: Exception) {
                Log.i("SafeWatch", "clean copy failed", e)
                FilterLog.add("clean copy not made: ${e.message}")
                Log.i("SafeWatch", "clean copy not made: ${e.message}")
                TvState.Job(source.title, "Not made", -1, error = e.message ?: "Something went wrong")
            }
            ui.post {
                making = null
                TvState.job = result
                TvState.changed()
                refresh()
                if (result.made != null) notifyDone(result)
                finishIfIdle()
            }
        }
    }

    // ---- Playing on the TV ----

    private fun play(file: File, title: String, device: TvDevice) {
        stopPlaying(tellTv = false)
        val address = TvFinder.phoneAddress(this)
        if (address == null) {
            // Started as a foreground service, it has to show itself before it may stop.
            val note = Notification.Builder(this, channel(this)).setSmallIcon(R.drawable.ic_cast).setContentTitle("EdenOS").build()
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
                ui.post {
                    stopPlaying(tellTv = false)
                    TvState.job = TvState.Job(title, "Not sent", -1, error = "${device.name} did not accept the video: ${e.message}")
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
        val finished = server
        server?.stop()
        server = null
        // A clean copy is for one viewing: once the TV has played it through, it is deleted.
        val file = servingFile
        servingFile = null
        if (finished != null && file != null && finished.readShare > 0.9) {
            CleanCopy.delete(file)
            FilterLog.add("clean copy played through and deleted")
        }
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
        }, "EdenOS YouTube on TV").start()
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
    private fun finishIfIdle() {
        ui.post {
            if (making != null || server != null || remote != null) return@post
            try { awake?.release() } catch (e: Exception) { /* already released */ }
            awake = null
            try { wifiLock?.release() } catch (e: Exception) { /* already released */ }
            wifiLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refresh() {
        if (making == null && server == null && remote == null) return
        val text = when {
            server != null -> (if (TvState.paused) "Paused on " else "Playing on ") + (TvState.playingOn?.name ?: "TV")
            making == null && remote != null -> TvState.youtubeStatus
            else -> TvState.job?.let { it.step + if (it.percent >= 0) " ${it.percent}%" else "" } ?: "Working"
        }
        val title = when {
            server != null -> TvState.playingTitle ?: "EdenOS"
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
        if (server == null && making != null) builder.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        if (server != null) {
            builder.addAction(Notification.Action.Builder(null, if (TvState.paused) "Play" else "Pause", action(ACTION_PAUSE)).build())
            builder.addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP)).build())
        } else if (making != null) {
            builder.addAction(Notification.Action.Builder(null, "Stop", action(ACTION_CANCEL)).build())
        }
        if (remote != null) builder.addAction(Notification.Action.Builder(null, "Stop filtering YouTube", action(ACTION_YOUTUBE_STOP)).build())
        var types = 0
        if (making != null) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (server != null || remote != null) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, builder.build(), types) else startForeground(NOTE_ID, builder.build())
        } catch (e: Exception) {
            Log.i("SafeWatch", "could not show the TV notification", e)
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
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private const val ACTION_YOUTUBE = "youtube"
        private const val ACTION_YOUTUBE_STOP = "youtubeStop"
        private const val EXTRA_SOURCE = "source"
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

        fun prepare(ctx: Context, source: CleanSource) =
            start(ctx, Intent(ctx, TvService::class.java).setAction(ACTION_PREPARE).putExtra(EXTRA_SOURCE, source.toJson()))

        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, TvService::class.java).setAction(ACTION_CANCEL))

        fun play(ctx: Context, copy: CleanCopyFile, device: TvDevice) = start(ctx, Intent(ctx, TvService::class.java).setAction(ACTION_PLAY)
            .putExtra(EXTRA_FILE, copy.file.absolutePath).putExtra(EXTRA_TITLE, copy.title).putExtra(EXTRA_KIND, device.kind.name)
            .putExtra(EXTRA_NAME, device.name).putExtra(EXTRA_LOCATION, device.location).putExtra(EXTRA_CONTROL, device.controlUrl))

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
