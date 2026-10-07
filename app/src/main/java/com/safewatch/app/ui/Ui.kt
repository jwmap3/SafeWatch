package com.safewatch.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.safewatch.app.R

/**
 * The app's small design kit: grouped cards on a quiet background, large
 * titles, pill controls. Every colour comes from the colour resources, which
 * have a day and a night version.
 */
object Ui {
    fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, id: Int): Int = ctx.getColor(id)

    fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun ripple(ctx: Context): Drawable? {
        val value = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ctx.getDrawable(value.resourceId)
    }

    fun isNight(ctx: Context): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Draws behind the system bars and keeps [root]'s content clear of them and of the keyboard. */
    fun fitSystemBars(activity: Activity, root: View, lightBars: Boolean = !isNight(activity)) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** A scrolling page. Returns the view to show and the column to add content to. */
    fun page(activity: Activity): Pair<View, LinearLayout> {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 40))
        }
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(color(activity, R.color.bg))
            isFillViewport = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fitSystemBars(activity, scroll)
        return scroll to column
    }

    fun largeTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 34f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.02f
        setTextColor(color(ctx, R.color.text))
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 4))
    }

    fun subtitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(color(ctx, R.color.text_secondary))
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 13f
        letterSpacing = 0.04f
        setTextColor(color(ctx, R.color.text_secondary))
        setPadding(dp(ctx, 16), dp(ctx, 28), dp(ctx, 16), dp(ctx, 8))
    }

    fun caption(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(dp(ctx, 2).toFloat(), 1f)
        setTextColor(color(ctx, R.color.text_secondary))
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), 0)
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color(ctx, R.color.card), dp(ctx, 14).toFloat())
        clipToOutline = true
    }

    fun divider(ctx: Context, inset: Int = 16): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.separator))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(ctx, 1) / 2)).apply {
            marginStart = dp(ctx, inset)
        }
    }

    /** A circle with a letter in it, used instead of service logos. */
    fun monogram(ctx: Context, name: String): TextView = TextView(ctx).apply {
        text = name.take(1).uppercase()
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(color(ctx, R.color.accent))
        background = rounded(color(ctx, R.color.fill), dp(ctx, 16).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { marginEnd = dp(ctx, 12) }
    }

    /** One line in a card: a title, an optional value on the right, and a chevron when it opens something. */
    fun row(
        ctx: Context,
        title: String,
        value: String? = null,
        leading: View? = null,
        chevron: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 52)
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        if (leading != null) addView(leading)
        addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(color(ctx, R.color.text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) addView(TextView(ctx).apply {
            text = value
            textSize = 16f
            maxLines = 1
            setTextColor(color(ctx, R.color.text_secondary))
            setPadding(dp(ctx, 8), 0, 0, 0)
        })
        if (chevron && onClick != null) addView(TextView(ctx).apply {
            text = "›"
            textSize = 22f
            setTextColor(color(ctx, R.color.text_secondary))
            alpha = 0.6f
            setPadding(dp(ctx, 8), 0, 0, dp(ctx, 3))
        })
        if (onClick != null) {
            foreground = ripple(ctx)
            setOnClickListener { onClick() }
        }
    }

    fun switchRow(ctx: Context, title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val toggle = SwitchCompat(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        return row(ctx, title, chevron = false).apply {
            addView(toggle)
            foreground = ripple(ctx)
            setOnClickListener { toggle.toggle() }
        }
    }

    /** A row of choices where exactly one is selected. */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val pad = dp(ctx, 2)
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(ctx, R.color.fill), dp(ctx, 10).toFloat())
            setPadding(pad, pad, pad, pad)
        }
        val cells = ArrayList<TextView>()
        fun paint(index: Int) {
            cells.forEachIndexed { i, cell ->
                val on = i == index
                cell.background = if (on) rounded(color(ctx, R.color.segment_selected), dp(ctx, 8).toFloat()) else null
                cell.elevation = if (on) dp(ctx, 1).toFloat() else 0f
                cell.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            }
        }
        labels.forEachIndexed { i, label ->
            val cell = TextView(ctx).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(color(ctx, R.color.text))
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                setOnClickListener { paint(i); onSelect(i) }
            }
            cells += cell
            track.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return track
    }

    /** Wraps a view with the standard inner margin of a card. */
    fun inset(ctx: Context, child: View, top: Int = 12, bottom: Int = 12): FrameLayout = FrameLayout(ctx).apply {
        setPadding(dp(ctx, 14), dp(ctx, top), dp(ctx, 14), dp(ctx, bottom))
        addView(child, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun pill(ctx: Context, text: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(color(ctx, if (filled) R.color.on_accent else R.color.accent))
        background = rounded(color(ctx, if (filled) R.color.accent else R.color.fill), dp(ctx, 18).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        setOnClickListener { onClick() }
    }

    /** A plain text button for toolbars. */
    fun barButton(ctx: Context, text: String, size: Float = 16f, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(color(ctx, R.color.accent))
        minWidth = dp(ctx, 44)
        minHeight = dp(ctx, 44)
        setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        foreground = ripple(ctx)
        setOnClickListener { onClick() }
    }

    fun spacer(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    /**
     * Hides a picture. Newer phones blur it; older ones cannot blur a live
     * view, so a black [cover] is shown over it instead.
     */
    fun setHidden(target: View, cover: View, hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.setRenderEffect(if (hidden) RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP) else null)
        } else {
            cover.visibility = if (hidden) View.VISIBLE else View.GONE
        }
    }

    /**
     * Opens the phone's own screen-casting panel. Mirroring the screen sends
     * the already filtered picture and sound, so every filter keeps working on the TV.
     */
    fun sendToTv(activity: Activity) {
        for (action in listOf(Settings.ACTION_CAST_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)) {
            try {
                activity.startActivity(Intent(action))
                return
            } catch (e: Exception) {
                // Try the next screen.
            }
        }
        toast(activity, "Open screen casting from your phone's quick settings")
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
