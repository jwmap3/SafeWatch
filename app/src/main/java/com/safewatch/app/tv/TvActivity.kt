package com.safewatch.app.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.safewatch.app.R
import com.safewatch.app.ui.Ui
import com.safewatch.core.tv.TvDevice
import org.json.JSONObject

/**
 * The TV screen: clean copies being made and made, sending one to a TV, and what is
 * playing on the TV now.
 */
@UnstableApi
class TvActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var body: LinearLayout
    private val listener: () -> Unit = { if (!isDestroyed) show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, col) = Ui.page(this, ownWindow = true)
        column = col
        column.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)).apply { topMargin = Ui.dp(context, 8) }
        })
        column.addView(Ui.largeTitle(this, "TV"))
        column.addView(Ui.subtitle(this,
            "Two ways to watch on the TV with your phone locked: YouTube plays in the TV's own YouTube app with edenOS muting " +
                "it from the phone, and other videos go as a clean copy, the video with the filtering built in."))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
        setContentView(page)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        CleanCopy.deleteOld(this)
        TvState.listeners += listener
        show()
    }

    override fun onPause() {
        TvState.listeners -= listener
        super.onPause()
    }

    private fun show() {
        body.removeAllViews()
        showYouTube()
        val playing = TvState.playingOn
        if (playing != null) {
            body.addView(Ui.sectionHeader(this, "On the TV"))
            body.addView(Ui.card(this).apply {
                addView(Ui.row(this@TvActivity, TvState.playingTitle ?: "Video", playing.name, chevron = false))
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, if (TvState.paused) "Play" else "Pause", chevron = false) { TvService.pauseOrResume(this@TvActivity) })
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, "Stop", chevron = false) { TvService.stop(this@TvActivity) })
            })
            body.addView(Ui.caption(this, "Pause, rewind and skip with the TV's own remote too."))
        }

        val job = TvState.job
        if (job != null && job.made == null) {
            body.addView(Ui.sectionHeader(this, if (job.error == null) "Making a clean copy" else "Clean copy"))
            body.addView(Ui.card(this).apply {
                addView(Ui.row(this@TvActivity, job.title, chevron = false))
                addView(Ui.divider(this@TvActivity))
                if (job.error == null) {
                    addView(TextView(context).apply {
                        text = job.step + if (job.percent >= 0) "  ${job.percent}%" else ""
                        textSize = 15f
                        setTextColor(Ui.color(context, R.color.text_secondary))
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 4))
                    })
                    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = job.percent < 0
                        max = 100
                        progress = job.percent.coerceAtLeast(0)
                        setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), Ui.dp(context, 10))
                    })
                    addView(Ui.divider(this@TvActivity))
                    addView(Ui.row(this@TvActivity, "Stop making it", chevron = false) { TvService.cancel(this@TvActivity) })
                } else {
                    addView(TextView(context).apply {
                        text = job.error
                        textSize = 15f
                        setTextColor(Ui.color(context, R.color.text))
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                    })
                    addView(Ui.divider(this@TvActivity))
                    addView(Ui.row(this@TvActivity, "Dismiss", chevron = false) { TvState.job = null; show() })
                }
            })
            if (job.error == null) body.addView(Ui.caption(this,
                "This carries on with the screen off. A full film can take an hour or more: every picture is checked, and the video is written again."))
        }

        val copies = CleanCopy.all(this)
        body.addView(Ui.sectionHeader(this, "Clean copies"))
        body.addView(Ui.caption(this,
            "Your Roku or smart TV plays a clean copy by itself. Keep the phone on the Wi-Fi: the TV fetches the video from it. " +
                "Each copy is for one viewing: it deletes itself once it has played through, or after a day."))
        if (copies.isEmpty()) {
            body.addView(Ui.caption(this,
                "None yet. While watching a video file or a website's video, tap Send to TV, then Clean copy to TV or Superclean to TV. " +
                    "Netflix, HBO Max and the other paid services lock their videos, so use Mirror to TV for those."))
            return
        }
        body.addView(Ui.card(this).apply {
            copies.forEachIndexed { i, copy ->
                if (i > 0) addView(Ui.divider(this@TvActivity))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                    addView(TextView(context).apply {
                        text = copy.title
                        textSize = 17f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Ui.color(context, R.color.text))
                    })
                    addView(TextView(context).apply {
                        text = copy.summary
                        textSize = 13f
                        setTextColor(Ui.color(context, R.color.text_secondary))
                        setPadding(0, Ui.dp(context, 2), 0, 0)
                    })
                    addView(TextView(context).apply {
                        text = Ui.time(copy.durationMs) + "  ·  " + copy.sizeText + "  ·  " +
                            DateUtils.getRelativeTimeSpanString(copy.madeAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        textSize = 13f
                        setTextColor(Ui.color(context, R.color.text_secondary))
                    })
                    setOnClickListener { choose(copy) }
                })
            }
        })
    }

    /** YouTube in the TV's own app: what is playing, and the TV it is linked to. */
    private fun showYouTube() {
        val title = TvState.youtubeTitle
        if (title != null) {
            body.addView(Ui.sectionHeader(this, "YouTube on ${TvState.youtubeOn ?: "the TV"}"))
            body.addView(Ui.card(this).apply {
                addView(Ui.row(this@TvActivity, title, chevron = false))
                addView(Ui.divider(this@TvActivity))
                addView(TextView(context).apply {
                    text = TvState.youtubeStatus
                    textSize = 15f
                    setTextColor(Ui.color(context, R.color.text_secondary))
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                })
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, "Stop filtering", chevron = false) { TvService.stopYouTube(this@TvActivity) })
            })
            body.addView(Ui.caption(this,
                "Use the TV's own remote to pause, rewind or change the volume. If a different video is started on the TV, " +
                    "edenOS stops filtering, since it has not read that video's captions."))
        } else TvState.youtubeEnded?.let { reason ->
            body.addView(Ui.sectionHeader(this, "YouTube on the TV"))
            body.addView(Ui.card(this).apply {
                addView(TextView(context).apply {
                    text = reason
                    textSize = 15f
                    setTextColor(Ui.color(context, R.color.text))
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                })
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, "Dismiss", chevron = false) { TvState.youtubeEnded = null; show() })
            })
        }
        val linked = YouTubeTv.linked(this)
        body.addView(Ui.sectionHeader(this, "Your TV's YouTube"))
        body.addView(Ui.card(this).apply {
            if (linked == null) {
                addView(Ui.row(this@TvActivity, "Link with TV code", "Not linked") { YouTubeTv.link(this@TvActivity) { show() } })
            } else {
                addView(Ui.row(this@TvActivity, linked.name, "Linked", chevron = false))
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, "Link a different TV") { YouTubeTv.link(this@TvActivity) { show() } })
                addView(Ui.divider(this@TvActivity))
                addView(Ui.row(this@TvActivity, "Unlink", chevron = false) {
                    AlertDialog.Builder(this@TvActivity).setMessage("Unlink ${linked.name}?")
                        .setPositiveButton("Unlink") { _, _ -> YouTubeTv.unlink(this@TvActivity); show() }
                        .setNegativeButton("Cancel", null).show()
                })
            }
        })
        body.addView(Ui.caption(this,
            "On the TV, open YouTube, then Settings > Link with TV code. Then, while a YouTube video plays in edenOS, tap " +
                "Send to TV > YouTube on TV, or tap Play on TV on a video's page."))
    }

    private fun choose(copy: CleanCopyFile) {
        val last = lastTv(this)
        val busy = TvState.job?.let { it.made == null && it.error == null } == true
        val again = copy.source?.takeIf { !busy }
        val options = listOfNotNull(last?.let { "Play on ${it.name}" }, "Play on a TV…", again?.let { "Superclean this" }, "Delete")
        AlertDialog.Builder(this)
            .setTitle(copy.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Play on a TV…" -> pickTv(copy)
                    "Superclean this" -> again?.let { SupercleanActivity.open(this, it, replaces = copy.file.absolutePath) }
                    "Delete" -> AlertDialog.Builder(this).setMessage("Delete the clean copy of ${copy.title}?")
                        .setPositiveButton("Delete") { _, _ -> CleanCopy.delete(copy); show() }
                        .setNegativeButton("Cancel", null).show()
                    else -> last?.let { send(copy, it) }
                }
            }
            .show()
    }

    private fun pickTv(copy: CleanCopyFile) = findTv(this) { send(copy, it) }

    private fun send(copy: CleanCopyFile, device: TvDevice) {
        rememberTv(this, device)
        TvService.play(this, copy, device)
        Ui.toast(this, "Sending to ${device.name}. A Samsung or LG TV may ask you to allow it.")
        askToRunWithScreenOff()
    }

    /**
     * Some phones stop apps that work with the screen off to save battery, which would cut the TV off
     * mid-film. Asked once: the viewer can let edenOS run without that limit.
     */
    private fun askToRunWithScreenOff() {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName) || prefs(this).getBoolean("askedBattery", false)) return
        prefs(this).edit().putBoolean("askedBattery", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep playing with the screen off")
            .setMessage("So the TV is not cut off mid-film when your phone locks, let edenOS run without battery limits.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    @Suppress("BatteryLife")
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, TvActivity::class.java))

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

        fun rememberTv(ctx: Context, d: TvDevice) = prefs(ctx).edit().putString("last", JSONObject()
            .put("kind", d.kind.name).put("name", d.name).put("location", d.location).put("control", d.controlUrl)
            .put("rendering", d.renderingUrl).toString()).apply()

        /** Looks for Rokus and smart TVs on the Wi-Fi and lets the viewer pick one. */
        fun findTv(activity: android.app.Activity, then: (TvDevice) -> Unit) {
            val wait = AlertDialog.Builder(activity).setTitle("Looking for TVs…")
                .setMessage("Rokus and smart TVs on this Wi-Fi.").setNegativeButton("Cancel", null).show()
            Thread {
                val found = try { TvFinder.find(activity.applicationContext) } catch (e: Exception) { emptyList() }
                activity.runOnUiThread {
                    if (activity.isDestroyed || !wait.isShowing) return@runOnUiThread
                    wait.dismiss()
                    if (found.isEmpty()) {
                        AlertDialog.Builder(activity).setTitle("No TV found")
                            .setMessage("Make sure the TV is on and on the same Wi-Fi as this phone.\n\n" +
                                "Roku: Settings > System > Advanced system settings > Control by mobile apps > Network access, set to Default or Permissive.\n\n" +
                                "Samsung and LG: the TV may show a message asking whether to allow this phone; choose Allow. If it said no " +
                                "before, look in the TV's settings for connected or mobile devices and allow it there.")
                            .setPositiveButton("Search again") { _, _ -> findTv(activity, then) }
                            .setNegativeButton("Close", null).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(activity).setTitle("Play on")
                        .setItems(found.map { it.name + if (it.kind == TvDevice.Kind.ROKU) " (Roku)" else "" }.toTypedArray()) { _, which ->
                            rememberTv(activity, found[which])
                            then(found[which])
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.start()
        }

        fun lastTv(ctx: Context): TvDevice? = try {
            val o = JSONObject(prefs(ctx).getString("last", null) ?: return null)
            TvDevice(TvDevice.Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("location"), o.optString("control"), o.optString("rendering"))
        } catch (e: Exception) { null }
    }
}
