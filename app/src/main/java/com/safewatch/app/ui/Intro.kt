package com.safewatch.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import com.safewatch.app.R
import com.safewatch.app.data.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The opening of the app, about three and a half seconds. In the dark, the emerald leaf of the e is
 * there, unlit. The sunlit leaf drifts down from above, swaying as a leaf does, and settles into its
 * place; the moment it lands, light blooms out of the e: the leaves come up to full colour, a warm glow
 * and soft rays open behind them and a gleam runs across. The name comes up underneath, and everything
 * eases forward into the app. With its own short orchestral sound. A tap skips it.
 */
class Intro private constructor(private val activity: Activity, private val onDone: () -> Unit) : View(activity) {
    private val upper: Path = Brand.path(Brand.UPPER_LEAF)
    private val lower: Path = Brand.path(Brand.LOWER_LEAF)
    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Brand.typeface(activity) }
    private val upperShader = LinearGradient(25f, 22f, 75f, 64f, Brand.EMERALD, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val lowerShader = LinearGradient(35f, 62f, 79f, 78f, Brand.SUNLIT, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val rays = Path()
    private var timeline: ValueAnimator? = null
    private var sound: MediaPlayer? = null
    private var finished = false

    /** Milliseconds into the opening. */
    private var t = 0f

    init {
        isClickable = true
        elevation = 1000f
        setOnClickListener { finish(quick = true) }
        // Fourteen soft rays, drawn once, turned as the light opens.
        for (i in 0 until 14) {
            val a = i * 2 * PI / 14
            val w = 0.022
            rays.moveTo(0f, 0f)
            rays.lineTo((cos(a - w) * 150).toFloat(), (sin(a - w) * 150).toFloat())
            rays.lineTo((cos(a + w) * 150).toFloat(), (sin(a + w) * 150).toFloat())
            rays.close()
        }
    }

    private fun start() {
        timeline = ValueAnimator.ofFloat(0f, LENGTH).apply {
            duration = LENGTH.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish(quick = false)
            })
            start()
        }
        playSound()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val exit = ease(seg(3150f, 3600f))
        // The ground: near black, with the faintest green in the middle.
        canvas.drawColor(Color.argb((255 * (1 - exit)).toInt(), 4, 10, 8))
        if (exit >= 1f) return
        val m = min(w, h) * 0.46f // the mark's size
        val cx = w / 2
        val cy = h / 2 - m * 0.18f
        val k = m / 100f
        val zoom = 1f + 0.12f * exit
        val fade = 1f - exit

        canvas.save()
        canvas.scale(zoom, zoom, cx, cy)

        // The light that opens when the leaf lands.
        val bloom = ease(seg(1650f, 2300f))
        val settle = 1f - 0.3f * ease(seg(2300f, 3100f))
        if (bloom > 0f) {
            val glow = bloom * settle * fade
            glowPaint.shader = RadialGradient(cx, cy + 4 * k, m * (0.75f + 0.55f * bloom),
                intArrayOf(Color.argb((150 * glow).toInt(), 255, 214, 120), Color.argb((90 * glow).toInt(), 40, 170, 105), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy + 4 * k, m * 1.4f, glowPaint)
            // Rays, turning slowly.
            val rayAlpha = (bloom * (1f - 0.45f * ease(seg(2200f, 3100f))) * fade * 0.11f).coerceIn(0f, 1f)
            rayPaint.shader = RadialGradient(0f, 0f, 150f, intArrayOf(Color.argb((255 * rayAlpha).toInt(), 255, 236, 180), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.translate(cx, cy + 4 * k)
            canvas.scale(k * 1.1f, k * 1.1f)
            canvas.rotate(-8f + 16f * (t / LENGTH))
            canvas.drawPath(rays, rayPaint)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(cx - 50 * k, cy - 50 * k)
        canvas.scale(k, k)

        // The emerald leaf: there from the start, unlit, coming up to full colour in the light.
        val appear = ease(seg(120f, 900f))
        val lit = bloom
        leafPaint.shader = upperShader
        leafPaint.colorFilter = tone(0.24f + 0.76f * lit, 0.35f + 0.65f * lit)
        leafPaint.alpha = (255 * appear * fade).toInt()
        canvas.save()
        val grow = 0.94f + 0.06f * appear
        canvas.scale(grow, grow, 50f, 45f)
        canvas.drawPath(upper, leafPaint)
        canvas.restore()

        // The sunlit leaf, falling into place.
        val p = seg(250f, 1600f)
        val drop = easeInOut(p)
        val sway = (1 - p).toDouble().pow(1.3).toFloat()
        val dx = -58f * (1 - drop) + 20f * sin(2 * PI.toFloat() * 1.45f * p) * sway
        val dy = -150f * (1 - drop)
        val turn = -48f * (1 - drop) + 26f * sin(2 * PI.toFloat() * 1.45f * p + 0.7f) * sway +
            5f * sin(PI.toFloat() * seg(1600f, 1900f)) * (1 - seg(1600f, 1900f))
        if (t >= 250f) {
            canvas.save()
            canvas.translate(dx, dy)
            canvas.rotate(turn, 57f, 68f)
            leafPaint.shader = lowerShader
            leafPaint.colorFilter = tone(0.78f + 0.22f * lit, 0.8f + 0.2f * lit)
            leafPaint.alpha = (255 * ease(seg(250f, 520f)) * fade).toInt()
            canvas.drawPath(lower, leafPaint)
            canvas.restore()
        }

        // Light from above on both leaves, and a gleam that runs across them as they light.
        shinePaint.shader = LinearGradient(0f, 18f, 0f, 46f, Color.argb((90 * lit * fade).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(upper, shinePaint)
        val gleam = seg(1750f, 2450f)
        if (gleam > 0f && gleam < 1f) {
            val at = -30f + 160f * easeInOut(gleam)
            shinePaint.shader = LinearGradient(at - 16f, at - 30f, at + 16f, at + 2f,
                intArrayOf(Color.TRANSPARENT, Color.argb((170 * fade).toInt(), 255, 250, 228), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(upper, shinePaint)
            canvas.drawPath(lower, shinePaint)
        }

        // A ring of light where the leaf lands.
        val ripple = seg(1600f, 2350f)
        if (ripple > 0f && ripple < 1f) {
            ripplePaint.strokeWidth = 1.6f * (1 - ripple) + 0.3f
            ripplePaint.color = Color.argb((70 * (1 - ripple) * fade).toInt(), 255, 220, 140)
            canvas.drawCircle(57f, 68f, 6f + 36f * ease(ripple), ripplePaint)
        }

        // A few motes of light drifting out.
        val motes = seg(1700f, 2900f)
        if (motes > 0f && motes < 1f) {
            for (i in 0 until 9) {
                val a = (i * 2.39996 + 0.4).toFloat() // spread evenly round the circle
                val r = 20f + (38f + 9f * (i % 3)) * ease(motes)
                val alpha = sin(PI.toFloat() * motes) * fade
                sparkPaint.color = Color.argb((200 * alpha).toInt(), 255, 236, 190)
                canvas.drawCircle(52f + cos(a) * r, 52f + sin(a) * r * 0.85f, 0.7f + 0.5f * (i % 2), sparkPaint)
            }
        }
        canvas.restore()

        // The name.
        val word = ease(seg(2150f, 2850f))
        if (word > 0f) {
            wordPaint.textSize = m * 0.215f
            wordPaint.letterSpacing = 0.02f + 0.3f * (1 - word)
            val eden = wordPaint.measureText("eden")
            val os = wordPaint.measureText("OS")
            val x0 = cx - (eden + os) / 2
            val baseline = cy + m * 0.62f + (1 - word) * m * 0.05f
            val a = (255 * word * fade).toInt()
            wordPaint.shader = null
            wordPaint.color = Brand.MIST
            wordPaint.alpha = a
            canvas.drawText("eden", x0, baseline, wordPaint)
            wordPaint.shader = LinearGradient(x0 + eden, 0f, x0 + eden + os, 0f, Brand.GOLD[0], Brand.GOLD[1], Shader.TileMode.CLAMP)
            wordPaint.alpha = a
            canvas.drawText("OS", x0 + eden, baseline, wordPaint)
            val glint = seg(2650f, 3150f)
            if (glint > 0f && glint < 1f) {
                val total = eden + os
                val at = x0 + total * easeInOut(glint)
                wordPaint.shader = LinearGradient(at - total * 0.12f, 0f, at + total * 0.12f, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((230 * fade).toInt(), 255, 248, 222), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                wordPaint.alpha = 255
                canvas.drawText("edenOS", x0, baseline, wordPaint)
            }
            wordPaint.shader = null
        }
        canvas.restore()
    }

    /** Dims and mutes a leaf's colour before the light: [brightness] and [saturation] from 0 to 1. */
    private fun tone(brightness: Float, saturation: Float): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 1f)) }
        val b = brightness.coerceIn(0f, 1f)
        m.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(m)
    }

    /** How far through the stretch from [a] to [b] milliseconds the opening is, from 0 to 1. */
    private fun seg(a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun ease(x: Float): Float = 1 - (1 - x) * (1 - x) * (1 - x)

    private fun easeInOut(x: Float): Float = (0.5f - 0.5f * cos(PI.toFloat() * x))

    private fun playSound() {
        if (Prefs.soundPack(activity) == "off") return
        val volume = Prefs.soundVolume(activity) / 100f
        if (volume <= 0f) return
        try {
            sound = MediaPlayer.create(activity, R.raw.intro, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0)?.apply {
                setVolume(volume, volume)
                setOnCompletionListener { it.release(); if (sound === it) sound = null }
                start()
            }
        } catch (e: Exception) {
            // No sound then; the picture is the main thing.
        }
    }

    /** Ends the opening; [quick] when the viewer tapped to skip it. */
    private fun finish(quick: Boolean) {
        if (finished) return
        finished = true
        timeline?.removeAllListeners()
        timeline?.cancel()
        if (quick) sound?.let { s ->
            // The music fades rather than stopping dead.
            val fadeOut = ValueAnimator.ofFloat(1f, 0f).setDuration(350)
            val start = Prefs.soundVolume(activity) / 100f
            fadeOut.addUpdateListener { a -> try { s.setVolume(start * a.animatedValue as Float, start * a.animatedValue as Float) } catch (e: Exception) { } }
            fadeOut.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { try { s.release() } catch (e: Exception) { }; if (sound === s) sound = null }
            })
            fadeOut.start()
        }
        animate().alpha(0f).setDuration(if (quick) 220 else 1).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onDone()
        }.start()
    }

    companion object {
        private const val LENGTH = 3600f

        /**
         * Plays the opening over [activity]'s window, unless the viewer turned it off or the phone is set to
         * remove animations; [then] runs once it is over (straight away when it does not play).
         */
        fun play(activity: Activity, then: () -> Unit): Boolean {
            if (!Prefs.openingAnimation(activity) || !ValueAnimator.areAnimatorsEnabled()) {
                then()
                return false
            }
            // The opening must never stop the app from opening: anything going wrong just skips it.
            return try {
                val intro = Intro(activity, then)
                (activity.window.decorView as ViewGroup).addView(intro, ViewGroup.LayoutParams(-1, -1))
                intro.post {
                    try {
                        intro.start()
                    } catch (e: Exception) {
                        intro.finish(quick = true)
                    }
                }
                true
            } catch (e: Exception) {
                then()
                false
            }
        }
    }
}
