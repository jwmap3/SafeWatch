package com.safewatch.app.tv

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * TV Mode: edenOS on the TV's screen with the phone as its remote, and no app on the TV.
 *
 * When the phone is connected to a TV as a second screen (Samsung's Smart View and other screen
 * mirroring, or a USB-C to HDMI cable), Android lets an app show its own window there instead of a
 * copy of the phone. edenOS shows its TV home there, and pages and players open on the TV, while
 * the phone shows a remote. The phone does all the work, so it stays on (dimmed).
 */
object TvMode {
    /** True while the viewer has TV Mode on. Screens that show video put their picture on the TV while it is. */
    @Volatile var active = false

    private val main = Handler(Looper.getMainLooper())
    private var askedFor = -1

    /** The TV as a second screen, if the phone is connected to one. */
    fun display(ctx: Context): Display? {
        val manager = ctx.getSystemService(DisplayManager::class.java) ?: return null
        return manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    /** Calls [onChange] whenever a second screen comes or goes, until the returned function is called. */
    fun watch(ctx: Context, onChange: (Display?) -> Unit): () -> Unit {
        val manager = ctx.getSystemService(DisplayManager::class.java) ?: return {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChange(display(ctx))
            override fun onDisplayRemoved(displayId: Int) = onChange(display(ctx))
            override fun onDisplayChanged(displayId: Int) {}
        }
        manager.registerDisplayListener(listener, main)
        return { manager.unregisterDisplayListener(listener) }
    }

    /**
     * Offers TV Mode once when the phone connects to a TV, so the TV turns into edenOS without looking
     * for a button. Asked again only for a new connection.
     */
    fun offer(activity: Activity, display: Display?) {
        if (display == null) { askedFor = -1; return }
        if (active || askedFor == display.displayId || activity.isFinishing) return
        askedFor = display.displayId
        AlertDialog.Builder(activity)
            .setTitle("Show edenOS on ${display.name}?")
            .setMessage("Your phone is connected to ${display.name}. edenOS can fill the TV with its own home screen, " +
                "and your phone becomes the remote.")
            .setPositiveButton("TV Mode") { _, _ -> TvModeActivity.open(activity) }
            .setNegativeButton("Not now", null)
            .show()
    }
}

/**
 * The window edenOS shows on the TV. Whatever is put in [root] fills the TV. A pointer can be shown over
 * it, worked from the phone's touchpad.
 */
class TvStage(context: Context, display: Display) : Presentation(context, display) {
    val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    private val pointer = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(5, Color.argb(200, 0, 0, 0))
        }
        elevation = 30f
        visibility = View.GONE
    }
    private val hidePointer = Runnable { pointer.visibility = View.GONE }
    private val ui = Handler(Looper.getMainLooper())

    /** Where the pointer is, in the TV's pixels. */
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The sharpest picture the TV connection offers: its largest size, then its smoothest refresh.
        display.supportedModes.maxWithOrNull(compareBy<android.view.Display.Mode>({ it.physicalWidth * it.physicalHeight }, { it.refreshRate }))
            ?.let { best -> window?.attributes = window?.attributes?.apply { preferredDisplayModeId = best.modeId } }
        setContentView(FrameLayout(context).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(pointer, FrameLayout.LayoutParams(POINTER, POINTER, Gravity.TOP or Gravity.START))
        })
    }

    /** Moves the pointer by the given amount, keeping it on the screen, and shows it for a few seconds. */
    fun movePointer(dx: Float, dy: Float) {
        val w = root.width.coerceAtLeast(1)
        val h = root.height.coerceAtLeast(1)
        if (pointer.visibility != View.VISIBLE && pointerX == 0f && pointerY == 0f) {
            pointerX = w / 2f
            pointerY = h / 2f
        }
        pointerX = (pointerX + dx).coerceIn(0f, w - 1f)
        pointerY = (pointerY + dy).coerceIn(0f, h - 1f)
        pointer.translationX = pointerX - POINTER / 2f
        pointer.translationY = pointerY - POINTER / 2f
        pointer.visibility = View.VISIBLE
        ui.removeCallbacks(hidePointer)
        ui.postDelayed(hidePointer, 6000)
    }

    fun pointerShown(): Boolean = pointer.visibility == View.VISIBLE

    override fun onStop() {
        ui.removeCallbacks(hidePointer)
        super.onStop()
    }

    private companion object {
        const val POINTER = 34
    }
}
