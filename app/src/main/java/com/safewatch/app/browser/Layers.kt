package com.safewatch.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow

/**
 * The blur that hides the picture.
 *
 * It is drawn in a small window of its own, floating over the video, and
 * that is the point of it. The detector works by looking at the app's main
 * window; a blur put on the video itself would blind it, and it would have
 * to lift the blur to look again. Because this layer is a separate window,
 * the detector goes on seeing the real picture underneath and can keep the
 * blur up for exactly as long as it is needed.
 *
 * What it shows is the picture the detector was just given, shrunk to a
 * few dozen dots and stretched back out, so shapes and colour move but
 * nothing can be made out.
 */
class Curtain(private val anchor: View) {
    private val image = ImageView(anchor.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP))
        }
    }
    private val popup = PopupWindow(image, 1, 1).apply {
        isTouchable = false // touches go straight through to the page and the controls
        isFocusable = false
        isClippingEnabled = false
        animationStyle = 0
        windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
    }

    val isUp: Boolean get() = popup.isShowing

    /** Shows the curtain, or refreshes it, with a smeared copy of [frame] (or plain black, if the viewer chose that). */
    fun show(frame: Bitmap) {
        if (com.safewatch.app.data.Prefs.hideStyle(anchor.context) == "black") {
            image.setImageDrawable(null)
        } else {
            val small = Bitmap.createScaledBitmap(frame, DOTS, (DOTS * frame.height / maxOf(1, frame.width)).coerceAtLeast(2), true)
            image.setImageBitmap(small)
        }
        if (!anchor.isAttachedToWindow || anchor.width == 0) return
        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        if (popup.isShowing) {
            popup.update(at[0], at[1], anchor.width, anchor.height)
        } else {
            popup.width = anchor.width
            popup.height = anchor.height
            try {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], at[1])
            } catch (e: WindowManager.BadTokenException) {
                // The screen is closing; nothing to cover.
            }
        }
    }

    fun drop() {
        if (popup.isShowing) popup.dismiss()
        image.setImageDrawable(null)
    }

    private companion object {
        const val DOTS = 28
    }
}

/**
 * The player's controls, in a window of their own above the curtain so they
 * stay visible while the picture is hidden.
 *
 * A touch that lands on a control is handled here. Any other touch is passed
 * down to the page underneath, as if this layer were not there, so the
 * page's own buttons keep working.
 */
class PlayerLayer(context: Context, private val below: () -> View?, private val onTouched: () -> Unit) : FrameLayout(context) {
    private var passingDown = false
    private val offset = IntArray(2)
    private val mine = IntArray(2)

    val popup = PopupWindow(this, 1, 1).apply {
        isFocusable = false // typing still goes to the page
        isClippingEnabled = false
        animationStyle = 0
        windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
    }

    /** Puts the layer exactly over [area]. */
    fun showOver(area: View) {
        if (!area.isAttachedToWindow || area.width == 0) return
        val at = IntArray(2)
        area.getLocationInWindow(at)
        if (popup.isShowing) {
            popup.update(at[0], at[1], area.width, area.height)
        } else {
            popup.width = area.width
            popup.height = area.height
            try {
                popup.showAtLocation(area, Gravity.NO_GRAVITY, at[0], at[1])
            } catch (e: WindowManager.BadTokenException) {
                // The screen is closing.
            }
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouched()
            passingDown = !super.dispatchTouchEvent(event)
            if (!passingDown) return true
        } else if (!passingDown) {
            return super.dispatchTouchEvent(event)
        }
        // Not on a control: hand the touch to the page, shifted to where the page sits on screen.
        val target = below()
        if (target != null) {
            getLocationOnScreen(mine)
            target.getLocationOnScreen(offset)
            val copy = MotionEvent.obtain(event)
            copy.offsetLocation((mine[0] - offset[0]).toFloat(), (mine[1] - offset[1]).toFloat())
            target.dispatchTouchEvent(copy)
            copy.recycle()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) passingDown = false
        return true
    }
}
