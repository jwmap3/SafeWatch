package com.safewatch.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.safewatch.app.R
import com.safewatch.app.data.Prefs

/**
 * The app's colours, with the viewer's own choices laid over the built-in
 * day and night sets.
 *
 * Three colours can be chosen: the primary (buttons and highlights), the
 * background, and the cards that sit on it. Text, dividers and control
 * backgrounds are worked out from the background so they stay readable
 * whatever is picked.
 */
object Palette {
    /** Choices offered for each colour. Card choices come in a dark and a light set to match the background. */
    val primaries = listOf(0xFF5B8CFF, 0xFFFF4D5E, 0xFFFF8A3D, 0xFFF5C542, 0xFF34C77B, 0xFF22C3C3, 0xFFA06BFF, 0xFFFF6FB5, 0xFFE8E8EE).map { it.toInt() }
    val backgrounds = listOf(0xFF09090C, 0xFF0B1020, 0xFF140E1C, 0xFF0B1411, 0xFF1A1210, 0xFFF5F5F7, 0xFFFAF6EE).map { it.toInt() }
    private val darkCards = listOf(0xFF16161A, 0xFF18203A, 0xFF241A33, 0xFF15261F, 0xFF2B1D19, 0xFF26262C).map { it.toInt() }
    private val lightCards = listOf(0xFFFFFFFF, 0xFFF1EADB, 0xFFE9EEF8, 0xFFEAF4EC).map { it.toInt() }

    private const val LIGHT_TEXT = 0xFFF4F4F6.toInt()
    private const val DARK_TEXT = 0xFF0E0E11.toInt()

    fun cardChoices(ctx: Context): List<Int> = if (isDark(ctx)) darkCards else lightCards

    /** Whether the app is currently showing light text on a dark background. */
    fun isDark(ctx: Context): Boolean {
        val background = Prefs.customColor(ctx, Prefs.COLOR_BACKGROUND)
        if (background != 0) return luminance(background) < 0.5
        return ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun color(ctx: Context, id: Int): Int {
        val primary = Prefs.customColor(ctx, Prefs.COLOR_PRIMARY)
        val background = Prefs.customColor(ctx, Prefs.COLOR_BACKGROUND)
        val card = Prefs.customColor(ctx, Prefs.COLOR_CARD)
        when (id) {
            R.color.accent -> if (primary != 0) return primary
            R.color.on_accent -> if (primary != 0) return textOn(primary)
            R.color.card -> if (card != 0) return card
        }
        if (background == 0) return ctx.getColor(id)
        val text = textOn(background)
        return when (id) {
            R.color.bg -> background
            R.color.card -> blend(background, text, 0.07f)
            R.color.bar -> blend(background, text, 0.035f)
            R.color.fill -> blend(background, text, 0.12f)
            R.color.separator -> blend(background, text, 0.15f)
            R.color.segment_selected -> blend(background, text, if (text == LIGHT_TEXT) 0.28f else 0f).let { if (text == DARK_TEXT) Color.WHITE else it }
            R.color.text -> text
            R.color.text_secondary -> blend(background, text, 0.6f)
            else -> ctx.getColor(id)
        }
    }

    private fun textOn(color: Int): Int = if (luminance(color) < 0.55) LIGHT_TEXT else DARK_TEXT

    private fun luminance(color: Int): Double =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
    )
}
