package com.safewatch.app.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.R
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui

/**
 * TV Mode's home: edenOS's own TV screen, shown on the TV, with the phone as its remote. Pick a service,
 * a YouTube video or a quick link and it opens on the TV, filtered as everywhere else in edenOS.
 */
class TvModeActivity : AppCompatActivity() {
    private var stage: TvStage? = null
    private var home: TvHome? = null
    private var stopWatching: (() -> Unit)? = null
    private lateinit var holder: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvMode.active = true
        holder = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F0D")) }
        setContentView(holder)
        Ui.fitSystemBars(this, holder)
    }

    override fun onStart() {
        super.onStart()
        stopWatching = TvMode.watch(this) { show(it) }
        show(TvMode.display(this))
    }

    override fun onStop() {
        stopWatching?.invoke()
        stopWatching = null
        // While a page or player is on the TV, it has the TV to itself.
        closeStage()
        super.onStop()
    }

    private fun closeStage() {
        stage?.let { if (it.isShowing) it.dismiss() }
        stage = null
        home = null
    }

    /** Puts the TV home on the TV, or, with no TV connected, says how to connect one. */
    private fun show(display: Display?) {
        if (isFinishing) return
        if (display == null) {
            closeStage()
            holder.removeAllViews()
            holder.addView(connectHelp(), FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (stage?.display?.displayId == display.displayId && stage?.isShowing == true) return
        closeStage()
        val tv = TvStage(this, display)
        val view = TvHome(tv.context) { open(it) }
        tv.show()
        tv.root.addView(view, FrameLayout.LayoutParams(-1, -1))
        tv.setOnDismissListener { if (stage === tv) { stage = null; home = null } }
        stage = tv
        home = view
        holder.removeAllViews()
        holder.addView(RemotePad(this, homeTarget, display.name) { exit() }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun connectHelp(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(context, 28)
        setPadding(pad, Ui.dp(context, 60), pad, pad)
        addView(Ui.logo(context, 96))
        addView(TextView(context).apply {
            text = "edenTV"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 22), 0, Ui.dp(context, 10))
        })
        addView(TextView(context).apply {
            text = "Open Smart View and pick your TV.\nThis phone becomes the remote."
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(Color.argb(200, 255, 255, 255))
        })
        addView(Ui.actionButton(context, "Open Smart View", iconRes = R.drawable.ic_cast) { Ui.openScreenCasting(this@TvModeActivity) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 28) })
        addView(Ui.actionButton(context, "Leave edenTV", filled = false) { exit() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 12) })
    }

    /** Leaves TV Mode for the phone's own Home. */
    private fun exit() {
        TvMode.active = false
        closeStage()
        com.safewatch.app.MainActivity.open(this)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (home?.back() == true) return
        exit()
    }

    /** Opens what was picked on the TV home. Pages and players go to the TV by themselves while TV Mode is on. */
    private fun open(choice: TvHome.Choice) {
        Sounds.play(this, Sounds.OPEN)
        when (choice) {
            is TvHome.Choice.Page -> BrowserActivity.open(this, choice.url)
            is TvHome.Choice.Service -> WatchActivity.open(this, choice.url, choice.name)
            is TvHome.Choice.Copy -> com.safewatch.app.player.PlayerActivity.openCopy(this, choice.file, choice.title, onTv = true)
            is TvHome.Choice.Search -> type()
        }
    }

    /** Asks the phone for words to look for, on YouTube or the web. */
    private fun type() {
        val field = EditText(this).apply {
            hint = "Search YouTube or the web"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(box)
            .setPositiveButton("YouTube") { _, _ ->
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                    WatchActivity.open(this, "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(it), "YouTube")
                }
            }
            .setNeutralButton("The web") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { BrowserActivity.open(this, it) } }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private val homeTarget = object : TvTarget {
        override val prefersPointer = false
        override fun arrow(keyCode: Int) { home?.move(keyCode) }
        override fun ok() { home?.choose() }
        override fun back() { home?.back() }
        override fun home() { home?.toTop() }
        override fun type() = this@TvModeActivity.type()
        override fun media(command: String) {}
        override fun pointer(dx: Float, dy: Float) { stage?.movePointer(dx, dy) }
        override fun click() { home?.clickAt(stage?.pointerX ?: 0f, stage?.pointerY ?: 0f) }
        override fun scroll(dy: Float) { home?.scrollBy(dy) }
    }

    companion object {
        fun open(activity: Activity) = activity.startActivity(Intent(activity, TvModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
