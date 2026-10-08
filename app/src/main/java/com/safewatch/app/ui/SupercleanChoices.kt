package com.safewatch.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.safewatch.app.R
import com.safewatch.core.Superclean

/**
 * The list of what Superclean can take out, grouped as VidAngel groups its filters (language, sex, nudity,
 * kissing, immodesty, violence, alcohol and drugs, and the rest), with ready-made sets to start from.
 */
object SupercleanChoices {

    /** "22 of 34 chosen", for a row's value. */
    fun summary(ids: Set<String>): String {
        val n = Superclean.CHOICES.count { it.id in ids }
        return if (n == 0) "None" else if (n == Superclean.CHOICES.size) "Everything" else "$n of ${Superclean.CHOICES.size}"
    }

    /** Opens the list with [initial] ticked; [onSave] gets what is ticked when the viewer saves. */
    fun edit(activity: Activity, title: String, initial: Set<String>, onSave: (Set<String>) -> Unit) {
        val ctx = activity
        val boxes = LinkedHashMap<String, CheckBox>()
        val accent = Ui.color(ctx, R.color.accent)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 12))
        }
        column.addView(TextView(ctx).apply {
            text = "Start from"
            textSize = 13f
            setTextColor(Ui.color(ctx, R.color.text_secondary))
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 4), Ui.dp(ctx, 24), Ui.dp(ctx, 6))
        })
        val presets = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 12), 0)
        }
        fun tick(ids: Set<String>) = boxes.forEach { (id, box) -> box.isChecked = id in ids }
        Superclean.PRESETS.forEach { (name, ids) -> presets.addView(Ui.chip(ctx, name, strong = true) { tick(ids) }) }
        presets.addView(Ui.chip(ctx, "None", strong = true) { tick(emptySet()) })
        column.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(presets)
        })
        for (group in Superclean.GROUPS) {
            column.addView(TextView(ctx).apply {
                text = group.uppercase()
                textSize = 13f
                letterSpacing = 0.04f
                setTextColor(Ui.color(ctx, R.color.text_secondary))
                setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 18), Ui.dp(ctx, 24), Ui.dp(ctx, 4))
            })
            for (choice in Superclean.CHOICES.filter { it.group == group }) {
                val box = CheckBox(ctx).apply {
                    isChecked = choice.id in initial
                    buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accent, Ui.color(ctx, R.color.text_secondary)))
                    contentDescription = choice.name
                }
                boxes[choice.id] = box
                column.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 6), Ui.dp(ctx, 14), Ui.dp(ctx, 6))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ctx).apply {
                            text = choice.name
                            textSize = 16f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(Ui.color(ctx, R.color.text))
                        })
                        addView(TextView(ctx).apply {
                            text = choice.detail
                            textSize = 13f
                            setTextColor(Ui.color(ctx, R.color.text_secondary))
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(box)
                    foreground = Ui.ripple(ctx)
                    setOnClickListener { box.toggle() }
                })
            }
        }
        column.addView(TextView(ctx).apply {
            text = "Kissing and romance are treated alike for every couple."
            textSize = 13f
            setTextColor(Ui.color(ctx, R.color.text_secondary))
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 16), Ui.dp(ctx, 24), 0)
        })
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(column) })
            .setPositiveButton("Save") { _, _ -> onSave(boxes.filterValues { it.isChecked }.keys.toSet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
