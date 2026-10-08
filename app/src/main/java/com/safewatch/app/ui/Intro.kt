package com.safewatch.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.safewatch.app.R
import com.safewatch.app.data.Prefs

/**
 * The opening of the app: on a dark ground the sword of fire sweeps in, spinning, and catches around
 * the tree as it grows; the name draws itself in with a glint of light; then everything rushes toward the
 * viewer and the app is underneath. About two and a half seconds, with a short sound of its own. A tap
 * skips it.
 */
class Intro private constructor(private val activity: Activity, private val onDone: () -> Unit) : FrameLayout(activity) {
    private val glow = View(activity)
    private val fire = ImageView(activity).apply { setImageResource(R.drawable.ic_logo_fire) }
    private val tree = ImageView(activity).apply { setImageResource(R.drawable.ic_logo_tree) }
    private val spark = View(activity)
    private val word = TextView(activity)
    private val group = LinearLayout(activity)
    private var show: AnimatorSet? = null
    private var sound: MediaPlayer? = null
    private var finished = false

    init {
        setBackgroundColor(Color.parseColor("#04080A"))
        isClickable = true
        elevation = 1000f
        val mark = dp(176)
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(190).toFloat()
            colors = intArrayOf(Color.argb(150, 40, 150, 80), Color.argb(60, 255, 120, 30), Color.TRANSPARENT)
        }
        spark.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(110).toFloat()
            colors = intArrayOf(Color.argb(230, 255, 236, 190), Color.argb(80, 255, 170, 60), Color.TRANSPARENT)
        }
        word.apply {
            text = "EdenOS"
            textSize = 40f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }

        val logo = FrameLayout(activity).apply {
            clipChildren = false
            addView(glow, LayoutParams(dp(380), dp(380), Gravity.CENTER))
            addView(spark, LayoutParams(dp(220), dp(220), Gravity.CENTER))
            addView(fire, LayoutParams(mark, mark, Gravity.CENTER))
            addView(tree, LayoutParams(mark, mark, Gravity.CENTER))
        }
        val name = FrameLayout(activity).apply {
            addView(word, LayoutParams(-2, -2, Gravity.CENTER))
        }
        group.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(logo, LinearLayout.LayoutParams(dp(380), dp(300)))
            addView(name, LinearLayout.LayoutParams(dp(300), dp(70)).apply { topMargin = -dp(36) })
        }
        clipChildren = false
        addView(group, LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(40) })

        // The first frame: nothing yet but the dark.
        for (v in listOf(glow, spark, fire, tree, word)) v.alpha = 0f
        setOnClickListener { finish(quick = true) }
    }

    private fun start() {
        val anims = listOf(
            // The glow of the garden comes up behind.
            fade(glow, 0f, 0.9f, 0, 800, DecelerateInterpolator()),
            scale(glow, 0.5f, 1f, 0, 1000, DecelerateInterpolator(1.5f)),
            // The sword of fire sweeps in, turning fast, and slows as it settles around the tree.
            fade(fire, 0f, 1f, 0, 260, LinearInterpolator()),
            ObjectAnimator.ofFloat(fire, View.ROTATION, -540f, 0f).apply { duration = 1150; interpolator = DecelerateInterpolator(2.2f) },
            scale(fire, 0.3f, 1f, 0, 820, OvershootInterpolator(1.15f)),
            // The tree grows.
            fade(tree, 0f, 1f, 320, 520, DecelerateInterpolator()),
            scale(tree, 0.55f, 1f, 320, 700, DecelerateInterpolator(1.6f)),
            ObjectAnimator.ofFloat(tree, View.TRANSLATION_Y, dp(26).toFloat(), 0f).apply { startDelay = 320; duration = 700; interpolator = DecelerateInterpolator(1.6f) },
            // The fire catches: a flash at the heart, and the ring breathes out once.
            ObjectAnimator.ofPropertyValuesHolder(spark,
                PropertyValuesHolder.ofKeyframe(View.ALPHA, Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.25f, 0.95f), Keyframe.ofFloat(1f, 0f)),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1.5f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1.5f),
            ).apply { startDelay = 980; duration = 650; interpolator = DecelerateInterpolator() },
            ObjectAnimator.ofPropertyValuesHolder(fire,
                PropertyValuesHolder.ofKeyframe(View.SCALE_X, Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.35f, 1.09f), Keyframe.ofFloat(1f, 1f)),
                PropertyValuesHolder.ofKeyframe(View.SCALE_Y, Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.35f, 1.09f), Keyframe.ofFloat(1f, 1f)),
            ).apply { startDelay = 1000; duration = 520 },
            // It keeps turning, slowly, as on Home.
            ObjectAnimator.ofFloat(fire, View.ROTATION, 0f, 40f).apply { startDelay = 1150; duration = 1500; interpolator = LinearInterpolator() },
            // The name draws in, closing up from wide letters, and a glint of light crosses it.
            fade(word, 0f, 1f, 1000, 520, DecelerateInterpolator()),
            ValueAnimator.ofFloat(0.55f, 0.08f).apply {
                startDelay = 1000; duration = 760; interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { word.letterSpacing = it.animatedValue as Float }
            },
            ObjectAnimator.ofFloat(word, View.TRANSLATION_Y, dp(14).toFloat(), 0f).apply { startDelay = 1000; duration = 620; interpolator = DecelerateInterpolator() },
            // A glint of gold runs through the letters.
            ValueAnimator.ofFloat(-0.4f, 1.4f).apply {
                startDelay = 1450; duration = 700; interpolator = AccelerateInterpolator(0.6f)
                addUpdateListener {
                    val w = word.width.toFloat().coerceAtLeast(1f)
                    val x = (it.animatedValue as Float) * w
                    word.paint.shader = LinearGradient(x - w * 0.22f, 0f, x + w * 0.22f, 0f,
                        intArrayOf(Color.WHITE, Color.parseColor("#FFD98A"), Color.WHITE), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    word.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { word.paint.shader = null; word.invalidate() }
                })
            },
            // Then everything rushes toward the viewer, and the app is underneath.
            scale(group, 1f, 2.1f, 2120, 480, AccelerateInterpolator(1.7f)),
            fade(group, 1f, 0f, 2240, 360, AccelerateInterpolator()),
            ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f).apply { startDelay = 2300; duration = 380; interpolator = DecelerateInterpolator() },
        )
        show = AnimatorSet().apply {
            playTogether(anims)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish(quick = false)
            })
            start()
        }
        postDelayed({ playSound() }, 90)
    }

    private fun playSound() {
        if (finished || Prefs.soundPack(activity) == "off") return
        val volume = Prefs.soundVolume(activity) / 100f
        if (volume <= 0f) return
        try {
            sound = MediaPlayer.create(activity, R.raw.intro, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0)?.apply {
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
        show?.removeAllListeners()
        show?.cancel()
        if (quick) sound?.let { s -> try { s.setVolume(0f, 0f); s.release() } catch (e: Exception) { /* already released */ }; sound = null }
        animate().alpha(0f).setDuration(if (quick) 180 else 1).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onDone()
        }.start()
    }

    private fun fade(v: View, from: Float, to: Float, delay: Long, length: Long, ease: android.animation.TimeInterpolator) =
        ObjectAnimator.ofFloat(v, View.ALPHA, from, to).apply { startDelay = delay; duration = length; interpolator = ease }

    private fun scale(v: View, from: Float, to: Float, delay: Long, length: Long, ease: android.animation.TimeInterpolator) =
        ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to)).apply { startDelay = delay; duration = length; interpolator = ease }

    private fun dp(v: Int) = Ui.dp(activity, v)

    companion object {
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
