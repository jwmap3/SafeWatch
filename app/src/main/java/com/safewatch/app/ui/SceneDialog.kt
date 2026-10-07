package com.safewatch.app.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.safewatch.core.Action
import com.safewatch.core.Category
import com.safewatch.core.Tag

/** Asks what to do with a scene the viewer has just marked. */
object SceneDialog {
    private val choices = listOf(
        "Nudity – blur it" to (Category.NUDITY to Action.BLUR),
        "Nudity – skip it" to (Category.NUDITY to Action.SKIP),
        "Language – mute it" to (Category.LANGUAGE to Action.MUTE),
    )

    fun show(activity: Activity, startMs: Long, endMs: Long, onSave: (Tag) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("${Ui.time(startMs)} – ${Ui.time(endMs)}")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                val (category, action) = choices[which].second
                onSave(Tag(startMs, endMs, category, action, level = 3, source = Tag.SOURCE_MANUAL))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
