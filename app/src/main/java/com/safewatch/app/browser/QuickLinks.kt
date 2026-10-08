package com.safewatch.app.browser

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.safewatch.app.R
import com.safewatch.app.data.Prefs
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Palette
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui

/**
 * The browser's own start screen: six quick links the viewer chooses, as large tiles with each
 * site's icon. Tap to open; press and hold to change or remove; an empty tile adds one.
 */
class QuickLinks(context: Context, private val onOpen: (String) -> Unit) : FrameLayout(context) {
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Ui.color(context, R.color.bg))
        isClickable = true // nothing behind it is touched
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
        }
        // The logo, in the viewer's colour, as on Home.
        column.addView(FrameLayout(context).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_logo_halo)
                setColorFilter(Ui.color(context, R.color.accent))
            }, FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64), Gravity.CENTER))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_logo_eye)
                setColorFilter(Ui.color(context, R.color.accent))
            }, FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64), Gravity.CENTER))
        }, LinearLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64)).apply { bottomMargin = Ui.dp(context, 28) })
        column.addView(grid, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(context).apply {
            text = "Press and hold a link to change it."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.color(context, R.color.text_secondary))
            alpha = 0.7f
            setPadding(0, Ui.dp(context, 20), 0, 0)
        })
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        refresh()
    }

    fun refresh() {
        grid.removeAllViews()
        val links = Prefs.quickLinks(context)
        for (rowStart in 0 until 6 step 3) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
            for (i in rowStart until rowStart + 3) {
                row.addView(tile(links.getOrNull(i), i), LinearLayout.LayoutParams(0, -2, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 18) })
        }
    }

    private fun tile(link: Pair<String, String>?, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = Ui.dp(context, 64)
        val box = FrameLayout(context).apply {
            background = Ui.rounded(if (link == null) Color.TRANSPARENT else Ui.color(context, R.color.card), Ui.dp(context, 20).toFloat())
            if (link == null) {
                // An empty slot: an outline with a plus.
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 20).toFloat()
                    setStroke(Ui.dp(context, 2), Ui.color(context, R.color.text_secondary) and 0x55FFFFFF)
                }
                addView(TextView(context).apply {
                    text = "+"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.color(context, R.color.text_secondary))
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                // The site's first letter until (or unless) its icon arrives.
                val letter = TextView(context).apply {
                    text = link.first.take(1).uppercase()
                    textSize = 24f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setTextColor(Palette.color(context, R.color.accent))
                }
                addView(letter, FrameLayout.LayoutParams(-1, -1))
                val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                addView(icon, FrameLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36), Gravity.CENTER))
                val host = Uri.parse(link.second).host.orEmpty()
                if (host.isNotEmpty()) {
                    // Once the site's icon is in, the letter behind it is not needed.
                    Images.load("https://www.google.com/s2/favicons?domain=$host&sz=128", icon, minWidth = 64, clear = true) {
                        letter.visibility = View.INVISIBLE
                    }
                }
            }
        }
        addView(box, LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply {
            text = link?.first ?: "Add"
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(Ui.color(context, if (link == null) R.color.text_secondary else R.color.text))
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        isClickable = true
        isLongClickable = true
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.color(context, R.color.fill)), null, null)
        setOnClickListener {
            Sounds.play(context, Sounds.TAP)
            if (link == null) edit(index, null) else onOpen(link.second)
        }
        setOnLongClickListener {
            if (link == null) edit(index, null) else choose(index, link)
            true
        }
    }

    private fun choose(index: Int, link: Pair<String, String>) {
        AlertDialog.Builder(context)
            .setTitle(link.first)
            .setItems(arrayOf("Change", "Remove")) { _, which ->
                if (which == 0) edit(index, link) else save(index, null)
            }
            .show()
    }

    private fun edit(index: Int, link: Pair<String, String>?) {
        val pad = Ui.dp(context, 20)
        val name = EditText(context).apply {
            hint = "Name"
            setText(link?.first.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val address = EditText(context).apply {
            hint = "Website, such as pbskids.org"
            setText(link?.second.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(context, 8), pad, 0)
            addView(name)
            addView(address)
        }
        AlertDialog.Builder(context)
            .setTitle(if (link == null) "Add a quick link" else "Change quick link")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val typed = address.text.toString().trim()
                if (typed.isEmpty()) return@setPositiveButton
                val url = if (typed.startsWith("http://") || typed.startsWith("https://")) typed else "https://$typed"
                val label = name.text.toString().trim().ifEmpty {
                    Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m.").substringBefore('.').replaceFirstChar { it.uppercase() }
                }
                save(index, label to url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(index: Int, link: Pair<String, String>?) {
        val slots = Prefs.quickLinks(context).map<Pair<String, String>, Pair<String, String>?> { it }.toMutableList()
        while (slots.size < 6) slots += null
        slots[index] = link
        Prefs.setQuickLinks(context, slots.filterNotNull())
        refresh()
    }
}
