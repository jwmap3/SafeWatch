package com.safewatch.app.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
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
            if (job.error == null) body.addView(Ui.caption(this, "Keeps going with the screen off."))
        }

        val copies = CleanCopy.all(this)
        body.addView(Ui.sectionHeader(this, "Your Scrubbed Movies"))
        if (copies.isEmpty()) {
            body.addView(Ui.caption(this, "None yet. Tap Superclean while a movie plays."))
            return
        }
        CleanCopy.makeMissingThumbs(this, copies) { if (!isDestroyed) show() }
        body.addView(Ui.card(this).apply {
            copies.forEachIndexed { i, copy ->
                if (i > 0) addView(Ui.divider(this@TvActivity))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                    // The cover picture made from the video.
                    addView(android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 8).toFloat())
                        copy.thumb?.let { com.safewatch.app.ui.Images.load(it.absolutePath, this) }
                    }, LinearLayout.LayoutParams(Ui.dp(context, 96), Ui.dp(context, 54)).apply { marginEnd = Ui.dp(context, 12) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply {
                            text = copy.title
                            textSize = 17f
                            maxLines = 2
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(Ui.color(context, R.color.text))
                        })
                        addView(TextView(context).apply {
                            text = Ui.time(copy.durationMs) + "  ·  " + copy.sizeText + "  ·  " +
                                DateUtils.getRelativeTimeSpanString(copy.madeAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            textSize = 13f
                            setTextColor(Ui.color(context, R.color.text_secondary))
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    foreground = Ui.ripple(context)
                    setOnClickListener { ScrubbedSheet.show(this@TvActivity, copy) { show() } }
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
        if (linked == null) body.addView(Ui.caption(this, "On the TV: YouTube › Settings › Link with TV code."))
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, TvActivity::class.java))

        /** Sends a scrubbed movie to [device], which plays it in its own player. */
        fun sendCopy(activity: android.app.Activity, copy: CleanCopyFile, device: TvDevice) {
            rememberTv(activity, device)
            TvService.play(activity, copy, device)
            Ui.toast(activity, "Sending to ${device.name}")
            askToRunWithScreenOff(activity)
        }

        /**
         * Some phones stop apps that work with the screen off to save battery, which would cut the TV off
         * mid-film. Asked once: the viewer can let edenOS run without that limit.
         */
        private fun askToRunWithScreenOff(activity: android.app.Activity) {
            val power = activity.getSystemService(android.os.PowerManager::class.java)
            if (power.isIgnoringBatteryOptimizations(activity.packageName) || prefs(activity).getBoolean("askedBattery", false)) return
            prefs(activity).edit().putBoolean("askedBattery", true).apply()
            AlertDialog.Builder(activity)
                .setTitle("Keep playing with the screen off?")
                .setMessage("Lets the TV keep going when your phone locks.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        @Suppress("BatteryLife")
                        activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${activity.packageName}")))
                    } catch (e: Exception) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

        fun rememberTv(ctx: Context, d: TvDevice) = prefs(ctx).edit().putString("last", JSONObject()
            .put("kind", d.kind.name).put("name", d.name).put("location", d.location).put("control", d.controlUrl)
            .put("rendering", d.renderingUrl).toString()).apply()

        /** Looks for smart TVs on the Wi-Fi and lets the viewer pick one. */
        fun findTv(activity: android.app.Activity, then: (TvDevice) -> Unit) {
            val wait = AlertDialog.Builder(activity).setTitle("Looking for TVs…").setNegativeButton("Cancel", null).show()
            Thread {
                val found = try { TvFinder.find(activity.applicationContext) } catch (e: Exception) { emptyList() }
                activity.runOnUiThread {
                    if (activity.isDestroyed || !wait.isShowing) return@runOnUiThread
                    wait.dismiss()
                    if (found.isEmpty()) {
                        AlertDialog.Builder(activity).setTitle("No TV found")
                            .setMessage("Turn the TV on, on the same Wi-Fi as this phone.")
                            .setPositiveButton("Search again") { _, _ -> findTv(activity, then) }
                            .setNegativeButton("Close", null).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(activity).setTitle("Play on")
                        .setItems(found.map { it.name }.toTypedArray()) { _, which ->
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
                .takeIf { it.kind != TvDevice.Kind.ROKU } // Roku cannot play a video sent from the phone
        } catch (e: Exception) { null }
    }
}
