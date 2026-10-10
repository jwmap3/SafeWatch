package com.safewatch.app.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.safewatch.app.R
import com.safewatch.app.data.Prefs
import com.safewatch.app.player.PlayerActivity
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sheet
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import com.safewatch.core.tv.TvDevice

/**
 * What opens when a scrubbed movie is tapped: its cover and name, and four choices. Send to TV, edenTV mode,
 * Watch on Phone, and Delete. It is already scrubbed, so there is nothing more to clean here.
 */
@UnstableApi
object ScrubbedSheet {

    fun show(activity: Activity, copy: CleanCopyFile, onChanged: () -> Unit) {
        val sheet = Sheet(activity)
        options(activity, sheet, copy, onChanged)
        sheet.show()
    }

    private fun options(activity: Activity, sheet: Sheet, copy: CleanCopyFile, onChanged: () -> Unit) {
        val body = sheet.body
        body.addView(cover(activity, copy))
        body.addView(TextView(activity).apply {
            text = copy.title
            textSize = 21f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.color(activity, R.color.text))
            setPadding(Ui.dp(activity, 2), Ui.dp(activity, 14), 0, 0)
        })
        body.addView(TextView(activity).apply {
            text = meta(activity, copy)
            textSize = 13f
            setTextColor(Ui.color(activity, R.color.text_secondary))
            setPadding(Ui.dp(activity, 2), Ui.dp(activity, 2), 0, Ui.dp(activity, 16))
        })
        body.addView(LinearLayout(activity).apply {
            addView(choice(activity, "Send to TV", R.drawable.ic_cast, primary = true) {
                sheet.swap { pickTv(activity, sheet, copy, onChanged) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(choice(activity, "edenTV mode", R.drawable.ic_tv) {
                sheet.close { PlayerActivity.openCopy(activity, copy.file, copy.title, onTv = true) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(activity, 10) })
            addView(choice(activity, "Watch on Phone", R.drawable.ic_phone) {
                sheet.close { PlayerActivity.openCopy(activity, copy.file, copy.title) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(activity, 10) })
        })
        // Delete asks for a second tap rather than another box.
        val red = Color.rgb(224, 72, 72)
        lateinit var label: TextView
        var armed = false
        body.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.color(activity, R.color.fill), Ui.dp(activity, 14).toFloat())
            setPadding(0, Ui.dp(activity, 13), 0, Ui.dp(activity, 13))
            addView(Ui.icon(activity, R.drawable.ic_delete, sizeDp = 19).apply { imageTintList = android.content.res.ColorStateList.valueOf(red) })
            label = TextView(activity).apply {
                text = "Delete"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(red)
                setPadding(Ui.dp(activity, 8), 0, 0, 0)
            }
            addView(label)
            foreground = Ui.ripple(activity)
            Sheet.pressable(this)
            setOnClickListener {
                if (!armed) {
                    armed = true
                    label.text = "Tap again to delete"
                    background = Ui.rounded(Color.argb(40, 224, 72, 72), Ui.dp(activity, 14).toFloat())
                    postDelayed({ if (armed) { armed = false; label.text = "Delete"; background = Ui.rounded(Ui.color(activity, R.color.fill), Ui.dp(activity, 14).toFloat()) } }, 3500)
                } else {
                    armed = false
                    Sounds.play(activity, Sounds.TAP)
                    CleanCopy.delete(copy)
                    sheet.close { onChanged(); Ui.toast(activity, "Deleted") }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 12) })
    }

    /** The cover picture, wide, with the length in the corner. */
    private fun cover(activity: Activity, copy: CleanCopyFile): View = object : FrameLayout(activity) {
        // 16:9, whatever the phone's width.
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
        }
    }.apply {
        background = Ui.rounded(Ui.color(activity, R.color.fill), Ui.dp(activity, 18).toFloat())
        clipToOutline = true
        addView(Ui.logo(activity, 54).apply { alpha = 0.35f }, FrameLayout.LayoutParams(Ui.dp(activity, 54), Ui.dp(activity, 54), Gravity.CENTER))
        val picture = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        addView(picture, FrameLayout.LayoutParams(-1, -1))
        val thumb = copy.thumb
        if (thumb != null) Images.load(thumb.absolutePath, picture, minWidth = 640) { picture.animate().alpha(1f).setDuration(220).start() }
        else CleanCopy.makeMissingThumbs(activity, listOf(copy)) {
            copy.thumb?.let { Images.load(it.absolutePath, picture, minWidth = 640) { picture.animate().alpha(1f).setDuration(220).start() } }
        }
        if (copy.durationMs > 0) addView(TextView(activity).apply {
            text = Ui.time(copy.durationMs)
            textSize = 12f
            setTextColor(Color.WHITE)
            background = Ui.rounded(Color.argb(170, 0, 0, 0), Ui.dp(activity, 6).toFloat())
            setPadding(Ui.dp(activity, 7), Ui.dp(activity, 2), Ui.dp(activity, 7), Ui.dp(activity, 3))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.dp(activity, 10); bottomMargin = Ui.dp(activity, 10)
        })
    }

    private fun meta(activity: Activity, copy: CleanCopyFile): String {
        val days = Prefs.keepCopiesDays(activity)
        val kept = if (days <= 0) "Kept always" else {
            val left = ((copy.madeAt + days * 86_400_000L - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
            if (left == 0L) "Leaves today" else "Kept $left more ${if (left == 1L) "day" else "days"}"
        }
        return listOf("Scrubbed", copy.sizeText, kept).joinToString("  ·  ")
    }

    /** A large rounded button: an icon in a circle with a name under it. */
    private fun choice(activity: Activity, name: String, icon: Int, primary: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = name
            val accent = Ui.color(activity, R.color.accent)
            background = Ui.rounded(if (primary) accent else Ui.color(activity, R.color.fill), Ui.dp(activity, 18).toFloat())
            setPadding(Ui.dp(activity, 6), Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 14))
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (primary) Color.argb(46, 255, 255, 255) else Ui.color(activity, R.color.card))
                }
                addView(ImageView(activity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.WHITE else accent)
                }, FrameLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)))
            addView(TextView(activity).apply {
                text = name
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (primary) Color.WHITE else Ui.color(activity, R.color.text))
                setPadding(0, Ui.dp(activity, 9), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            foreground = Ui.ripple(activity)
            Sheet.pressable(this)
            setOnClickListener { Sounds.play(activity, Sounds.OPEN); onClick() }
        }

    // ---- Send to TV: the TVs on the Wi-Fi, found while the viewer watches ----

    private fun pickTv(activity: Activity, sheet: Sheet, copy: CleanCopyFile, onChanged: () -> Unit) {
        val body = sheet.body
        body.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconButton(activity, R.drawable.ic_back, "Back") {
                sheet.swap { options(activity, sheet, copy, onChanged) }
            })
            addView(TextView(activity).apply {
                text = "Send to TV"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Ui.color(activity, R.color.text))
            })
        })
        val list = Ui.card(activity).apply { background = Ui.rounded(Ui.color(activity, R.color.fill), Ui.dp(activity, 16).toFloat()) }
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 8) })
        val tvs = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        list.addView(tvs)
        val shown = HashSet<String>()
        fun add(device: TvDevice, note: String?) {
            if (!shown.add(device.host + device.controlUrl)) return
            if (tvs.childCount > 0) tvs.addView(Ui.divider(activity))
            val row = Ui.row(activity, device.name, note, leading = Ui.icon(activity, R.drawable.ic_tv, R.color.accent, 22).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(activity, 12)
            }) {
                sheet.close { TvActivity.sendCopy(activity, copy, device) }
            }
            row.alpha = 0f
            tvs.addView(row)
            row.animate().alpha(1f).setDuration(200).start()
        }
        TvActivity.lastTv(activity)?.let { add(it, "Last used") }
        val searching = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14), Ui.dp(activity, 16), Ui.dp(activity, 14))
            addView(ProgressBar(activity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(Ui.dp(activity, 20), Ui.dp(activity, 20)))
            addView(TextView(activity).apply {
                text = "Looking for TVs…"
                textSize = 15f
                setTextColor(Ui.color(activity, R.color.text_secondary))
                setPadding(Ui.dp(activity, 12), 0, 0, 0)
            })
        }
        list.addView(searching)
        Thread {
            val found = try { TvFinder.find(activity.applicationContext) } catch (e: Exception) { emptyList() }
            activity.runOnUiThread {
                if (activity.isDestroyed || searching.parent == null) return@runOnUiThread
                found.forEach { add(it, null) }
                list.removeView(searching)
                if (shown.isEmpty()) {
                    list.addView(TextView(activity).apply {
                        text = "No TV found. Turn the TV on, on the same Wi-Fi."
                        textSize = 15f
                        setTextColor(Ui.color(activity, R.color.text_secondary))
                        setPadding(Ui.dp(activity, 16), Ui.dp(activity, 16), Ui.dp(activity, 16), Ui.dp(activity, 6))
                    })
                    list.addView(Ui.row(activity, "Search again", chevron = false) { sheet.swap { pickTv(activity, sheet, copy, onChanged) } })
                }
            }
        }.start()
    }
}
