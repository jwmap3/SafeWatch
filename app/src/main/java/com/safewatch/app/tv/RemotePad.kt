package com.safewatch.app.tv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.safewatch.app.R
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** What the phone's remote works on the TV: the TV home, or a page or player shown there. */
interface TvTarget {
    /** An arrow, as KeyEvent.KEYCODE_DPAD_UP, DOWN, LEFT or RIGHT. */
    fun arrow(keyCode: Int)
    fun ok()
    fun back()
    fun home()
    /** Asks for text to search for, or to type into the page. */
    fun type()
    /** "toggle" (play or pause), "skip:-10" or "skip:10". */
    fun media(command: String)
    fun pointer(dx: Float, dy: Float)
    fun click()
    fun scroll(dy: Float)
    /** Whether the touchpad should start as a pointer (web pages) rather than arrows (the TV home, players). */
    val prefersPointer: Boolean
}

/**
 * The phone as the TV's remote: a large touchpad (swipe or tap its edges for the arrows and its middle for
 * OK, or switch it to a pointer for web pages), Back, Home, typing, and play, pause and skip. The phone
 * dims itself when the remote is left alone, and lights up again at a touch.
 */
class RemotePad(
    private val activity: Activity,
    private val target: TvTarget,
    tvName: String,
    onExit: () -> Unit,
) : LinearLayout(activity) {
    private val surface = Surface(activity)
    private var dimmed = false
    private val dim = Runnable { setDimmed(true) }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B0F0D"))
        val pad = Ui.dp(activity, 18)
        setPadding(pad, Ui.dp(activity, 14), pad, Ui.dp(activity, 18))

        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.logo(activity, 34))
            addView(TextView(activity).apply {
                text = "On $tvName"
                textSize = 17f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 8), 0)
            }, LayoutParams(0, -2, 1f))
            addView(Ui.pill(activity, "Exit TV Mode", filled = false) { onExit() })
        })

        surface.pointerMode = target.prefersPointer
        addView(surface, LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(activity, 16) })
        addView(TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(150, 255, 255, 255))
            setPadding(0, Ui.dp(activity, 8), 0, 0)
            surface.hint = this
        })
        addView(Ui.segmented(activity, listOf("Arrows", "Pointer"), if (target.prefersPointer) 1 else 0) {
            surface.pointerMode = it == 1
            surface.invalidate()
            surface.showHint()
        }, LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10) })
        surface.showHint()

        addView(row(
            button("Back", R.drawable.ic_back) { target.back() },
            button("Home", R.drawable.ic_home) { target.home() },
            button("Type", R.drawable.ic_search) { target.type() },
        ), LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 14) })
        addView(row(
            button("Back 10 s", R.drawable.ic_back, label = "10") { target.media("skip:-10") },
            button("Play or pause", R.drawable.ic_play) { target.media("toggle") },
            button("Ahead 10 s", R.drawable.ic_forward, label = "10") { target.media("skip:10") },
        ), LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wake()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dim)
        setDimmed(false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val wasDim = dimmed
            wake()
            if (wasDim) return true // the first touch only lights the phone up
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun wake() {
        setDimmed(false)
        removeCallbacks(dim)
        postDelayed(dim, 25_000)
    }

    /** The phone is only a remote now, so after a while it goes nearly dark to save its battery. */
    private fun setDimmed(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        alpha = if (on) 0.35f else 1f
    }

    private fun row(vararg views: View) = LinearLayout(activity).apply {
        views.forEachIndexed { i, v ->
            addView(v, LayoutParams(0, Ui.dp(activity, 58), 1f).apply { if (i > 0) leftMargin = Ui.dp(activity, 10) })
        }
    }

    private fun button(description: String, icon: Int, label: String? = null, onClick: () -> Unit) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        background = Ui.rounded(Color.parseColor("#1B2420"), Ui.dp(activity, 16).toFloat())
        addView(Ui.icon(activity, icon, R.color.on_accent, 24).apply { imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) })
        if (label != null) addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(activity, 4), 0, 0, 0)
        })
        foreground = Ui.ripple(activity)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Sounds.play(activity, Sounds.TAP)
            onClick()
        }
    }

    /**
     * The touchpad. With arrows: swipe, or tap an edge, to move; tap the middle for OK. With the pointer:
     * slide a finger to move it, tap to click, and slide two fingers to scroll.
     */
    private inner class Surface(context: Context) : View(context) {
        var pointerMode = false
        var hint: TextView? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141C18") }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val box = RectF()
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var twoFingers = false
        private var downAt = 0L

        init {
            contentDescription = "Touchpad"
        }

        fun showHint() {
            hint?.text = if (pointerMode) "Slide to move the pointer · tap to click · two fingers to scroll"
            else "Swipe or tap an edge to move · tap the middle for OK"
        }

        override fun onDraw(canvas: Canvas) {
            val r = Ui.dp(context, 28).toFloat()
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, fill)
            if (pointerMode) return
            val cx = width / 2f
            val cy = height / 2f
            val ring = minOf(width, height) * 0.16f
            canvas.drawCircle(cx, cy, ring, line)
            val c = Ui.dp(context, 10).toFloat()
            val edge = minOf(width, height) * 0.36f
            fun chevron(x: Float, y: Float, dx: Float, dy: Float) {
                // A small arrow head pointing along (dx, dy).
                val path = Path()
                path.moveTo(x - dy * c - dx * c, y + dx * c - dy * c)
                path.lineTo(x, y)
                path.lineTo(x + dy * c - dx * c, y - dx * c - dy * c)
                canvas.drawPath(path, line)
            }
            chevron(cx, cy - edge, 0f, -1f)
            chevron(cx, cy + edge, 0f, 1f)
            chevron(cx - edge, cy, -1f, 0f)
            chevron(cx + edge, cy, 1f, 0f)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    moved = false
                    twoFingers = false
                    downAt = e.eventTime
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFingers = true
                    lastY = (e.getY(0) + e.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                    if (pointerMode) {
                        if (twoFingers && e.pointerCount >= 2) {
                            val y = (e.getY(0) + e.getY(1)) / 2
                            target.scroll((lastY - y) * 3f)
                            lastY = y
                        } else if (!twoFingers) {
                            target.pointer((e.x - lastX) * POINTER_SPEED, (e.y - lastY) * POINTER_SPEED)
                            lastX = e.x
                            lastY = e.y
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pointerMode) {
                        if (!moved && !twoFingers && e.eventTime - downAt < 400) tapped { target.click() }
                    } else if (moved) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        tapped { target.arrow(direction(dx, dy)) }
                    } else {
                        val dx = e.x - width / 2f
                        val dy = e.y - height / 2f
                        if (hypot(dx, dy) < minOf(width, height) * 0.2f) tapped { target.ok() }
                        else tapped { target.arrow(direction(dx, dy)) }
                    }
                }
            }
            return true
        }

        private fun tapped(action: () -> Unit) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }

        private fun direction(dx: Float, dy: Float): Int {
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            return when {
                angle >= -45 && angle < 45 -> KeyEvent.KEYCODE_DPAD_RIGHT
                angle >= 45 && angle < 135 -> KeyEvent.KEYCODE_DPAD_DOWN
                angle >= -135 && angle < -45 -> KeyEvent.KEYCODE_DPAD_UP
                else -> KeyEvent.KEYCODE_DPAD_LEFT
            }
        }
    }

    private companion object {
        const val POINTER_SPEED = 2.4f
    }
}
