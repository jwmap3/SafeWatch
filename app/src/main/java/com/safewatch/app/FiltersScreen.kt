package com.safewatch.app

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Accounts
import com.safewatch.app.data.FilterLog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.detect.ModelSetup
import com.safewatch.app.ui.Palette
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.SupercleanChoices
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

    /** Which page of Settings is showing: the list of sections, or one section. Kept across a recreate (a new colour). */
    private var section: String
        get() = shownSection
        set(value) { shownSection = value }

    fun rebuild() {
        val scrolled = view.scrollY
        column.removeAllViews()
        view.post { view.scrollTo(0, scrolled) }
        when (section) {
            ACCOUNTS -> accounts()
            LANGUAGE -> language()
            NUDITY -> nudity()
            SUPERCLEAN -> superclean()
            BROWSER -> browser()
            PLAYER -> player()
            APP -> app()
            LOOK -> look()
            else -> main()
        }
    }

    /** Opens a section, sliding it in from the side. */
    private fun open(which: String) {
        section = which
        rebuild()
        view.scrollTo(0, 0)
        slide(from = 1)
    }

    /** Starts again from the list of sections, as when Settings is opened from another tab. */
    fun toList() {
        if (keepSection) keepSection = false else section = ""
    }

    /** Back from a section to the list. Returns false when already on the list. */
    fun back(): Boolean {
        if (section.isEmpty()) return false
        section = ""
        rebuild()
        view.scrollTo(0, 0)
        slide(from = -1)
        return true
    }

    private fun slide(from: Int) {
        column.alpha = 0f
        column.translationX = Ui.dp(activity, 36).toFloat() * from
        column.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(com.safewatch.app.ui.Sheet.EASE_OUT).start()
    }

    // ---- The list of sections ----

    private fun main() {
        val ctx = activity
        column.addView(Ui.largeTitle(ctx, "Settings"))
        val services = Services.connected(ctx)
        val signedIn = services.count { Accounts.isSignedIn(ctx, it) }
        fun group(vararg rows: View) = column.addView(Ui.card(ctx).apply {
            rows.forEachIndexed { i, r -> if (i > 0) addView(Ui.divider(ctx, 62)); addView(r) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 18) })

        group(
            entry("Your accounts", R.drawable.ic_user, 0xFFE0524A.toInt(),
                if (services.isEmpty()) "Add services" else "$signedIn of ${services.size} signed in", ACCOUNTS),
        )
        group(
            entry("Language", R.drawable.ic_mute, 0xFF3D7DF0.toInt(), levelNames[levels.indexOf(settings.language)], LANGUAGE),
            entry("Nudity", R.drawable.ic_eye, 0xFF8A5CF0.toInt(),
                if (settings.nudity == Strictness.OFF) "Off" else levelNames[levels.indexOf(settings.nudity)] +
                    if (settings.nudityAction == Action.SKIP) " · Skip" else " · Blur", NUDITY),
            entry("Superclean", R.drawable.ic_sparkle, 0xFF1FA36B.toInt(),
                if (Prefs.claudeKey(ctx).isEmpty()) "Add a key" else keepText(Prefs.keepCopiesDays(ctx)), SUPERCLEAN),
        )
        group(
            entry("Browser", R.drawable.ic_globe, 0xFF2E8FD6.toInt(), if (Prefs.blockAdult(ctx)) "Protected" else "Open", BROWSER),
            entry("Player", R.drawable.ic_play, 0xFFF08A24.toInt(), "${Prefs.skipSeconds(ctx)} s skip", PLAYER),
            entry("Home and tabs", R.drawable.ic_grid, 0xFF6B7280.toInt(),
                "Opens on " + (mapOf(MainActivity.TAB_BROWSER to "Browser", MainActivity.TAB_YOUTUBE to "YouTube")[Prefs.startTab(ctx)] ?: "Home"), APP),
            entry("Look and sound", R.drawable.ic_palette, 0xFFD6467E.toInt(), listOf("Automatic", "Light", "Dark").getOrElse(Prefs.themeMode(ctx)) { "Automatic" }, LOOK),
        )
        val pin = Prefs.settingsPin(ctx)
        group(
            Ui.row(ctx, "Lock Settings with a PIN", if (pin.isEmpty()) "Off" else "On", leading = badge(R.drawable.ic_lock, 0xFF4B5563.toInt())) { editPin() },
        )
        column.addView(Ui.caption(ctx, "Applied to everything you watch in edenOS."))
    }

    /** A row in the list of sections: a coloured icon, the name, and what it is set to now. */
    private fun entry(title: String, icon: Int, colour: Int, value: String, which: String): View =
        Ui.row(activity, title, value, leading = badge(icon, colour)) { open(which) }

    private fun badge(icon: Int, colour: Int): View = FrameLayout(activity).apply {
        background = Ui.rounded(colour, Ui.dp(activity, 9).toFloat())
        addView(android.widget.ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        }, FrameLayout.LayoutParams(Ui.dp(activity, 19), Ui.dp(activity, 19), android.view.Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 32)).apply { marginEnd = Ui.dp(activity, 14) }
    }

    /** The top of a section: Back, and its name. */
    private fun header(title: String) {
        column.addView(LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(activity, 8), 0, 0)
            addView(Ui.iconButton(activity, R.drawable.ic_back, "Back") { back() }.apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 44)).apply { marginStart = -Ui.dp(activity, 10) }
            })
            addView(android.widget.TextView(activity).apply {
                text = "Settings"
                textSize = 16f
                setTextColor(Ui.color(activity, R.color.accent))
                setOnClickListener { back() }
            })
        })
        column.addView(Ui.largeTitle(activity, title).apply { setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 4)) })
    }

    // ---- Sections ----

    private fun accounts() {
        val ctx = activity
        header("Your accounts")
        val services = Services.connected(ctx)
        column.addView(Ui.sectionHeader(ctx, "Services"))
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
        column.addView(Ui.caption(ctx, "You sign in on each service's own page. edenOS never sees your password."))
        val hasKey = Prefs.catalogKey(ctx).isNotEmpty()
        column.addView(Ui.sectionHeader(ctx, "Titles on Home"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "TMDB catalog key", if (hasKey) "Added" else "Optional") { editCatalogKey() })
        })
        column.addView(Ui.caption(ctx, "A free key from themoviedb.org adds movies and popular titles to Home."))
    }

    private fun language() {
        val ctx = activity
        header("Language")
        val note = Ui.caption(ctx, languageText(settings.language))
        column.addView(Ui.sectionHeader(ctx, "Mute"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, levelNames, levels.indexOf(settings.language)) {
                update(settings.copy(language = levels[it]))
                rebuild()
            }))
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Blasphemy", settings.blasphemy) { update(settings.copy(blasphemy = it)); rebuild() })
        })
        column.addView(note)
        column.addView(Ui.sectionHeader(ctx, "Words"))
        column.addView(Ui.card(ctx).apply {
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
        if (settings.language != Strictness.OFF) {
            column.addView(Ui.sectionHeader(ctx, "Captions"))
            column.addView(Ui.card(ctx).apply {
                addView(Ui.switchRow(ctx, "Show captions", Prefs.showCaptions(ctx)) { Prefs.setShowCaptions(ctx, it) })
                addView(Ui.divider(ctx))
                addView(Ui.switchRow(ctx, "No captions, no sound", Prefs.silentWithoutCaptions(ctx)) { Prefs.setSilentWithoutCaptions(ctx, it) })
                addView(Ui.divider(ctx))
                addView(Ui.row(ctx, "Filter report") { showReport() })
            })
            column.addView(Ui.caption(ctx, "Cursing is found from captions. A video without them plays silent if you choose."))
        }
    }

    private fun nudity() {
        val ctx = activity
        header("Nudity")
        val note = Ui.caption(ctx, nudityText(settings.nudity))
        val model = ModelSetup.stateFor(ctx)
        val installed = model == ModelSetup.State.READY
        column.addView(Ui.sectionHeader(ctx, "Hide"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, levelNames, levels.indexOf(settings.nudity)) {
                update(settings.copy(nudity = levels[it]))
                note.text = nudityText(levels[it])
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
        column.addView(note)
        if (installed && settings.nudity != Strictness.OFF) {
            column.addView(Ui.sectionHeader(ctx, "More"))
            column.addView(Ui.card(ctx).apply {
                addView(Ui.switchRow(ctx, "Look ahead", Prefs.lookAhead(ctx)) { Prefs.setLookAhead(ctx, it) })
                addView(Ui.divider(ctx))
                addView(Ui.row(ctx, "Test the blur", chevron = false) {
                    Prefs.startBlurTest(ctx)
                    Ui.toast(ctx, "For two minutes, faces are blurred too. Play any video with people in it.")
                })
            })
            column.addView(Ui.caption(ctx, "Look ahead blurs a scene just before it starts. It uses about twice the data."))
        }
        if (!installed) column.addView(Ui.caption(ctx, "Detection downloads once, about 11 MB."))
    }

    private fun browser() {
        val ctx = activity
        header("Browser")
        column.addView(Ui.sectionHeader(ctx, "Search"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, ENGINES.map { it.second }, ENGINES.indexOfFirst { it.first == Prefs.searchEngine(ctx) }.coerceAtLeast(0)) {
                Prefs.setSearchEngine(ctx, ENGINES[it].first)
                com.safewatch.app.browser.BrowserActivity.searchPrefix = Prefs.searchPrefix(ctx)
            }))
        })
        column.addView(Ui.caption(ctx, "Safe search is always on."))
        column.addView(Ui.sectionHeader(ctx, "Block"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.switchRow(ctx, "Adult websites", Prefs.blockAdult(ctx)) { Prefs.setBlockAdult(ctx, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Twitter, Reddit and Instagram", Prefs.blockSocial(ctx)) { Prefs.setBlockSocial(ctx, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Pop-ups and redirects", Prefs.blockPopups(ctx)) { Prefs.setBlockPopups(ctx, it) })
        })
        column.addView(Ui.caption(ctx, "Lock Settings with a PIN to keep these on."))
        column.addView(Ui.sectionHeader(ctx, "Layout"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.switchRow(ctx, "Hide the bar while scrolling", Prefs.hideBarWhileScrolling(ctx)) { Prefs.setHideBarWhileScrolling(ctx, it) })
        })
        column.addView(Ui.caption(ctx, "Press and hold a quick link to change it."))
    }

    private fun player() {
        val ctx = activity
        header("Player")
        column.addView(Ui.sectionHeader(ctx, "Skip buttons"))
        column.addView(Ui.card(ctx).apply {
            val jumps = listOf(10, 15, 30)
            addView(Ui.inset(ctx, Ui.segmented(ctx, jumps.map { "$it seconds" }, jumps.indexOf(Prefs.skipSeconds(ctx)).coerceAtLeast(0)) {
                Prefs.setSkipSeconds(ctx, jumps[it])
            }))
        })
        column.addView(Ui.sectionHeader(ctx, "Hidden pictures"))
        column.addView(Ui.card(ctx).apply {
            val styles = listOf("blur" to "Blurred", "black" to "Black")
            addView(Ui.inset(ctx, Ui.segmented(ctx, styles.map { it.second }, styles.indexOfFirst { it.first == Prefs.hideStyle(ctx) }.coerceAtLeast(0)) {
                Prefs.setHideStyle(ctx, styles[it].first)
            }))
        })
        column.addView(Ui.caption(ctx, "Blurred lets you follow along; Black shows nothing."))
    }

    private fun app() {
        val ctx = activity
        header("Home and tabs")
        column.addView(Ui.sectionHeader(ctx, "Open on"))
        column.addView(Ui.card(ctx).apply {
            val tabs = listOf(MainActivity.TAB_HOME to "Home", MainActivity.TAB_BROWSER to "Browser", MainActivity.TAB_YOUTUBE to "YouTube")
            addView(Ui.inset(ctx, Ui.segmented(ctx, tabs.map { it.second }, tabs.indexOfFirst { it.first == Prefs.startTab(ctx) }.coerceAtLeast(0)) {
                Prefs.setStartTab(ctx, tabs[it].first)
            }))
        })
        val hiddenTabs = Prefs.hiddenTabs(ctx)
        column.addView(Ui.sectionHeader(ctx, "Tabs"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Arrange tabs",
                if (hiddenTabs.isEmpty()) "All shown" else "${MainActivity.TAB_ORDER.size - hiddenTabs.size} of ${MainActivity.TAB_ORDER.size} shown") { arrangeTabs() })
            addView(Ui.divider(ctx))
            addView(Ui.inset(ctx, Ui.segmented(ctx, listOf("Icons and names", "Icons only"), if (Prefs.tabNames(ctx)) 0 else 1) {
                Prefs.setTabNames(ctx, it == 0)
                activity.rebuildTabBar()
            }))
        })
    }

    private fun look() {
        val ctx = activity
        header("Look and sound")
        column.addView(Ui.sectionHeader(ctx, "Mode"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, listOf("Automatic", "Light", "Dark"), Prefs.themeMode(ctx)) {
                // Choosing a mode goes back to that mode's own background and cards.
                Prefs.setCustomColor(ctx, Prefs.COLOR_BACKGROUND, 0)
                Prefs.setCustomColor(ctx, Prefs.COLOR_CARD, 0)
                Prefs.setThemeMode(ctx, it)
                keepSection = true
                activity.recreate()
            }))
        })
        column.addView(Ui.sectionHeader(ctx, "Colours"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.fieldLabel(ctx, "Primary"))
            addView(Ui.swatches(ctx, Palette.primaries, Prefs.customColor(ctx, Prefs.COLOR_PRIMARY)) { pickColor(Prefs.COLOR_PRIMARY, it) })
            addView(Ui.divider(ctx))
            addView(Ui.fieldLabel(ctx, "Background"))
            addView(Ui.swatches(ctx, Palette.backgrounds, Prefs.customColor(ctx, Prefs.COLOR_BACKGROUND)) { pickColor(Prefs.COLOR_BACKGROUND, it) })
            addView(Ui.divider(ctx))
            addView(Ui.fieldLabel(ctx, "Cards"))
            addView(Ui.swatches(ctx, Palette.cardChoices(ctx), Prefs.customColor(ctx, Prefs.COLOR_CARD)) { pickColor(Prefs.COLOR_CARD, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Opening animation", Prefs.openingAnimation(ctx)) { Prefs.setOpeningAnimation(ctx, it) })
        })
        column.addView(Ui.sectionHeader(ctx, "Sounds"))
        val packs = Sounds.available(ctx)
        column.addView(Ui.card(ctx).apply {
            addView(Ui.inset(ctx, Ui.segmented(ctx, packs.map { it.name }, packs.indexOfFirst { it.id == Prefs.soundPack(ctx) }.coerceAtLeast(0)) {
                Prefs.setSoundPack(ctx, packs[it].id)
                Sounds.play(ctx, Sounds.OPEN)
            }))
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Volume", chevron = false).apply {
                addView(android.widget.SeekBar(ctx).apply {
                    max = 100
                    progress = Prefs.soundVolume(ctx)
                    val accent = android.content.res.ColorStateList.valueOf(Ui.color(ctx, R.color.accent))
                    progressTintList = accent
                    thumbTintList = accent
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                            if (fromUser) Prefs.setSoundVolume(ctx, value)
                        }
                        override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                        override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Sounds.play(ctx, Sounds.TAP)
                    })
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 200), -2))
            })
        })
    }

    private fun superclean() {
        val ctx = activity
        header("Superclean")
        val claudeKey = Prefs.claudeKey(ctx)
        val claudeModel = Prefs.claudeModel(ctx)
        column.addView(Ui.subtitle(ctx, "Claude goes through a movie and takes out what you choose."))

        column.addView(Ui.sectionHeader(ctx, "Choices"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "What it takes out", SupercleanChoices.summary(Prefs.supercleanChoices(ctx))) {
                SupercleanChoices.edit(activity, "What Superclean takes out", Prefs.supercleanChoices(ctx)) {
                    Prefs.setSupercleanChoices(ctx, it)
                    rebuild()
                }
            })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Scenes", chevron = false).apply {
                addView(Ui.segmented(ctx, listOf("Cut out", "Blur"), if (Prefs.supercleanCut(ctx)) 0 else 1) {
                    Prefs.setSupercleanCut(ctx, it == 0)
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 170), -2))
            })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Use the IMDb Parents Guide", Prefs.supercleanGuide(ctx)) { Prefs.setSupercleanGuide(ctx, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Review before each one", Prefs.supercleanReview(ctx)) { Prefs.setSupercleanReview(ctx, it) })
        })

        column.addView(Ui.sectionHeader(ctx, "Keep scrubbed movies"))
        column.addView(Ui.card(ctx).apply {
            val days = listOf(1, 7, 30, 0)
            addView(Ui.inset(ctx, Ui.segmented(ctx, listOf("1 day", "1 week", "1 month", "Always"),
                days.indexOf(Prefs.keepCopiesDays(ctx)).coerceAtLeast(0)) {
                Prefs.setKeepCopiesDays(ctx, days[it])
            }))
        })
        column.addView(Ui.caption(ctx, "Then they are deleted to free up space."))

        column.addView(Ui.sectionHeader(ctx, "Claude"))
        column.addView(Ui.card(ctx).apply {
            val builtIn = com.safewatch.app.BuildConfig.CLAUDE_KEY.isNotEmpty() && claudeKey == com.safewatch.app.BuildConfig.CLAUDE_KEY
            addView(Ui.row(ctx, "Claude API key", if (claudeKey.isEmpty()) "Not added" else if (builtIn) "Built in" else "Added") { editClaudeKey() })
            if (claudeKey.isNotEmpty()) {
                addView(Ui.divider(ctx))
                addView(Ui.row(ctx, "Model", chevron = false).apply {
                    val models = listOf(com.safewatch.core.ClaudeApi.SONNET to "Sonnet", com.safewatch.core.ClaudeApi.HAIKU to "Haiku")
                    addView(Ui.segmented(ctx, models.map { it.second }, models.indexOfFirst { it.first == claudeModel }.coerceAtLeast(0)) {
                        Prefs.setClaudeModel(ctx, models[it].first)
                        rebuild()
                    }, LinearLayout.LayoutParams(Ui.dp(ctx, 170), -2))
                })
                addView(Ui.divider(ctx))
                val spent = Prefs.supercleanSpentCents(ctx)
                addView(Ui.row(ctx, "Estimated spent", "$" + String.format(java.util.Locale.US, "%.2f", spent / 100.0)) {
                    AlertDialog.Builder(activity).setTitle("Estimated spend")
                        .setMessage("Worked out from each Superclean on this phone. Your real balance is at console.anthropic.com.")
                        .setPositiveButton("Reset") { _, _ -> Prefs.resetSupercleanSpent(activity); rebuild() }
                        .setNegativeButton("Close", null).show()
                })
            }
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Superclean a video link") { supercleanLink() })
        })
        if (claudeKey.isNotEmpty()) column.addView(Ui.caption(ctx,
            "About ${com.safewatch.app.tv.SupercleanRun.costText(3_600_000L, claudeModel)} an hour of video. " +
                "Pictures and captions go only to Anthropic."))
    }

    private fun keepText(days: Int) = when (days) {
        0 -> "Kept always"
        1 -> "Kept 1 day"
        7 -> "Kept 1 week"
        30 -> "Kept 1 month"
        else -> "Kept $days days"
    }

    private fun pickColor(which: String, color: Int) {
        Prefs.setCustomColor(activity, which, color)
        if (which == Prefs.COLOR_BACKGROUND) {
            // Cards are chosen to suit the background, so a new background starts them afresh.
            Prefs.setCustomColor(activity, Prefs.COLOR_CARD, 0)
            // Pop-ups and system bars follow the phone's dark or light mode, so keep that in step.
            if (color != 0) Prefs.setThemeMode(activity, if (Palette.isDark(activity)) Prefs.THEME_DARK else Prefs.THEME_LIGHT)
        }
        keepSection = true
        activity.recreate()
    }

    /** Offers to download the detection file again, or to pick one already on the phone. */
    private fun setUpDetection() {
        AlertDialog.Builder(activity)
            .setTitle("Set up nudity detection")
            .setMessage("edenOS needs to download its detection file, about 11 MB. It is only downloaded once.")
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

    private fun editClaudeKey() {
        val first = Prefs.claudeKey(activity).isEmpty()
        textDialog("Claude API key", Prefs.claudeKey(activity), "sk-ant-…", 1) {
            Prefs.setClaudeKey(activity, it)
            rebuild()
        }
        if (first) {
            // No key yet: open Anthropic's page for making one; this box is waiting to paste it into on the way back.
            try {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://platform.claude.com/settings/keys")))
                Ui.toast(activity, "Create a key, copy it, then come back and paste it here")
            } catch (e: Exception) {
                Ui.toast(activity, "Make a key at platform.claude.com/settings/keys")
            }
        }
    }

    /**
     * Superclean from a link pasted in: the video's own address (an .mp4, or an .m3u8 or .mpd stream), for when a
     * site's player hides the real video behind pop-ups or its own embedded player.
     */
    private fun supercleanLink() {
        val ctx = activity
        fun field(hint: String, type: Int) = EditText(ctx).apply {
            this.hint = hint
            inputType = type
            maxLines = 3
        }
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val link = field("Video link (https://…)", uri)
        val title = field("Title, for the Parents Guide", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val captions = field("Captions link, if you have one (.vtt or .srt)", uri)
        val page = field("Page it plays on, if the site needs it", uri)
        (ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim())
            ?.takeIf { it.startsWith("http") }?.let { link.setText(it) }
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
            addView(Ui.caption(ctx, "The video's own address, often ending in .mp4 or .m3u8.").apply { setPadding(0, 0, 0, Ui.dp(ctx, 8)) })
            listOf(link, title, captions, page).forEach { addView(it) }
        }
        AlertDialog.Builder(ctx)
            .setTitle("Superclean a video link")
            .setView(android.widget.ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("Next") { _, _ ->
                val address = link.text.toString().trim()
                if (!address.startsWith("http")) {
                    Ui.toast(ctx, "That does not look like a link: it should start with https://")
                    return@setPositiveButton
                }
                val name = title.text.toString().trim().ifEmpty { android.net.Uri.parse(address).lastPathSegment ?: "Video" }
                val subs = captions.text.toString().trim().takeIf { it.startsWith("http") }?.let { listOf(it) }.orEmpty()
                val source = com.safewatch.app.tv.CleanSource(name, com.safewatch.core.MediaKey.forUrl(address), address, subs,
                    page.text.toString().trim(), com.safewatch.app.tv.CleanSource.streamKind(address))
                com.safewatch.app.tv.SupercleanActivity.open(ctx, source)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editCatalogKey() = textDialog("TMDB catalog key", Prefs.catalogKey(activity), "Paste your key", 1) {
        Prefs.setCatalogKey(activity, it)
        rebuild()
    }

    /** What the filters did lately, to read or to copy and send on when something was missed. */
    private fun showReport() {
        val report = FilterLog.text(activity)
        val words = android.widget.TextView(activity).apply {
            text = report
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(Ui.color(activity, R.color.text))
            setPadding(Ui.dp(activity, 20), Ui.dp(activity, 12), Ui.dp(activity, 20), Ui.dp(activity, 12))
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(words) }
        AlertDialog.Builder(activity)
            .setTitle("Filter report")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val board = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                board.setPrimaryClip(android.content.ClipData.newPlainText("edenOS filter report", report))
                Ui.toast(activity, "Report copied")
            }
            .setNeutralButton("Clear") { _, _ -> FilterLog.clear(activity) }
            .setNegativeButton("Close", null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Lets the viewer move each tab up or down the bar, and take tabs off it. */
    private fun arrangeTabs() {
        val ctx = activity
        val order = Prefs.tabOrder(ctx).toMutableList()
        val hidden = Prefs.hiddenTabs(ctx).toMutableSet()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0)
        }
        fun arrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) = Ui.iconButton(ctx, R.drawable.ic_up, if (up) "Move up" else "Move down") { onClick() }.apply {
            rotation = if (up) 0f else 180f
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 42), Ui.dp(ctx, 42))
        }
        fun render() {
            list.removeAllViews()
            order.forEachIndexed { i, id ->
                val (name, icon) = MainActivity.tabInfo(id)
                val shown = id !in hidden
                list.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = Ui.dp(ctx, 52)
                    addView(Ui.icon(ctx, icon, if (shown) R.color.accent else R.color.text_secondary))
                    addView(android.widget.TextView(ctx).apply {
                        text = if (shown) name else "$name (hidden)"
                        textSize = 16f
                        setTextColor(Ui.color(ctx, if (shown) R.color.text else R.color.text_secondary))
                        setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (id != MainActivity.TAB_FILTERS) {
                        addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                            isChecked = shown
                            contentDescription = "Show $name"
                            setOnCheckedChangeListener { _, on ->
                                if (on) hidden -= id else hidden += id
                                list.post { render() }
                            }
                        })
                    }
                    addView(arrow(true, i > 0) { order.add(i - 1, order.removeAt(i)); render() })
                    addView(arrow(false, i < order.size - 1) { order.add(i + 1, order.removeAt(i)); render() })
                })
            }
        }
        render()
        AlertDialog.Builder(ctx)
            .setTitle("Arrange tabs")
            .setMessage("Left to right along the bottom. Settings always stays.")
            .setView(android.widget.ScrollView(ctx).apply { addView(list) })
            .setPositiveButton("Done") { _, _ ->
                Prefs.setTabOrder(ctx, order)
                Prefs.setHiddenTabs(ctx, hidden)
                activity.rebuildTabBar()
                rebuild()
            }
            .setNeutralButton("Reset") { _, _ ->
                Prefs.setTabOrder(ctx, MainActivity.TAB_ORDER)
                Prefs.setHiddenTabs(ctx, emptySet())
                activity.rebuildTabBar()
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sets, changes or removes the PIN that guards Settings. */
    private fun editPin() {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Four digits"
            gravity = android.view.Gravity.CENTER
            textSize = 22f
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        val box = FrameLayout(activity).apply {
            setPadding(Ui.dp(activity, 24), Ui.dp(activity, 8), Ui.dp(activity, 24), 0)
            addView(field)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (Prefs.settingsPin(activity).isEmpty()) "Set a PIN" else "Change the PIN")
            .setMessage("Settings will ask for this PIN before opening.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val pin = field.text.toString()
                if (pin.length < 4) {
                    Ui.toast(activity, "Use at least four digits")
                } else {
                    Prefs.setSettingsPin(activity, pin)
                    MainActivity.settingsUnlocked = true
                    Ui.toast(activity, "Settings are locked with your PIN")
                    rebuild()
                }
            }
            .setNegativeButton("Cancel", null)
        if (Prefs.settingsPin(activity).isNotEmpty()) builder.setNeutralButton("Remove PIN") { _, _ ->
            Prefs.setSettingsPin(activity, "")
            rebuild()
        }
        builder.show()
    }

    companion object {
        private var shownSection = ""
        /** Set just before the screen is rebuilt for a new colour or mode, so it comes back on the same section. */
        private var keepSection = false
        private const val ACCOUNTS = "accounts"
        private const val LANGUAGE = "language"
        private const val NUDITY = "nudity"
        private const val SUPERCLEAN = "superclean"
        private const val BROWSER = "browser"
        private const val PLAYER = "player"
        private const val APP = "app"
        private const val LOOK = "look"
        private val ENGINES = listOf("google" to "Google", "duckduckgo" to "DuckDuckGo", "bing" to "Bing")
    }

    private fun editWords(title: String, words: Set<String>, onSave: (Set<String>) -> Unit) =
        textDialog(title, words.sorted().joinToString(", "), "Separate with commas", 2) { text ->
            onSave(text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
        }
}
