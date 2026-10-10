package com.safewatch.app.tv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.safewatch.app.R
import com.safewatch.app.ui.Sheet
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
    /** Asks for text to search for, when the target cannot take typing a key at a time. */
    fun type()
    /** "toggle" (play or pause), "skip:-10" or "skip:10". */
    fun media(command: String)
    fun pointer(dx: Float, dy: Float)
    fun click()
    fun scroll(dy: Float)
    /** Whether the touchpad should start as a pointer (web pages) rather than arrows (the TV home, players). */
    val prefersPointer: Boolean

    /** Whether typing on the phone's keyboard goes straight to the TV, a key at a time. */
    val liveKeys: Boolean get() = false
    fun typeText(text: String) {}
    fun key(code: Int) {}
}

/**
 * The phone as the TV's remote: a large touchpad (swipe or tap its edges for the arrows and its middle for OK,
 * or switch it to a pointer for web pages), a scroll strip down its side, Back, Home, the keyboard, and play,
 * pause and skip. The phone dims itself when the remote is left alone, and lights up again at a touch.
 */
class RemotePad(
    private val activity: Activity,
    private val target: TvTarget,
    tvName: String,
    onExit: () -> Unit,
) : LinearLayout(activity) {
    private val surface = Surface(activity)
    private val strip = ScrollStrip(activity)
    private var dimmed = false
    private val dim = Runnable { setDimmed(true) }
    private val typing: LinearLayout
    private val field: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B0F0D"))
        val pad = Ui.dp(activity, 18)
        setPadding(pad, Ui.dp(activity, 14), pad, Ui.dp(activity, 18))

        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.logo(activity, 30))
            addView(TextView(activity).apply {
                text = tvName
                textSize = 16f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 8), 0)
            }, LayoutParams(0, -2, 1f))
            addView(Ui.pill(activity, "Exit", filled = false) { onExit() })
        })

        surface.pointerMode = target.prefersPointer
        addView(LinearLayout(activity).apply {
            addView(surface, LayoutParams(0, -1, 1f))
            addView(strip, LayoutParams(Ui.dp(activity, 54), -1).apply { marginStart = Ui.dp(activity, 10) })
        }, LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(activity, 14) })
        addView(Ui.segmented(activity, listOf("Arrows", "Pointer"), if (target.prefersPointer) 1 else 0) {
            surface.pointerMode = it == 1
            surface.invalidate()
        }, LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 12) })

        addView(row(
            button("Back", R.drawable.ic_back) { target.back() },
            button("Home", R.drawable.ic_home) { target.home() },
            button("Keyboard", R.drawable.ic_keyboard) { openKeyboard() },
        ), LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 12) })
        addView(row(
            button("Back 10 s", R.drawable.ic_back, label = "10") { target.media("skip:-10") },
            button("Play or pause", R.drawable.ic_play) { target.media("toggle") },
            button("Ahead 10 s", R.drawable.ic_forward, label = "10") { target.media("skip:10") },
        ), LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10) })

        // Typing: what is typed on the phone's keyboard goes to the TV a key at a time.
        field = EditText(activity).apply {
            hint = "Type on the TV"
            setHintTextColor(Color.argb(110, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 17f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        typing = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Color.parseColor("#1B2420"), Ui.dp(activity, 16).toFloat())
            setPadding(Ui.dp(activity, 14), Ui.dp(activity, 4), Ui.dp(activity, 6), Ui.dp(activity, 4))
            addView(Ui.icon(activity, R.drawable.ic_keyboard, sizeDp = 20).apply { imageTintList = android.content.res.ColorStateList.valueOf(Color.argb(170, 255, 255, 255)) })
            addView(field, LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(activity, 8) })
            addView(Ui.pill(activity, "Done", filled = true) { closeKeyboard() })
            visibility = GONE
        }
        addView(typing, LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10) })
        var sent = ""
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val now = s?.toString().orEmpty()
                var same = 0
                while (same < now.length && same < sent.length && now[same] == sent[same]) same++
                repeat(sent.length - same) { target.key(KeyEvent.KEYCODE_DEL) }
                if (now.length > same) target.typeText(now.substring(same))
                sent = now
            }
        })
        field.setOnEditorActionListener { _, _, _ ->
            target.key(KeyEvent.KEYCODE_ENTER)
            sent = ""
            field.setText("")
            closeKeyboard()
            true
        }
    }

    /** Opens the phone's keyboard. Where the TV can take keys one at a time they go straight there; otherwise it asks. */
    private fun openKeyboard() {
        if (!target.liveKeys) { target.type(); return }
        typing.visibility = VISIBLE
        typing.alpha = 0f
        typing.translationY = Ui.dp(activity, 20).toFloat()
        typing.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Sheet.EASE_OUT).start()
        field.requestFocus()
        field.post {
            activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        typing.animate().alpha(0f).setDuration(150).withEndAction { typing.visibility = GONE }.start()
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
        if (on && typing.visibility == VISIBLE) return // not while typing
        dimmed = on
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        animate().alpha(if (on) 0.35f else 1f).setDuration(300).start()
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
        Sheet.pressable(this)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Sounds.play(activity, Sounds.TAP)
            onClick()
        }
    }

    /**
     * The touchpad. With arrows: swipe to move (a long swipe keeps moving, one step for every so far along),
     * or tap an edge; tap the middle for OK. With the pointer: slide a finger to move it, tap to click, and
     * slide two fingers to scroll.
     */
    private inner class Surface(context: Context) : View(context) {
        var pointerMode = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        private val step = Ui.dp(context, 64).toFloat()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141C18") }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            textSize = Ui.dp(context, 13).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val box = RectF()
        private var downX = 0f
        private var downY = 0f
        private var anchorX = 0f
        private var anchorY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var stepped = false
        private var twoFingers = false
        private var downAt = 0L
        private var touchX = -1f
        private var touchY = -1f

        init {
            contentDescription = "Touchpad"
        }

        override fun onDraw(canvas: Canvas) {
            val r = Ui.dp(context, 28).toFloat()
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, fill)
            // A soft light under the finger.
            if (touchX >= 0) canvas.drawCircle(touchX, touchY, Ui.dp(context, 46).toFloat(), glow)
            if (pointerMode) {
                canvas.drawText("Pointer", width / 2f, height - Ui.dp(context, 18).toFloat(), words)
                return
            }
            val cx = width / 2f
            val cy = height / 2f
            val ring = minOf(width, height) * 0.16f
            canvas.drawCircle(cx, cy, ring, line)
            canvas.drawText("OK", cx, cy + words.textSize / 3, words)
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
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; anchorX = e.x; anchorY = e.y
                    moved = false
                    stepped = false
                    twoFingers = false
                    downAt = e.eventTime
                    touchX = e.x; touchY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFingers = true
                    lastY = (e.getY(0) + e.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                    touchX = e.x; touchY = e.y
                    invalidate()
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
                    } else {
                        // Keeps moving while the finger keeps going: one step for every so far along.
                        val dx = e.x - anchorX
                        val dy = e.y - anchorY
                        if (hypot(dx, dy) >= step) {
                            tapped { target.arrow(direction(dx, dy)) }
                            anchorX = e.x
                            anchorY = e.y
                            stepped = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchX = -1f
                    invalidate()
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL) return true
                    if (pointerMode) {
                        if (!moved && !twoFingers && e.eventTime - downAt < 400) tapped { target.click() }
                    } else if (moved) {
                        if (!stepped) tapped { target.arrow(direction(e.x - downX, e.y - downY)) }
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

    /**
     * The scroll strip down the side of the touchpad, like a wheel: drag up or down along it and what is on the
     * TV scrolls with the finger, flick it and it glides on and slows to a stop. Small ticks pass under the finger
     * so it feels like turning a wheel.
     */
    private inner class ScrollStrip(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141C18") }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            strokeWidth = Ui.dp(context, 2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
        private val arrows = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val box = RectF()
        private var offset = 0f // how far the ticks have rolled, so they move with the finger
        private var lastY = 0f
        private var thumbY = -1f
        private var sinceTick = 0f
        private var velocity: VelocityTracker? = null
        private var glide: Runnable? = null

        init {
            contentDescription = "Scroll"
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, track)
            val gap = Ui.dp(context, 18).toFloat()
            val cx = width / 2f
            val half = Ui.dp(context, 7).toFloat()
            var y = (offset % gap + gap) % gap + Ui.dp(context, 34)
            while (y < height - Ui.dp(context, 34)) {
                canvas.drawLine(cx - half, y, cx + half, y, tick)
                y += gap
            }
            // Arrows at each end say which way it goes.
            val a = Ui.dp(context, 6).toFloat()
            val top = Ui.dp(context, 18).toFloat()
            canvas.drawPath(Path().apply { moveTo(cx - a, top + a / 2); lineTo(cx, top - a / 2); lineTo(cx + a, top + a / 2) }, arrows)
            val bottom = height - Ui.dp(context, 18).toFloat()
            canvas.drawPath(Path().apply { moveTo(cx - a, bottom - a / 2); lineTo(cx, bottom + a / 2); lineTo(cx + a, bottom - a / 2) }, arrows)
            if (thumbY >= 0) canvas.drawRoundRect(cx - r * 0.6f, thumbY - Ui.dp(context, 22), cx + r * 0.6f, thumbY + Ui.dp(context, 22), r, r, thumb)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopGlide()
                    lastY = e.y
                    thumbY = e.y
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dy = e.y - lastY
                    lastY = e.y
                    thumbY = e.y.coerceIn(Ui.dp(context, 22).toFloat(), height - Ui.dp(context, 22).toFloat())
                    roll(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(1000)
                    val v = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    thumbY = -1f
                    invalidate()
                    if (abs(v) > Ui.dp(context, 300)) glideFrom(v)
                }
            }
            return true
        }

        /** Scrolls the TV by a finger's movement along the strip: finger down, page down. */
        private fun roll(dy: Float) {
            offset += dy
            target.scroll(dy * SCROLL_SPEED)
            sinceTick += abs(dy)
            if (sinceTick > Ui.dp(context, 18)) {
                sinceTick = 0f
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            invalidate()
        }

        /** After a flick, keeps scrolling and eases to a stop, a frame at a time. */
        private fun glideFrom(start: Float) {
            var v = start
            var last = System.nanoTime()
            val frame = object : Runnable {
                override fun run() {
                    val now = System.nanoTime()
                    val dt = (now - last) / 1e9f
                    last = now
                    roll(v * dt)
                    v *= Math.pow(0.04, dt.toDouble()).toFloat() // loses most of its speed within a second
                    if (abs(v) > Ui.dp(context, 40)) postOnAnimation(this) else glide = null
                }
            }
            glide = frame
            postOnAnimation(frame)
        }

        private fun stopGlide() {
            glide?.let { removeCallbacks(it) }
            glide = null
        }

        override fun onDetachedFromWindow() {
            stopGlide()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val POINTER_SPEED = 2.4f
        const val SCROLL_SPEED = 3f
    }
}
