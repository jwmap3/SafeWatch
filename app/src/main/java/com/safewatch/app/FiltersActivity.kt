package com.safewatch.app

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.data.Prefs
import com.safewatch.app.detect.NudityDetector
import com.safewatch.app.ui.Ui
import com.safewatch.core.Action
import com.safewatch.core.FilterSettings
import com.safewatch.core.Strictness

/** The filter settings: what to mute, what to hide, and how the app looks. */
class FiltersActivity : AppCompatActivity() {

    private lateinit var settings: FilterSettings
    private val levels = listOf(Strictness.OFF, Strictness.LOW, Strictness.MEDIUM, Strictness.HIGH)
    private val levelNames = listOf("Off", "Low", "Medium", "High")

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Ui.toast(this, "Checking the model…")
        Thread {
            val ok = NudityDetector.install(this, uri)
            runOnUiThread {
                Ui.toast(this, if (ok) "Nudity detection is ready" else "That file is not a model this app can use")
                build()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Prefs.settings(this)
        build()
    }

    private fun update(changed: FilterSettings) {
        settings = changed
        Prefs.save(this, changed)
    }

    private fun build() {
        val (page, column) = Ui.page(this)
        column.addView(Ui.barButton(this, "‹ Home") { finish() }.apply { setPadding(0, 0, Ui.dp(context, 10), 0) },
            LinearLayout.LayoutParams(-2, -2))
        column.addView(Ui.largeTitle(this, "Filters"))

        // Language
        column.addView(Ui.sectionHeader(this, "Language"))
        val languageNote = Ui.caption(this, languageText(settings.language))
        column.addView(Ui.card(this).apply {
            addView(Ui.inset(context, Ui.segmented(context, levelNames, levels.indexOf(settings.language)) {
                update(settings.copy(language = levels[it]))
                languageNote.text = languageText(levels[it])
            }))
            addView(Ui.divider(context))
            addView(Ui.switchRow(context, "Blasphemy", settings.blasphemy) { update(settings.copy(blasphemy = it)) })
            addView(Ui.divider(context))
            addView(Ui.row(context, "Extra words to mute", count(settings.customWords)) {
                editWords("Extra words to mute", settings.customWords) { update(settings.copy(customWords = it)); build() }
            })
            addView(Ui.divider(context))
            addView(Ui.row(context, "Words to allow", count(settings.allowedWords)) {
                editWords("Words to allow", settings.allowedWords) { update(settings.copy(allowedWords = it)); build() }
            })
        })
        column.addView(languageNote)

        // Nudity
        column.addView(Ui.sectionHeader(this, "Nudity"))
        val nudityNote = Ui.caption(this, nudityText(settings.nudity))
        val installed = NudityDetector.isInstalled(this)
        column.addView(Ui.card(this).apply {
            addView(Ui.inset(context, Ui.segmented(context, levelNames, levels.indexOf(settings.nudity)) {
                update(settings.copy(nudity = levels[it]))
                nudityNote.text = nudityText(levels[it])
            }))
            addView(Ui.divider(context))
            addView(Ui.row(context, "When found", chevron = false).apply {
                addView(Ui.segmented(context, listOf("Blur", "Skip"), if (settings.nudityAction == Action.SKIP) 1 else 0) {
                    update(settings.copy(nudityAction = if (it == 1) Action.SKIP else Action.BLUR))
                }, LinearLayout.LayoutParams(Ui.dp(context, 150), -2))
            })
            addView(Ui.divider(context))
            addView(Ui.row(context, "Detection model", if (installed) "Installed" else "Not installed") {
                pickModel.launch(arrayOf("*/*"))
            })
        })
        column.addView(nudityNote)
        if (!installed) column.addView(Ui.caption(this,
            "Automatic detection needs the NudeNet model file (320n.onnx). Tap Detection model to import it. " +
                "Scenes you mark yourself work without it."))

        // Appearance
        column.addView(Ui.sectionHeader(this, "Appearance"))
        column.addView(Ui.card(this).apply {
            addView(Ui.inset(context, Ui.segmented(context, listOf("Automatic", "Light", "Dark"), Prefs.themeMode(context)) {
                Prefs.setThemeMode(context, it)
            }))
        })
        column.addView(Ui.caption(this, "Automatic follows your phone's day and night setting."))

        setContentView(page)
    }

    private fun count(words: Set<String>): String = if (words.isEmpty()) "None" else words.size.toString()

    private fun languageText(s: Strictness): String = when (s) {
        Strictness.OFF -> "Nothing is muted."
        Strictness.LOW -> "Mutes the strongest profanity."
        Strictness.MEDIUM -> "Mutes strong profanity and common swearing."
        Strictness.HIGH -> "Also mutes mild words such as damn and hell."
    }

    private fun nudityText(s: Strictness): String = when (s) {
        Strictness.OFF -> "Nothing is hidden."
        Strictness.LOW -> "Hides explicit nudity."
        Strictness.MEDIUM -> "Hides explicit nudity, bare breasts and bare buttocks."
        Strictness.HIGH -> "Also hides swimwear, underwear and shirtless scenes."
    }

    private fun editWords(title: String, words: Set<String>, onSave: (Set<String>) -> Unit) {
        val input = EditText(this).apply {
            setText(words.sorted().joinToString(", "))
            hint = "Separate with commas"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        val holder = FrameLayout(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text.toString().split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
