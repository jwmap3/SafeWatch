package com.safewatch.app

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Accounts
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.detect.ModelSetup
import com.safewatch.app.ui.Ui
import com.safewatch.core.Action
import com.safewatch.core.FilterSettings
import com.safewatch.core.Strictness
import com.safewatch.core.WordList

/** The settings tab: the viewer's accounts, what to mute, what to hide, and how the app looks. */
class FiltersScreen(private val activity: MainActivity) {

    val view: View
    private val column: LinearLayout
    private var settings: FilterSettings = Prefs.settings(activity)
    private val levels = listOf(Strictness.OFF, Strictness.LOW, Strictness.MEDIUM, Strictness.HIGH)
    private val levelNames = listOf("Off", "Low", "Medium", "High")

    init {
        val (page, col) = Ui.page(activity)
        view = page
        column = col
        rebuild()
    }

    private fun update(changed: FilterSettings) {
        settings = changed
        Prefs.save(activity, changed)
    }

    fun rebuild() {
        val ctx = activity
        val scrolled = view.scrollY
        column.removeAllViews()
        view.post { view.scrollTo(0, scrolled) }
        column.addView(Ui.largeTitle(ctx, "Settings"))
        column.addView(Ui.subtitle(ctx, "Set once. Applied to everything you watch here."))

        // Accounts
        val services = Services.connected(ctx)
        column.addView(Ui.sectionHeader(ctx, "Your accounts"))
        column.addView(Ui.card(ctx).apply {
            services.forEach { service ->
                val signedIn = Accounts.isSignedIn(ctx, service)
                addView(Ui.row(ctx, service.name, if (signedIn) "Signed in" else "Sign in", leading = Ui.monogram(ctx, service.name)) {
                    WatchActivity.signIn(ctx, service)
                })
                addView(Ui.divider(ctx, 60))
            }
            addView(Ui.row(ctx, if (services.isEmpty()) "Add services" else "Add or remove services", chevron = false) {
                activity.editServices { rebuild() }
            })
        })
        column.addView(Ui.caption(ctx,
            "Sign in once on each service's own page. The sign-in is kept on this phone, the way a browser keeps it, " +
                "so titles open straight into that service. SafeWatch never sees your password."))

        // Language
        column.addView(Ui.sectionHeader(ctx, "Language"))
        val languageNote = Ui.caption(ctx, languageText(settings.language))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, levelNames, levels.indexOf(settings.language)) {
                update(settings.copy(language = levels[it]))
                rebuild()
            }))
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Blasphemy", settings.blasphemy) { update(settings.copy(blasphemy = it)); rebuild() })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Choose words", wordsSummary()) { WordsActivity.open(ctx) })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Extra words to mute", count(settings.customWords)) {
                editWords("Extra words to mute", settings.customWords) { update(settings.copy(customWords = it)); rebuild() }
            })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Words to allow", count(settings.allowedWords)) {
                editWords("Words to allow", settings.allowedWords) { update(settings.copy(allowedWords = it)); rebuild() }
            })
        })
        column.addView(languageNote)

        // Nudity
        column.addView(Ui.sectionHeader(ctx, "Nudity"))
        val nudityNote = Ui.caption(ctx, nudityText(settings.nudity))
        val model = ModelSetup.stateFor(ctx)
        val installed = model == ModelSetup.State.READY
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, levelNames, levels.indexOf(settings.nudity)) {
                update(settings.copy(nudity = levels[it]))
                nudityNote.text = nudityText(levels[it])
            }))
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "When found", chevron = false).apply {
                addView(Ui.segmented(ctx, listOf("Blur", "Skip"), if (settings.nudityAction == Action.SKIP) 1 else 0) {
                    update(settings.copy(nudityAction = if (it == 1) Action.SKIP else Action.BLUR))
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 150), -2))
            })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Detection", when (model) {
                ModelSetup.State.READY -> "Ready"
                ModelSetup.State.DOWNLOADING -> "Setting up…"
                else -> "Not set up"
            }) {
                when (model) {
                    ModelSetup.State.READY -> Ui.toast(ctx, "Nudity detection is ready")
                    ModelSetup.State.DOWNLOADING -> Ui.toast(ctx, "Still downloading. Check back in a moment.")
                    else -> setUpDetection()
                }
            })
        })
        column.addView(nudityNote)
        if (installed && settings.nudity != Strictness.OFF) {
            column.addView(Ui.sectionHeader(ctx, ""))
            column.addView(Ui.card(ctx).apply {
                addView(Ui.row(ctx, "Test the blur", chevron = false) {
                    Prefs.startBlurTest(ctx)
                    Ui.toast(ctx, "For two minutes, faces are blurred too. Play any video with people in it.")
                })
            })
            column.addView(Ui.caption(ctx, "A way to see the blur working without playing anything explicit."))
        }
        if (!installed) column.addView(Ui.caption(ctx,
            "Automatic detection downloads a small file (about 11 MB) the first time. Tap Detection if it has not finished."))

        // Catalog
        val hasKey = Prefs.catalogKey(ctx).isNotEmpty()
        column.addView(Ui.sectionHeader(ctx, "Titles"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "TMDB catalog key", if (hasKey) "Added" else "Not added") { editCatalogKey() })
        })
        column.addView(Ui.caption(ctx,
            "Optional. Without it, Home shows what is new on each service. With a free key from themoviedb.org " +
                "(Settings > API), it also shows movies and the most popular titles on each service."))

        // Appearance
        column.addView(Ui.sectionHeader(ctx, "Appearance"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, listOf("Automatic", "Light", "Dark"), Prefs.themeMode(ctx)) {
                Prefs.setThemeMode(ctx, it)
            }))
        })
        column.addView(Ui.caption(ctx, "Automatic follows your phone's day and night setting."))
    }

    /** Offers to download the detection file again, or to pick one already on the phone. */
    private fun setUpDetection() {
        AlertDialog.Builder(activity)
            .setTitle("Set up nudity detection")
            .setMessage("SafeWatch needs to download its detection file, about 11 MB. It is only downloaded once.")
            .setPositiveButton("Download") { _, _ ->
                ModelSetup.ensure(activity)
                rebuild()
            }
            .setNeutralButton("Choose a file") { _, _ -> activity.pickModel.launch(arrayOf("*/*")) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Called when the tab comes into view, so changes made on other screens show. */
    fun onShown() {
        settings = Prefs.settings(activity)
        rebuild()
    }

    private fun wordsSummary(): String =
        if (settings.language == Strictness.OFF) "Off"
        else "${WordList.groups.count { settings.mutes(it) }} of ${WordList.groups.size}"

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

    private fun textDialog(title: String, initial: String, hint: String, lines: Int, onSave: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            minLines = lines
        }
        val holder = FrameLayout(activity).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editCatalogKey() = textDialog("TMDB catalog key", Prefs.catalogKey(activity), "Paste your key", 1) {
        Prefs.setCatalogKey(activity, it)
        rebuild()
    }

    private fun editWords(title: String, words: Set<String>, onSave: (Set<String>) -> Unit) =
        textDialog(title, words.sorted().joinToString(", "), "Separate with commas", 2) { text ->
            onSave(text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
        }
}
