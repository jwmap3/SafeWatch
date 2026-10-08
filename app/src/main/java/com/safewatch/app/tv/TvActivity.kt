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
            "A clean copy is the video with the filtering built in. Your Roku or smart TV plays it by itself, so your phone " +
                "can be locked while it plays. Keep the phone on the Wi-Fi: the TV fetches the video from it."))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
        setContentView(page)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        TvState.listeners += listener
        show()
    }

    override fun onPause() {
        TvState.listeners -= listener
        super.onPause()
    }

    private fun show() {
        body.removeAllViews()
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
        if (copies.isEmpty()) {
            body.addView(Ui.caption(this,
                "None yet. While watching a video file or a website's video, tap Send to TV, then Clean copy to TV. " +
                    "Streaming services and YouTube cannot be saved; use Mirror to TV for those."))
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

    private fun choose(copy: CleanCopyFile) {
        val last = lastTv(this)
        val options = listOfNotNull(last?.let { "Play on ${it.name}" }, "Play on a TV…", "Delete")
        AlertDialog.Builder(this)
            .setTitle(copy.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Play on a TV…" -> pickTv(copy)
                    "Delete" -> AlertDialog.Builder(this).setMessage("Delete the clean copy of ${copy.title}?")
                        .setPositiveButton("Delete") { _, _ -> CleanCopy.delete(copy); show() }
                        .setNegativeButton("Cancel", null).show()
                    else -> last?.let { send(copy, it) }
                }
            }
            .show()
    }

    private fun pickTv(copy: CleanCopyFile) {
        val wait = AlertDialog.Builder(this).setTitle("Looking for TVs…")
            .setMessage("Rokus and smart TVs on this Wi-Fi.").setNegativeButton("Cancel", null).show()
        Thread {
            val found = try { TvFinder.find(applicationContext) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed || !wait.isShowing) return@runOnUiThread
                wait.dismiss()
                if (found.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("No TV found")
                        .setMessage("Make sure the TV is on and on the same Wi-Fi as this phone.\n\n" +
                            "Roku: Settings > System > Advanced system settings > Control by mobile apps > Network access, set to Default or Permissive.\n\n" +
                            "Samsung and LG: the TV may show a message asking whether to allow this phone; choose Allow. If it said no " +
                            "before, look in the TV's settings for connected or mobile devices and allow it there.")
                        .setPositiveButton("Search again") { _, _ -> pickTv(copy) }
                        .setNegativeButton("Close", null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Play on")
                    .setItems(found.map { it.name + if (it.kind == TvDevice.Kind.ROKU) " (Roku)" else "" }.toTypedArray()) { _, which -> send(copy, found[which]) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    private fun send(copy: CleanCopyFile, device: TvDevice) {
        rememberTv(this, device)
        TvService.play(this, copy, device)
        Ui.toast(this, "Sending to ${device.name}. A Samsung or LG TV may ask you to allow it.")
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, TvActivity::class.java))

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

        fun rememberTv(ctx: Context, d: TvDevice) = prefs(ctx).edit().putString("last", JSONObject()
            .put("kind", d.kind.name).put("name", d.name).put("location", d.location).put("control", d.controlUrl).toString()).apply()

        fun lastTv(ctx: Context): TvDevice? = try {
            val o = JSONObject(prefs(ctx).getString("last", null) ?: return null)
            TvDevice(TvDevice.Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("location"), o.optString("control"))
        } catch (e: Exception) { null }
    }
}
