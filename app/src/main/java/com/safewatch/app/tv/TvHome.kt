package com.safewatch.app.tv

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.safewatch.app.R
import com.safewatch.app.data.Services
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sheet
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import java.io.File

/**
 * What the TV shows in edenTV mode: one row of apps across the middle of the screen (the viewer's streaming
 * services, YouTube, the browser and their scrubbed movies), each with its own icon. The phone's remote moves
 * along the row; the chosen app grows and the glow behind the row takes on its colour.
 */
class TvHome(context: Context, private val onChoose: (Choice) -> Unit) : FrameLayout(context) {
    sealed class Choice {
        data class Page(val url: String) : Choice()
        data class Service(val name: String, val url: String) : Choice()
        data class Copy(val file: File, val title: String) : Choice()
        object Search : Choice()
    }

    /** One tile in the row. [host] is the website whose icon it shows; [children] opens a second row instead. */
    private class App(
        val name: String,
        val host: String? = null,
        val iconRes: Int = 0,
        val color: Int = Color.parseColor("#3B6B52"),
        val choice: Choice? = null,
        val children: (() -> List<App>)? = null,
        val picture: String? = null,
        val wide: Boolean = false,
    )

    private val glow = View(context)
    private var glowColor = Color.parseColor("#2F5A44")
    private var glowAnim: ValueAnimator? = null
    private val strip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val row = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
    }
    private val heading = TextView(context).apply {
        textSize = 15f
        letterSpacing = 0.12f
        setTextColor(Color.argb(150, 255, 255, 255))
        gravity = Gravity.CENTER
    }
    private val name = TextView(context).apply {
        textSize = 34f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val hint = TextView(context).apply {
        text = "Swipe on your phone to move  ·  tap to open"
        textSize = 15f
        setTextColor(Color.argb(120, 255, 255, 255))
        gravity = Gravity.CENTER
    }

    private var apps: List<App> = emptyList()
    private val tiles = ArrayList<View>()
    private var index = 0
    private val levels = ArrayList<Pair<List<App>, Int>>() // where to go back to
    private var scrollAnim: ObjectAnimator? = null
    private var scrollCarry = 0f

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0B1410"), Color.parseColor("#040706")))
        clipChildren = false
        // A soft wash of colour behind the row, in the colour of the app that is picked.
        glow.background = glowDrawable(glowColor)
        addView(glow, LayoutParams(-1, -1))
        val side = dp(64)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, dp(36), side, 0)
            addView(Ui.logo(context, 46))
            addView(com.safewatch.app.ui.Brand.wordmark(context, 28f, onDark = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            addView(TextClock(context).apply {
                textSize = 26f
                setTextColor(Color.argb(220, 255, 255, 255))
            })
        }, LayoutParams(-1, -2, Gravity.TOP))

        strip.addView(row, ViewGroup.LayoutParams(-2, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(heading, LinearLayout.LayoutParams(-1, -2))
            addView(strip, LinearLayout.LayoutParams(-1, dp(260)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); leftMargin = side; rightMargin = side })
        }, LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dp(30) })
        addView(hint, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(30) })
        hint.animate().alpha(0f).setStartDelay(7000).setDuration(800).start()

        show(topApps(), 0, entering = true)
    }

    private fun topApps(): List<App> {
        val list = ArrayList<App>()
        Services.connected(context).forEach { s ->
            list += App(s.name, host = android.net.Uri.parse(s.homeUrl).host, choice = Choice.Service(s.name, s.homeUrl))
        }
        list += App("YouTube", host = "www.youtube.com", choice = Choice.Service("YouTube", "https://www.youtube.com/"))
        list += App("Browser", iconRes = R.drawable.ic_globe, color = Color.parseColor("#2E5BBA"), choice = Choice.Page("https://www.google.com/"))
        val copies = CleanCopy.all(context)
        if (copies.isNotEmpty()) list += App("Scrubbed Movies", iconRes = R.drawable.ic_logo, color = Color.parseColor("#2F7A55"), children = {
            CleanCopy.all(context).map { c -> App(c.title, choice = Choice.Copy(c.file, c.title), picture = c.thumb?.absolutePath, wide = true) }
        })
        return list
    }

    /** Puts [list] in the row, with [at] picked, sliding in from the side the viewer is going. */
    private fun show(list: List<App>, at: Int, entering: Boolean = false, deeper: Boolean = true) {
        apps = list
        tiles.clear()
        row.removeAllViews()
        heading.text = if (levels.isEmpty()) "" else levelName.uppercase()
        heading.visibility = if (levels.isEmpty()) View.GONE else View.VISIBLE
        index = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        list.forEachIndexed { i, app ->
            val tile = if (app.wide) movieTile(app) else appTile(app)
            tile.setOnClickListener { focus(i, sound = false); choose() }
            val on = i == index
            tile.alpha = 0f
            tile.translationX = if (entering) 0f else dp(if (deeper) 80 else -80).toFloat()
            tile.translationY = if (entering) dp(50).toFloat() else 0f
            row.addView(tile)
            tiles += tile
            tile.animate().alpha(if (on) 1f else 0.55f).scaleX(if (on) 1.16f else 1f).scaleY(if (on) 1.16f else 1f)
                .translationX(0f).translationY(0f)
                .setStartDelay((if (entering) 120L else 0L) + i * 45L).setDuration(460).setInterpolator(Sheet.EASE_OUT).start()
        }
        // Space either side so the first and last tiles can come to the middle.
        row.setPadding(dp(64), 0, dp(64), 0)
        post { focus(index, sound = false, instant = true) }
    }

    private var levelName = ""

    /** An app: its icon on a rounded square, filled with the colour at the icon's edges. */
    private fun appTile(app: App): View = FrameLayout(context).apply {
        contentDescription = app.name
        val size = dp(150)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(18); marginEnd = dp(18); gravity = Gravity.CENTER_VERTICAL }
        val face = Ui.rounded(app.color, dp(34).toFloat())
        background = face
        clipToOutline = true
        tag = app.color
        val letter = TextView(context).apply {
            text = app.name.take(1).uppercase()
            textSize = 56f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        if (app.iconRes != 0) {
            addView(ImageView(context).apply {
                setImageResource(app.iconRes)
                if (app.iconRes != R.drawable.ic_logo) imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }, LayoutParams(dp(74), dp(74), Gravity.CENTER))
        } else addView(letter, LayoutParams(-1, -1))
        val host = app.host
        if (host != null) {
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
            addView(image, LayoutParams(-1, -1))
            AppIcons.load(context, host) { icon ->
                image.setImageBitmap(icon.bitmap)
                val pad = if (icon.fullBleed) 0 else dp(30)
                image.setPadding(pad, pad, pad, pad)
                image.scaleType = if (icon.fullBleed) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                face.setColor(icon.edge)
                tag = icon.glow
                image.animate().alpha(1f).setDuration(250).start()
                letter.animate().alpha(0f).setDuration(250).start()
                if (tiles.getOrNull(index) === this) tint(icon.glow)
            }
        }
        foreground = GradientDrawable().apply { cornerRadius = dp(34).toFloat(); setStroke(dp(4), Color.TRANSPARENT) }
    }

    /** A scrubbed movie: its cover, wide. */
    private fun movieTile(app: App): View = FrameLayout(context).apply {
        contentDescription = app.name
        layoutParams = LinearLayout.LayoutParams(dp(300), dp(169)).apply { marginStart = dp(16); marginEnd = dp(16); gravity = Gravity.CENTER_VERTICAL }
        background = Ui.rounded(Color.parseColor("#1C2521"), dp(22).toFloat())
        clipToOutline = true
        tag = Color.parseColor("#2F7A55")
        addView(TextView(context).apply {
            text = app.name
            textSize = 20f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
        }, LayoutParams(-1, -1))
        app.picture?.let { path ->
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LayoutParams(-1, -1))
            Images.load(path, image, minWidth = 480)
        }
        foreground = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setStroke(dp(4), Color.TRANSPARENT) }
    }

    /** Moves the pick to tile [i]: it grows with a little spring, the others step back, and the row glides to keep it in the middle. */
    private fun focus(i: Int, sound: Boolean = true, instant: Boolean = false) {
        if (tiles.isEmpty()) { name.text = "No apps yet"; return }
        index = i.coerceIn(0, tiles.size - 1)
        tiles.forEachIndexed { n, t ->
            val on = n == index
            val scale = if (on) 1.16f else 1f
            // Straight after the row appears its own entrance animation sets the sizes, so only the outline changes.
            if (!instant) {
                t.animate().cancel()
                t.animate().scaleX(scale).scaleY(scale).alpha(if (on) 1f else 0.55f).translationX(0f).translationY(0f)
                    .setStartDelay(0).setDuration(if (on) 340 else 260)
                    .setInterpolator(if (on) OvershootInterpolator(1.6f) else Sheet.EASE_OUT).start()
            }
            t.translationZ = if (on) dp(16).toFloat() else 0f
            (t.foreground as? GradientDrawable)?.setStroke(dp(4), if (on) Color.WHITE else Color.TRANSPARENT)
        }
        val tile = tiles[index]
        // The name under the row changes with a quick fade and lift.
        val label = apps.getOrNull(index)?.name.orEmpty()
        if (name.text != label) {
            name.animate().cancel()
            if (instant) { name.text = label; name.alpha = 1f; name.translationY = 0f }
            else name.animate().alpha(0f).translationY(dp(6).toFloat()).setDuration(90).withEndAction {
                name.text = label
                name.translationY = -dp(6).toFloat()
                name.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Sheet.EASE_OUT).start()
            }.start()
        }
        (tile.tag as? Int)?.let { tint(it) }
        center(tile, instant)
        if (sound) Sounds.play(context, Sounds.TAP)
        tile.isFocusable = true
    }

    /** Glides the row so [tile] sits in the middle of the TV. */
    private fun center(tile: View, instant: Boolean) {
        val target = (tile.left + tile.width / 2 - strip.width / 2).coerceAtLeast(0)
        scrollAnim?.cancel()
        if (instant) { strip.scrollTo(target, 0); return }
        scrollAnim = ObjectAnimator.ofInt(strip, "scrollX", strip.scrollX, target).apply {
            duration = 380
            interpolator = Sheet.EASE_OUT
            start()
        }
    }

    /** The glow behind the row fades to [color]. */
    private fun tint(color: Int) {
        if (color == glowColor) return
        glowAnim?.cancel()
        val from = glowColor
        glowColor = color
        glowAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = 500
            addUpdateListener { glow.background = glowDrawable(it.animatedValue as Int) }
            start()
        }
    }

    private fun glowDrawable(color: Int) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = (resources.displayMetrics.widthPixels * 0.55f).coerceAtLeast(dp(400).toFloat())
        setGradientCenter(0.5f, 0.55f)
        colors = intArrayOf(Color.argb(115, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT)
    }

    // ---- Worked from the phone ----

    fun move(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) focus(index - 1) else nudge(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < tiles.size - 1) focus(index + 1) else nudge(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (levels.isNotEmpty()) back() else nudge(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0)
        }
    }

    /** At the end of the row: a small bump, so the viewer can feel there is no more. */
    private fun nudge(direction: Int) {
        val tile = tiles.getOrNull(index) ?: return
        tile.animate().cancel()
        tile.animate().translationX(dp(14).toFloat() * direction).translationY(if (direction == 0) dp(8).toFloat() else 0f).setDuration(90)
            .withEndAction { tile.animate().translationX(0f).translationY(0f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start() }.start()
    }

    fun choose() {
        val app = apps.getOrNull(index) ?: return
        val tile = tiles.getOrNull(index)
        val kids = app.children
        // A quick press, then the app opens.
        tile?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(90)?.withEndAction {
            tile.animate().scaleX(1.16f).scaleY(1.16f).setDuration(200).setInterpolator(OvershootInterpolator(2f)).start()
            if (kids != null) {
                levels += apps to index
                levelName = app.name
                show(kids(), 0)
            } else app.choice?.let(onChoose)
        }?.start()
        Sounds.play(context, Sounds.OPEN)
    }

    /** Back: out of a second row to the apps. Returns false at the top, where there is nowhere to go back to. */
    fun back(): Boolean {
        val (list, at) = levels.removeLastOrNull() ?: return false
        levelName = ""
        show(list, at, deeper = false)
        return true
    }

    fun toTop() {
        if (levels.isNotEmpty()) {
            val first = levels.first()
            levels.clear()
            levelName = ""
            show(first.first, first.second, deeper = false)
        } else focus(0)
    }

    /** The phone's scroll strip: every so far along moves the pick one tile. */
    fun scrollBy(dy: Float) {
        scrollCarry += dy
        val step = dp(90)
        while (scrollCarry > step) { scrollCarry -= step; move(KeyEvent.KEYCODE_DPAD_RIGHT) }
        while (scrollCarry < -step) { scrollCarry += step; move(KeyEvent.KEYCODE_DPAD_LEFT) }
    }

    /** A click from the phone's pointer, at a place on the TV. */
    fun clickAt(x: Float, y: Float) {
        val here = IntArray(2).also { getLocationOnScreen(it) }
        val at = IntArray(2)
        tiles.forEachIndexed { i, t ->
            t.getLocationOnScreen(at)
            val left = at[0] - here[0]
            val top = at[1] - here[1]
            if (x >= left && x < left + t.width * t.scaleX && y >= top && y < top + t.height * t.scaleY) {
                focus(i, sound = false)
                choose()
                return
            }
        }
    }

    private fun dp(v: Int) = Ui.dp(context, v)
}
