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
                "so titles open straight into that service. edenOS never sees your password."))

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
        if (settings.language != Strictness.OFF) {
            column.addView(Ui.sectionHeader(ctx, ""))
            column.addView(Ui.card(ctx).apply {
                addView(Ui.switchRow(ctx, "Show captions", Prefs.showCaptions(ctx)) { Prefs.setShowCaptions(ctx, it) })
                addView(Ui.divider(ctx))
                addView(Ui.switchRow(ctx, "No captions, no sound", Prefs.silentWithoutCaptions(ctx)) { Prefs.setSilentWithoutCaptions(ctx, it) })
                addView(Ui.divider(ctx))
                addView(Ui.row(ctx, "Filter report") { showReport() })
            })
            column.addView(Ui.caption(ctx,
                "Cursing is found by reading a video's captions, ahead of time where the player allows. Captions stay hidden " +
                    "unless you switch them on, so a muted word is not printed instead. A video with no captions cannot be " +
                    "filtered: the player says so, and \"No captions, no sound\" plays such a video silent. " +
                    "The report lists what the filter found and when it muted."))
        }

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
                addView(Ui.switchRow(ctx, "Look ahead", Prefs.lookAhead(ctx)) { Prefs.setLookAhead(ctx, it) })
            })
            column.addView(Ui.caption(ctx,
                "Plays a hidden second copy of the video a few seconds in front of you, so a scene is blurred before it " +
                "starts and until it ends. Works on YouTube, video files and ordinary websites, and uses about twice the data."))
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

        // Browser
        column.addView(Ui.sectionHeader(ctx, "Browser"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Search with", chevron = false).apply {
                val engines = listOf("google" to "Google", "duckduckgo" to "DuckDuckGo", "bing" to "Bing")
                addView(Ui.segmented(ctx, engines.map { it.second }, engines.indexOfFirst { it.first == Prefs.searchEngine(ctx) }.coerceAtLeast(0)) {
                    Prefs.setSearchEngine(ctx, engines[it].first)
                    com.safewatch.app.browser.BrowserActivity.searchPrefix = Prefs.searchPrefix(ctx)
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 230), -2))
            })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Hide the bar while scrolling", Prefs.hideBarWhileScrolling(ctx)) { Prefs.setHideBarWhileScrolling(ctx, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Block pop-ups and redirects", Prefs.blockPopups(ctx)) { Prefs.setBlockPopups(ctx, it) })
        })
        column.addView(Ui.caption(ctx,
            "Pop-up blocking stops pages opening new windows or sending you to another site by themselves. While a video is " +
                "playing, nothing can take you off its page. The browser's quick links are changed by pressing and holding one."))

        // Player
        column.addView(Ui.sectionHeader(ctx, "Player"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Skip buttons", chevron = false).apply {
                val jumps = listOf(10, 15, 30)
                addView(Ui.segmented(ctx, jumps.map { "$it s" }, jumps.indexOf(Prefs.skipSeconds(ctx)).coerceAtLeast(0)) {
                    Prefs.setSkipSeconds(ctx, jumps[it])
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 190), -2))
            })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Hidden pictures", chevron = false).apply {
                val styles = listOf("blur" to "Blurred", "black" to "Black")
                addView(Ui.segmented(ctx, styles.map { it.second }, styles.indexOfFirst { it.first == Prefs.hideStyle(ctx) }.coerceAtLeast(0)) {
                    Prefs.setHideStyle(ctx, styles[it].first)
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 170), -2))
            })
        })
        column.addView(Ui.caption(ctx, "Blurred shows moving colour so you can follow along; Black shows nothing at all."))

        // The app
        column.addView(Ui.sectionHeader(ctx, "App"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Open on", chevron = false).apply {
                val tabs = listOf(MainActivity.TAB_HOME to "Home", MainActivity.TAB_BROWSER to "Browser", MainActivity.TAB_YOUTUBE to "YouTube")
                addView(Ui.segmented(ctx, tabs.map { it.second }, tabs.indexOfFirst { it.first == Prefs.startTab(ctx) }.coerceAtLeast(0)) {
                    Prefs.setStartTab(ctx, tabs[it].first)
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 230), -2))
            })
            addView(Ui.divider(ctx))
            val pin = Prefs.settingsPin(ctx)
            addView(Ui.row(ctx, "Lock Settings with a PIN", if (pin.isEmpty()) "Off" else "On") { editPin() })
        })
        column.addView(Ui.caption(ctx,
            "With a PIN, Settings asks for it before opening, so the filters stay as you set them. It is asked again each time the app is reopened."))

        // Tabs
        val hiddenTabs = Prefs.hiddenTabs(ctx)
        column.addView(Ui.sectionHeader(ctx, "Tabs"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Arrange tabs",
                if (hiddenTabs.isEmpty()) "All shown" else "${MainActivity.TAB_ORDER.size - hiddenTabs.size} of ${MainActivity.TAB_ORDER.size} shown") { arrangeTabs() })
            addView(Ui.divider(ctx))
            addView(Ui.row(ctx, "Tab bar shows", chevron = false).apply {
                addView(Ui.segmented(ctx, listOf("Icons and names", "Icons only"), if (Prefs.tabNames(ctx)) 0 else 1) {
                    Prefs.setTabNames(ctx, it == 0)
                    activity.rebuildTabBar()
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 230), -2))
            })
        })
        column.addView(Ui.caption(ctx,
            "Put the tabs along the bottom in any order and hide the ones you do not use. Settings always stays, so this can be changed back."))

        // Superclean: a clean copy for the TV that Claude has also been through
        val claudeKey = Prefs.claudeKey(ctx)
        val claudeModel = Prefs.claudeModel(ctx)
        column.addView(Ui.sectionHeader(ctx, "Superclean"))
        column.addView(Ui.card(ctx).apply {
            addView(Ui.row(ctx, "Claude API key", if (claudeKey.isEmpty()) "Not added" else "Added") { editClaudeKey() })
            if (claudeKey.isNotEmpty()) {
                addView(Ui.divider(ctx))
                addView(Ui.row(ctx, "Model", chevron = false).apply {
                    val models = listOf(com.safewatch.core.ClaudeApi.SONNET to "Sonnet 5.5", com.safewatch.core.ClaudeApi.HAIKU to "Haiku 5.5")
                    addView(Ui.segmented(ctx, models.map { it.second }, models.indexOfFirst { it.first == claudeModel }.coerceAtLeast(0)) {
                        Prefs.setClaudeModel(ctx, models[it].first)
                        rebuild()
                    }, LinearLayout.LayoutParams(Ui.dp(ctx, 220), -2))
                })
            }
            addView(Ui.divider(ctx))
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
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 180), -2))
            })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Look up the IMDb Parents Guide", Prefs.supercleanGuide(ctx)) { Prefs.setSupercleanGuide(ctx, it) })
        })
        column.addView(Ui.caption(ctx,
            "Superclean is a clean copy for the TV that Claude has been through too. Claude looks at small pictures from the " +
                "video (two seconds apart) and reads its captions, and takes out what you chose here, from cursing and slurs to " +
                "kissing, immodesty, violence, drinking and frightening scenes, modelled on VidAngel's filters. It first looks up " +
                "the title's IMDb Parents Guide, so you can also tick the scenes it warns of. Start one from Send to TV while a " +
                "video plays.\n\nWith your own key from console.anthropic.com; it stays on this phone, and the pictures and caption " +
                "text go only to Anthropic. It costs ${com.safewatch.app.tv.SupercleanRun.costText(3_600_000L, claudeModel)} for each " +
                "hour of video with " +
                (if (claudeModel == com.safewatch.core.ClaudeApi.HAIKU) "Haiku" else "Sonnet (Haiku is cheaper but less careful)") +
                ", plus ${com.safewatch.app.tv.SupercleanRun.guideCostText(claudeModel)} for the Parents Guide, billed to your Anthropic account."))

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
                // Choosing a mode goes back to that mode's own background and cards.
                Prefs.setCustomColor(ctx, Prefs.COLOR_BACKGROUND, 0)
                Prefs.setCustomColor(ctx, Prefs.COLOR_CARD, 0)
                Prefs.setThemeMode(ctx, it)
                activity.recreate()
            }))
            addView(Ui.divider(ctx))
            addView(Ui.fieldLabel(ctx, "Primary colour"))
            addView(Ui.swatches(ctx, Palette.primaries, Prefs.customColor(ctx, Prefs.COLOR_PRIMARY)) { pickColor(Prefs.COLOR_PRIMARY, it) })
            addView(Ui.divider(ctx))
            addView(Ui.fieldLabel(ctx, "Background colour"))
            addView(Ui.swatches(ctx, Palette.backgrounds, Prefs.customColor(ctx, Prefs.COLOR_BACKGROUND)) { pickColor(Prefs.COLOR_BACKGROUND, it) })
            addView(Ui.divider(ctx))
            addView(Ui.fieldLabel(ctx, "Card colour"))
            addView(Ui.swatches(ctx, Palette.cardChoices(ctx), Prefs.customColor(ctx, Prefs.COLOR_CARD)) { pickColor(Prefs.COLOR_CARD, it) })
            addView(Ui.divider(ctx))
            addView(Ui.switchRow(ctx, "Opening animation", Prefs.openingAnimation(ctx)) { Prefs.setOpeningAnimation(ctx, it) })
        })
        column.addView(Ui.caption(ctx, "Primary is used for buttons and highlights. Text adjusts by itself to stay readable on the background you pick."))

        // Sounds
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
        column.addView(Ui.caption(ctx, "Played when you tap, open a title and start watching, at the phone's media volume."))
    }

    private fun pickColor(which: String, color: Int) {
        Prefs.setCustomColor(activity, which, color)
        if (which == Prefs.COLOR_BACKGROUND) {
            // Cards are chosen to suit the background, so a new background starts them afresh.
            Prefs.setCustomColor(activity, Prefs.COLOR_CARD, 0)
            // Pop-ups and system bars follow the phone's dark or light mode, so keep that in step.
            if (color != 0) Prefs.setThemeMode(activity, if (Palette.isDark(activity)) Prefs.THEME_DARK else Prefs.THEME_LIGHT)
        }
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

    private fun editWords(title: String, words: Set<String>, onSave: (Set<String>) -> Unit) =
        textDialog(title, words.sorted().joinToString(", "), "Separate with commas", 2) { text ->
            onSave(text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
        }
}
