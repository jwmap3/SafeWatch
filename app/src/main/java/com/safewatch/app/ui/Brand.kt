package com.safewatch.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.graphics.Path
import com.safewatch.app.R

/**
 * The edenOS look: two leaves that make a lowercase e (an emerald one and a sunlit one), and the name set
 * in a light geometric face with "OS" in gold.
 */
object Brand {
    /** The two leaves, in a 100 by 100 square: the emerald one is the bowl and bar of the e, the sunlit one its tail. */
    const val UPPER_LEAF = "M26,66 C14,52 18,29 36,22 C52,16 71,21 81,34 C72,42 60,45 48,46 C38,47 30,54 26,66 Z"
    const val LOWER_LEAF = "M33,72 C39,60 50,55 63,55 C70,55 76,56 81,58 C77,73 64,82 51,82 C43,82 36,78 33,72 Z"

    val EMERALD = intArrayOf(Color.parseColor("#7FE3A9"), Color.parseColor("#22B573"), Color.parseColor("#0C7F57"))
    val SUNLIT = intArrayOf(Color.parseColor("#FFE07A"), Color.parseColor("#FFC145"), Color.parseColor("#7CCB4E"))
    val GOLD = intArrayOf(Color.parseColor("#FFC145"), Color.parseColor("#F5873A"))
    val DEEP_GREEN = Color.parseColor("#0E6E4F")
    val MIST = Color.parseColor("#F2FFF6")

    private var face: Typeface? = null

    /** Poppins Light, the name's face (SIL Open Font License; see docs/fonts). */
    fun typeface(ctx: Context): Typeface = face ?: (try { ctx.resources.getFont(R.font.poppins_light) } catch (e: Exception) { null }
        ?: Typeface.create("sans-serif-light", Typeface.NORMAL)).also { face = it }

    /** A path from SVG path data with absolute M, L, C and Z, as the leaves are written. */
    fun path(data: String): Path {
        val path = Path()
        val tokens = Regex("[MLCZmlcz]|-?\\d*\\.?\\d+").findAll(data).map { it.value }.toList()
        var i = 0
        var command = 'M'
        fun num() = tokens[i++].toFloat()
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && tok[0].isLetter()) { command = tok[0].uppercaseChar(); i++; if (command == 'Z') { path.close(); continue } }
            when (command) {
                'M' -> { path.moveTo(num(), num()); command = 'L' }
                'L' -> path.lineTo(num(), num())
                'C' -> path.cubicTo(num(), num(), num(), num(), num(), num())
                else -> i++
            }
        }
        return path
    }

    /** The name, "edenOS", sized [sizeSp]; [onDark] sets "eden" in pale mist instead of deep green. */
    fun wordmark(ctx: Context, sizeSp: Float, onDark: Boolean = Palette.isDark(ctx)): Wordmark = Wordmark(ctx, sizeSp, onDark)
}

/** "eden" in green (or mist on dark grounds) and "OS" in a gold gradient, drawn exactly, with no padding of its own. */
class Wordmark(context: Context, sizeSp: Float, private val onDark: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Brand.typeface(context)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        letterSpacing = 0.02f
    }
    /** Extra space between the letters, in ems, for the opening's letters closing up. */
    var spacing = 0.02f
        set(value) { field = value; paint.letterSpacing = value; requestLayout(); invalidate() }
    /** Where a glint of light runs through the letters, from 0 (left) to 1 (right); off outside that. */
    var glint = -1f
        set(value) { field = value; invalidate() }

    init {
        contentDescription = "edenOS"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText("edenOS")
        val fm = paint.fontMetrics
        setMeasuredDimension((w + 2).toInt(), (fm.descent - fm.ascent).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = -paint.fontMetrics.ascent
        val eden = paint.measureText("eden")
        val os = paint.measureText("OS")
        val x0 = (width - (eden + os)) / 2f
        paint.shader = null
        paint.color = if (onDark) Brand.MIST else Brand.DEEP_GREEN
        canvas.drawText("eden", x0, baseline, paint)
        paint.shader = LinearGradient(x0 + eden, 0f, x0 + eden + os, 0f, Brand.GOLD[0], Brand.GOLD[1], Shader.TileMode.CLAMP)
        canvas.drawText("OS", x0 + eden, baseline, paint)
        if (glint in 0f..1f) {
            // A narrow band of light passing over the letters.
            val total = eden + os
            val at = x0 + total * glint
            paint.shader = LinearGradient(at - total * 0.14f, 0f, at + total * 0.14f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(220, 255, 246, 214), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawText("edenOS", x0, baseline, paint)
        }
        paint.shader = null
    }
}
