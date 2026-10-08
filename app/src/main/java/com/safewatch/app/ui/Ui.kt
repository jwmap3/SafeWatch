package com.safewatch.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.safewatch.app.R
import com.safewatch.app.data.Video

/**
 * The app's small design kit: grouped cards on a quiet background, large
 * titles, pill controls. Every colour comes from the colour resources, which
 * have a day and a night version.
 */
object Ui {
    fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** A colour by name, with the viewer's own colour choices applied. */
    fun color(ctx: Context, id: Int): Int = Palette.color(ctx, id)

    fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun ripple(ctx: Context): Drawable? {
        val value = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ctx.getDrawable(value.resourceId)
    }

    fun isNight(ctx: Context): Boolean = Palette.isDark(ctx)

    /** Draws behind the system bars and keeps [root]'s content clear of them and of the keyboard. */
    fun fitSystemBars(activity: Activity, root: View, lightBars: Boolean = !isNight(activity)) {
        // Asking for the window's frame first makes sure it exists; without this the app
        // crashed when a screen was laid out before it was first shown.
        activity.window.decorView
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
    /**
     * A scrolling page. Returns the view to show and the column to add content to.
     * [padded] pages keep content off the screen edges; unpadded ones let shelves run edge to edge.
     * [ownWindow] is for a page that fills a whole screen by itself and so has to avoid the system bars.
     */
    fun page(activity: Activity, padded: Boolean = true, ownWindow: Boolean = false): Pair<ScrollView, LinearLayout> {
        val side = if (padded) dp(activity, 20) else 0
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, dp(activity, 32))
        }
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(color(activity, R.color.bg))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (ownWindow) fitSystemBars(activity, scroll)
        return scroll to column
    }

    fun largeTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 34f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.02f
        setTextColor(color(ctx, R.color.text))
        setPadding(0, dp(ctx, 18), 0, dp(ctx, 4))
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
            setOnClickListener { Sounds.play(ctx, Sounds.TAP); onClick() }
        }
    }

    fun switchRow(ctx: Context, title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val accent = color(ctx, R.color.accent)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = SwitchCompat(ctx).apply {
            thumbTintList = ColorStateList(states, intArrayOf(accent, color(ctx, R.color.text_secondary)))
            trackTintList = ColorStateList(states, intArrayOf((accent and 0x00FFFFFF) or (0x66 shl 24), color(ctx, R.color.fill)))
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
                setOnClickListener { Sounds.play(ctx, Sounds.TAP); paint(i); onSelect(i) }
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

    /** One of the app's line icons, tinted. */
    fun icon(ctx: Context, drawable: Int, colorRes: Int = R.color.text, sizeDp: Int = 24): ImageView = ImageView(ctx).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color(ctx, colorRes))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    /** A round, tappable icon for toolbars. */
    fun iconButton(ctx: Context, drawable: Int, label: String, colorRes: Int = R.color.text, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            contentDescription = label
            addView(icon(ctx, drawable, colorRes), FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 46))
            foreground = ripple(ctx)
            setOnClickListener { Sounds.play(ctx, Sounds.TAP); onClick(it) }
        }

    /** The main action on a screen: a wide, filled button with an optional icon. */
    fun actionButton(ctx: Context, text: String, filled: Boolean = true, iconRes: Int? = null, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val fg = if (filled) R.color.on_accent else R.color.text
            background = rounded(color(ctx, if (filled) R.color.accent else R.color.fill), dp(ctx, 12).toFloat())
            setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 18), dp(ctx, 13))
            if (iconRes != null) addView(icon(ctx, iconRes, fg, 20).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(ctx, 6)
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(ctx, fg))
            })
            foreground = ripple(ctx)
            setOnClickListener { onClick() }
        }

    /** A small rounded label, used for the row of services. */
    fun chip(ctx: Context, text: String, strong: Boolean = false, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(ctx, if (strong) R.color.text else R.color.text_secondary))
        background = rounded(color(ctx, if (strong) R.color.card else R.color.fill), dp(ctx, 18).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(ctx, 8) }
        setOnClickListener { Sounds.play(ctx, Sounds.TAP); onClick() }
    }

    /** Artwork for one title. Shows the name until the picture arrives, and stays that way if there is none. */
    fun poster(ctx: Context, name: String, url: String?, widthDp: Int, onClick: (() -> Unit)? = null): FrameLayout =
        FrameLayout(ctx).apply {
            background = rounded(color(ctx, R.color.fill), dp(ctx, 10).toFloat())
            clipToOutline = true
            contentDescription = name
            addView(TextView(ctx).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 4
                setTextColor(color(ctx, R.color.text_secondary))
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            }, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (url != null) Images.load(url, this)
            }, FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), dp(ctx, widthDp * 3 / 2))
            if (onClick != null) {
                foreground = ripple(ctx)
                setOnClickListener { Sounds.play(ctx, Sounds.OPEN); onClick() }
            }
        }

    /** A 16:9 video picture with its length in the corner. With no video it is an empty placeholder. */
    private fun videoPicture(ctx: Context, video: Video?, widthDp: Int): FrameLayout = FrameLayout(ctx).apply {
        background = rounded(color(ctx, R.color.fill), dp(ctx, 10).toFloat())
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), dp(ctx, widthDp * 9 / 16))
        if (video == null) return@apply
        addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            Images.load(video.thumbnail, this, minWidth = 320)
        }, FrameLayout.LayoutParams(-1, -1))
        if (video.duration.isNotEmpty()) addView(TextView(ctx).apply {
            text = video.duration
            textSize = 11f
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(190, 0, 0, 0), dp(ctx, 5).toFloat())
            setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 2))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(ctx, 6)
            bottomMargin = dp(ctx, 6)
        })
    }

    private fun videoText(ctx: Context, video: Video, titleSize: Float): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            text = CleanText.of(ctx, video.title)
            textSize = titleSize
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(ctx, R.color.text))
        })
        addView(TextView(ctx).apply {
            text = listOf(video.channel, video.meta).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(ctx, R.color.text_secondary))
            setPadding(0, dp(ctx, 3), 0, 0)
        })
    }

    /** A video for a sideways shelf: the picture with its title underneath. */
    fun videoCard(ctx: Context, video: Video?, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 224), -2).apply { marginEnd = dp(ctx, 12) }
        addView(videoPicture(ctx, video, 224))
        if (video == null) return@apply
        addView(videoText(ctx, video, 14f).apply { setPadding(0, dp(ctx, 8), 0, 0) })
        contentDescription = video.title
        setOnClickListener { Sounds.play(ctx, Sounds.OPEN); onClick() }
    }

    /** A video for an up-and-down list: the picture on the left, its title beside it. */
    fun videoRow(ctx: Context, video: Video, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), 0)
        addView(videoPicture(ctx, video, 150))
        addView(videoText(ctx, video, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(ctx, 12) })
        contentDescription = video.title
        foreground = ripple(ctx)
        setOnClickListener { Sounds.play(ctx, Sounds.OPEN); onClick() }
    }

    /** A shelf heading. */
    fun shelfTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 19f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.01f
        setTextColor(color(ctx, R.color.text))
        setPadding(dp(ctx, 20), dp(ctx, 26), dp(ctx, 20), dp(ctx, 12))
    }

    /** A top-to-bottom fade from clear to [colorTo], laid over artwork so text on it stays readable. */
    fun fade(colorTo: Int): GradientDrawable {
        val clear = colorTo and 0x00FFFFFF
        return GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            // Clear over the top of the picture, solid behind the title and buttons.
            setColors(
                intArrayOf(clear, clear, clear or (0xB8 shl 24), clear or (0xF2 shl 24), colorTo, colorTo),
                floatArrayOf(0f, 0.38f, 0.60f, 0.72f, 0.82f, 1f),
            )
        }
    }

    /**
     * A row of colour dots to choose from. The first dot stands for the built-in colour
     * and is picked as 0; [selected] is 0 when nothing has been chosen.
     */
    fun swatches(ctx: Context, colors: List<Int>, selected: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val strip = LinearLayout(ctx).apply { setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 6), dp(ctx, 14)) }
        val ring = color(ctx, R.color.text)
        for (c in listOf(0) + colors) {
            val on = c == selected
            strip.addView(FrameLayout(ctx).apply {
                contentDescription = if (c == 0) "Built-in colour" else "Colour %06X".format(c and 0xFFFFFF)
                // The chosen dot gets a ring around it.
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    if (on) setStroke(dp(ctx, 2), ring)
                }
                addView(TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    textSize = 11f
                    if (c == 0) {
                        text = "Auto"
                        setTextColor(color(ctx, R.color.text_secondary))
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (c == 0) color(ctx, R.color.fill) else c)
                        setStroke(maxOf(1, dp(ctx, 1) / 2), color(ctx, R.color.separator))
                    }
                }, FrameLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener { onPick(c) }
            })
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    /** A label above a control inside a card. */
    fun fieldLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(color(ctx, R.color.text))
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 6))
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
        AlertDialog.Builder(activity)
            .setTitle("Send to TV")
            .setMessage("Your phone's screen casting works with Chromecast, Roku, Fire TV and most smart TVs. " +
                "The TV shows exactly what is on this screen, filters included.\n\n" +
                "Netflix-style services often show a black picture when cast wirelessly. An HDMI adapter always works. " +
                "Apple TV cannot be reached from an Android phone this way.")
            .setPositiveButton("Cast screen") { _, _ -> openCastPanel(activity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCastPanel(activity: Activity) {
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
