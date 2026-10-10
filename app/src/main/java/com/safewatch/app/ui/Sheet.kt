package com.safewatch.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.safewatch.app.R

/**
 * A card that rises from the bottom of the screen over a soft dimmed backdrop, and sinks away again.
 * Tap outside it, press Back or pull it down to close it. Put its content in [body].
 */
class Sheet(private val activity: Activity) {
    private val dialog = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() = close()
    }
    private val scrim = View(activity).apply { setBackgroundColor(Color.argb(140, 0, 0, 0)); alpha = 0f }
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val r = Ui.dp(activity, 28).toFloat()
        background = GradientDrawable().apply {
            setColor(Ui.color(activity, R.color.card))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        elevation = Ui.dp(activity, 24).toFloat()
        isClickable = true // touches on the card do not fall through to the backdrop
    }

    /** Where the sheet's content goes. */
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private var closing = false
    private var onClosed: (() -> Unit)? = null

    init {
        val side = Ui.dp(activity, 20)
        // The small handle at the top, which says it can be pulled down.
        card.addView(View(activity).apply {
            background = Ui.rounded(Ui.color(activity, R.color.separator), Ui.dp(activity, 3).toFloat())
        }, LinearLayout.LayoutParams(Ui.dp(activity, 38), Ui.dp(activity, 5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Ui.dp(activity, 10)
            bottomMargin = Ui.dp(activity, 12)
        })
        // Scrolls when there is more than fits on the screen.
        card.addView(android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        card.setPadding(side, 0, side, Ui.dp(activity, 18))
        val root = FrameLayout(activity).apply {
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { topMargin = Ui.dp(activity, 48) })
        }
        scrim.setOnClickListener { close() }
        ViewCompat.setOnApplyWindowInsetsListener(card) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(side, 0, side, Ui.dp(activity, 18) + bars.bottom)
            insets
        }
        followFinger(root)
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            w.setWindowAnimations(0) // the sheet moves itself
        }
    }

    fun show(): Sheet {
        if (activity.isFinishing) return this
        dialog.show()
        card.visibility = View.INVISIBLE
        card.post {
            card.translationY = card.height.toFloat()
            card.visibility = View.VISIBLE
            card.animate().translationY(0f).setDuration(380).setInterpolator(EASE_OUT).start()
            scrim.animate().alpha(1f).setDuration(280).start()
        }
        return this
    }

    /** Sinks the sheet away, then runs [then]. */
    fun close(then: (() -> Unit)? = null) {
        if (then != null) onClosed = then
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(200).start()
        card.animate().translationY(card.height.toFloat()).setDuration(240).setInterpolator(EASE_IN)
            .withEndAction {
                try { dialog.dismiss() } catch (e: Exception) { /* the screen went first */ }
                onClosed?.invoke()
            }.start()
    }

    /** Swaps the sheet's content for new content with a quick cross-fade, keeping the height change smooth. */
    fun swap(fill: (LinearLayout) -> Unit) {
        val from = card.height
        body.animate().alpha(0f).setDuration(120).withEndAction {
            body.removeAllViews()
            fill(body)
            card.measure(View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            val to = card.measuredHeight
            // Slides from the old height to the new one, so the card grows or shrinks rather than jumping.
            card.translationY = (to - from).toFloat().coerceAtLeast(0f)
            card.animate().translationY(0f).setDuration(260).setInterpolator(EASE_OUT).start()
            body.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    /** Pulling the card down drags it with the finger; let go far enough and it closes, otherwise it springs back. */
    private fun followFinger(root: FrameLayout) {
        var startY = 0f
        var dragging = false
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; dragging = true }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val dy = (e.rawY - startY).coerceAtLeast(0f)
                    card.translationY = dy
                    scrim.alpha = 1f - (dy / card.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    if (card.translationY > card.height * 0.25f) close()
                    else {
                        card.animate().translationY(0f).setDuration(220).setInterpolator(EASE_OUT).start()
                        scrim.animate().alpha(1f).setDuration(220).start()
                    }
                }
            }
            true
        }
        root.clipChildren = false
    }

    companion object {
        val EASE_OUT = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)

        /** Makes a view shrink a little while pressed and spring back, so taps feel physical. */
        fun pressable(view: View) {
            view.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(EASE_OUT).start()
                }
                false
            }
        }
    }
}
