package com.safewatch.app.tv

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.safewatch.app.MainActivity
import com.safewatch.app.R
import com.safewatch.app.data.Prefs
import com.safewatch.app.ui.SupercleanChoices
import com.safewatch.app.ui.Ui
import com.safewatch.core.ClaudeApi
import com.safewatch.core.Superclean

/**
 * Superclean for one title: Claude looks up its IMDb Parents Guide, the family ticks the scenes it warns of
 * that they want out, adjusts their usual choices for this title if they like, and the clean copy is made
 * with Claude taking out all of it.
 */
@UnstableApi
class SupercleanActivity : AppCompatActivity() {
    private lateinit var source: CleanSource
    private var replaces: String? = null
    private lateinit var body: LinearLayout
    private var remove: Set<String> = emptySet()
    private var cut = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = try {
            CleanSource.fromJson(intent.getStringExtra(EXTRA_SOURCE) ?: "")
        } catch (e: Exception) {
            finish()
            return
        }
        replaces = intent.getStringExtra(EXTRA_REPLACES)
        remove = savedInstanceState?.getStringArrayList(STATE_REMOVE)?.toSet() ?: Prefs.supercleanChoices(this)
        cut = savedInstanceState?.getBoolean(STATE_CUT) ?: Prefs.supercleanCut(this)
        val (page, column) = Ui.page(this, ownWindow = true)
        column.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)).apply { topMargin = Ui.dp(context, 8) }
        })
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(Ui.logo(context, 40).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { marginEnd = Ui.dp(context, 10); bottomMargin = Ui.dp(context, 8) }
            })
            addView(Ui.largeTitle(context, "Superclean"))
        })
        column.addView(Ui.subtitle(this, source.title))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
        setContentView(page)
        if (Lookup.key != source.key + source.title && Prefs.supercleanGuide(this)) lookUp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_REMOVE, ArrayList(remove))
        outState.putBoolean(STATE_CUT, cut)
    }

    override fun onResume() {
        super.onResume()
        Lookup.listener = { if (!isDestroyed) show() }
        show() // the Parents Guide may have arrived while this was out of sight
    }

    override fun onPause() {
        Lookup.listener = null
        super.onPause()
    }

    private fun lookUp() = Lookup.start(this, source.key + source.title, source.title, source.referrer)

    private fun mine(): Boolean = Lookup.key == source.key + source.title

    private fun show() {
        body.removeAllViews()
        showGuide()

        body.addView(Ui.sectionHeader(this, "Also take out"))
        body.addView(Ui.card(this).apply {
            addView(Ui.row(this@SupercleanActivity, "What Superclean takes out", SupercleanChoices.summary(remove)) {
                SupercleanChoices.edit(this@SupercleanActivity, "For this title", remove) { remove = it; show() }
            })
            addView(Ui.divider(this@SupercleanActivity))
            addView(Ui.row(this@SupercleanActivity, "Scenes", chevron = false).apply {
                addView(Ui.segmented(context, listOf("Cut out", "Blur"), if (cut) 0 else 1) { cut = it == 0 },
                    LinearLayout.LayoutParams(Ui.dp(context, 180), -2))
            })
        })
        body.addView(Ui.caption(this,
            "Starts from your usual choices in Settings > Superclean; changes here are for this title only. Cut scenes are taken " +
                "out of the copy; blurred ones stay, too blurred to see."))

        body.addView(Ui.actionButton(this, if (replaces == null) "Review and Superclean" else "Review and Superclean again") { start() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 28) }
        })
        val model = Prefs.claudeModel(this)
        body.addView(Ui.caption(this,
            "Claude looks at small pictures from the whole video, two seconds apart, and reads its captions, then the copy is " +
                "written with all of it taken out. A film takes an hour or more, with the phone locked if you like. It costs " +
                "${SupercleanRun.costText(3_600_000L, model)} for each hour of video with " +
                (if (model == ClaudeApi.HAIKU) "Haiku" else "Sonnet") + ", billed to your Anthropic account."))
    }

    /** The title's Parents Guide: being looked up, found (a list to tick), or not found. */
    private fun showGuide() {
        body.addView(Ui.sectionHeader(this, "IMDb Parents Guide"))
        val guide = if (mine()) Lookup.guide else null
        val error = if (mine()) Lookup.error else null
        when {
            mine() && Lookup.running -> {
                body.addView(Ui.card(this).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
                        addView(ProgressBar(context).apply {
                            isIndeterminate = true
                            indeterminateTintList = ColorStateList.valueOf(Ui.color(context, R.color.accent))
                        }, LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)).apply { marginEnd = Ui.dp(context, 14) })
                        addView(TextView(context).apply {
                            text = "Claude is finding and reading the Parents Guide…"
                            textSize = 16f
                            setTextColor(Ui.color(context, R.color.text))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                })
                body.addView(Ui.caption(this, "This takes about a minute. Once it is read, tick the scenes you want taken out."))
            }
            guide != null && !guide.isEmpty -> showGuideItems(guide)
            guide != null || error != null -> {
                body.addView(Ui.card(this).apply {
                    addView(TextView(context).apply {
                        text = error?.let { "The Parents Guide could not be looked up. $it" }
                            ?: "Claude could not find a Parents Guide for this title. Your choices below still apply."
                        textSize = 15f
                        setTextColor(Ui.color(context, R.color.text))
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                    })
                    addView(Ui.divider(this@SupercleanActivity))
                    addView(Ui.row(this@SupercleanActivity, "Try again", chevron = false) { Lookup.key = null; lookUp(); show() })
                })
            }
            else -> {
                body.addView(Ui.card(this).apply {
                    addView(Ui.row(this@SupercleanActivity, "Look up the Parents Guide", chevron = false) { lookUp(); show() })
                })
                body.addView(Ui.caption(this,
                    "Claude finds this title's guide on IMDb and lists what it warns of, so you can choose scenes to take out. " +
                        "It costs ${SupercleanRun.guideCostText(Prefs.claudeModel(this))}."))
            }
        }
    }

    private fun showGuideItems(guide: Superclean.Guide) {
        if (Lookup.ticked == null) {
            // At first, a section's scenes are ticked when the usual choices cover that kind of thing.
            Lookup.ticked = LinkedHashSet(guide.sections.filter { Superclean.sectionWanted(it.name, remove) }
                .flatMap { s -> s.items.map { item(s, it) } })
        }
        val ticked = Lookup.ticked!!
        if (guide.title.isNotEmpty()) body.addView(Ui.caption(this,
            "Found: ${guide.title}" + (if (guide.year.isNotEmpty()) " (${guide.year})" else "") + ". Tick what you want taken out.").apply {
            setPadding(paddingLeft, 0, paddingRight, Ui.dp(context, 10))
        })
        for (section in guide.sections) {
            body.addView(Ui.card(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 12) }
                val boxes = ArrayList<CheckBox>()
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 10))
                    addView(TextView(context).apply {
                        text = section.name
                        textSize = 17f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Ui.color(context, R.color.text))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (section.severity.isNotEmpty()) addView(badge(section.severity))
                })
                if (section.items.isEmpty()) {
                    addView(Ui.divider(this@SupercleanActivity))
                    addView(TextView(context).apply {
                        text = "Nothing listed."
                        textSize = 14f
                        setTextColor(Ui.color(context, R.color.text_secondary))
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 12))
                    })
                    return@apply
                }
                for (line in section.items) {
                    val id = item(section, line)
                    addView(Ui.divider(this@SupercleanActivity))
                    val box = CheckBox(context).apply {
                        isChecked = id in ticked
                        buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(Ui.color(context, R.color.accent), Ui.color(context, R.color.text_secondary)))
                        setOnCheckedChangeListener { _, on -> if (on) ticked += id else ticked -= id }
                    }
                    boxes += box
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
                        addView(TextView(context).apply {
                            text = line
                            textSize = 15f
                            setTextColor(Ui.color(context, R.color.text))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(box)
                        foreground = Ui.ripple(context)
                        setOnClickListener { box.toggle() }
                    })
                }
                addView(Ui.divider(this@SupercleanActivity))
                addView(Ui.row(this@SupercleanActivity, "Tick all", chevron = false) {
                    val all = boxes.all { it.isChecked }
                    boxes.forEach { it.isChecked = !all }
                })
            })
        }
    }

    /** How strong IMDb says a section is, as a small coloured label. */
    private fun badge(severity: String): TextView = TextView(this).apply {
        text = severity
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        val tone = when (severity.lowercase()) {
            "severe" -> "#E5484D"
            "moderate" -> "#F08C2E"
            "mild" -> "#2FA36B"
            else -> "#8E8E93"
        }
        background = Ui.rounded(Color.parseColor(tone), Ui.dp(context, 10).toFloat())
        setPadding(Ui.dp(context, 9), Ui.dp(context, 3), Ui.dp(context, 9), Ui.dp(context, 3))
    }

    private fun item(section: Superclean.GuideSection, text: String) = "${section.name}: $text"

    private fun start() {
        val busy = TvState.job?.let { it.made == null && it.error == null } == true
        if (busy) {
            AlertDialog.Builder(this).setTitle("A copy is being made")
                .setMessage("One clean copy is made at a time. Wait for ${TvState.job?.title ?: "it"} to finish, then start this one.")
                .setPositiveButton("OK", null).show()
            return
        }
        val guide = if (mine()) Lookup.guide else null
        val ticked = Lookup.ticked.orEmpty()
        // In the order the guide lists them.
        val items = guide?.sections.orEmpty().flatMap { s -> s.items.map { item(s, it) } }.filter { it in ticked }
        if (remove.isEmpty() && items.isEmpty()) {
            Ui.toast(this, "Choose something for Superclean to take out")
            return
        }
        if (mine() && Lookup.running) {
            AlertDialog.Builder(this).setTitle("Start without the Parents Guide?")
                .setMessage("Claude is still reading it. Wait a moment to choose scenes from it, or start with your other choices.")
                .setPositiveButton("Continue without it") { _, _ -> review(emptyList()) }
                .setNegativeButton("Wait", null).show()
            return
        }
        review(items)
    }

    /**
     * The last step before anything goes to Claude: every single thing Superclean is about to take out, ticked.
     * The viewer unticks what should stay, and nothing starts until they approve.
     */
    private fun review(items: List<String>) {
        val ctx = this
        val accent = Ui.color(ctx, R.color.accent)
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, Ui.color(ctx, R.color.text_secondary)))
        val choiceBoxes = LinkedHashMap<String, CheckBox>()
        val itemBoxes = LinkedHashMap<String, CheckBox>()
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 12)) }
        fun heading(text: String) = column.addView(TextView(ctx).apply {
            this.text = text.uppercase()
            textSize = 13f
            letterSpacing = 0.04f
            setTextColor(Ui.color(ctx, R.color.text_secondary))
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 16), Ui.dp(ctx, 24), Ui.dp(ctx, 4))
        })
        fun line(text: String, detail: String?, box: CheckBox) = column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { this.text = text; textSize = 15f; setTextColor(Ui.color(ctx, R.color.text)) })
                if (detail != null) addView(TextView(ctx).apply { this.text = detail; textSize = 12f; setTextColor(Ui.color(ctx, R.color.text_secondary)) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(box)
            foreground = Ui.ripple(ctx)
            setOnClickListener { box.toggle() }
        })
        column.addView(TextView(ctx).apply {
            text = "Everything below will be " + (if (cut) "cut out" else "blurred") + " or muted. Untick anything that should stay, " +
                "then approve. Nothing is sent to Claude until you do."
            textSize = 14f
            setTextColor(Ui.color(ctx, R.color.text))
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 4), Ui.dp(ctx, 24), 0)
        })
        if (items.isNotEmpty()) {
            heading("From the Parents Guide")
            for (item in items) {
                val box = CheckBox(ctx).apply { isChecked = true; buttonTintList = tint }
                itemBoxes[item] = box
                line(item.substringAfter(": "), item.substringBefore(": "), box)
            }
        }
        for (group in Superclean.GROUPS) {
            val chosen = Superclean.CHOICES.filter { it.group == group && it.id in remove }
            if (chosen.isEmpty()) continue
            heading(group)
            for (choice in chosen) {
                val box = CheckBox(ctx).apply { isChecked = true; buttonTintList = tint }
                choiceBoxes[choice.id] = box
                line(choice.name, choice.detail, box)
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle("Superclean will take out")
            .setView(android.widget.ScrollView(ctx).apply { addView(column) })
            .setPositiveButton("Approve and start") { _, _ ->
                val keepRemove = choiceBoxes.filterValues { it.isChecked }.keys.toSet()
                val keepItems = itemBoxes.filterValues { it.isChecked }.keys.toList()
                if (keepRemove.isEmpty() && keepItems.isEmpty()) {
                    Ui.toast(ctx, "Nothing was left ticked, so Superclean did not start")
                } else {
                    remove = keepRemove
                    Lookup.ticked?.retainAll(keepItems.toSet())
                    begin(keepItems)
                }
            }
            .setNegativeButton("Go back", null)
            .show()
    }

    private fun begin(items: List<String>) {
        val wishes = Superclean.Wishes(remove, items, cut)
        TvService.prepare(this, source.copy(superclean = wishes.toJson()), replaces)
        TvActivity.open(this)
        finish()
    }

    /** The Parents Guide lookup, kept apart from the screen so turning the phone does not start it again. */
    private object Lookup {
        var key: String? = null
        var guide: Superclean.Guide? = null
        var error: String? = null
        var running = false
        var ticked: MutableSet<String>? = null
        var listener: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        fun start(ctx: Context, key: String, title: String, page: String) {
            if (this.key == key && (running || guide != null)) return
            this.key = key
            guide = null
            error = null
            ticked = null
            running = true
            val app = ctx.applicationContext
            Thread {
                var found: Superclean.Guide? = null
                var failed: String? = null
                try {
                    found = SupercleanRun.lookUpGuide(app, title, page)
                } catch (e: ClaudeApi.Failure) {
                    failed = e.message
                } catch (e: Exception) {
                    failed = e.message ?: "Something went wrong"
                }
                main.post {
                    if (this.key != key) return@post
                    guide = found
                    error = failed
                    running = false
                    listener?.invoke()
                }
            }.start()
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_REPLACES = "replaces"
        private const val STATE_REMOVE = "remove"
        private const val STATE_CUT = "cut"

        /**
         * Opens Superclean for [source]; [replaces] is a clean copy the new one takes the place of. Without a Claude
         * key, says where to add one instead.
         */
        fun open(ctx: Context, source: CleanSource, replaces: String? = null) {
            if (Prefs.claudeKey(ctx).isEmpty()) return askForKey(ctx)
            ctx.startActivity(Intent(ctx, SupercleanActivity::class.java).putExtra(EXTRA_SOURCE, source.toJson())
                .putExtra(EXTRA_REPLACES, replaces))
        }

        private fun askForKey(ctx: Context) {
            AlertDialog.Builder(ctx).setTitle("Superclean needs a Claude key")
                .setMessage("Superclean sends the video's pictures and captions to Claude, with your own Anthropic API key, so " +
                    "Claude can take out what you choose. Add a key in Settings > Superclean.")
                .setPositiveButton("Open Settings") { _, _ -> MainActivity.open(ctx, MainActivity.TAB_FILTERS) }
                .setNegativeButton("Cancel", null).show()
        }

        /**
         * One tap: Superclean what is playing with the choices already made in Settings, straight away, in the
         * background. The video is downloaded and Claude goes through it while the viewer carries on; Home shows how
         * far it has got, under Your Scrubbed Movies. [whyNot] says why there is nothing here that can be saved.
         */
        fun start(ctx: Context, source: CleanSource?, whyNot: String? = null) {
            if (source == null) {
                AlertDialog.Builder(ctx).setTitle("Superclean")
                    .setMessage((whyNot ?: "There is no video here that can be saved.") +
                        "\n\nSuperclean works with video files and websites' own videos. Netflix-style services lock theirs; " +
                        "watch those with Mirror to TV or TV Mode.")
                    .setPositiveButton("OK", null).show()
                return
            }
            if (Prefs.claudeKey(ctx).isEmpty()) return askForKey(ctx)
            if (Prefs.supercleanReview(ctx)) return open(ctx, source)
            val wishes = Superclean.Wishes(Prefs.supercleanChoices(ctx), cut = Prefs.supercleanCut(ctx), autoGuide = Prefs.supercleanGuide(ctx))
            if (wishes.remove.isEmpty() && !wishes.autoGuide) {
                Ui.toast(ctx, "Choose what Superclean takes out in Settings > Superclean")
                return
            }
            val waiting = TvState.job?.let { it.made == null && it.error == null } == true
            TvService.prepare(ctx, source.copy(superclean = wishes.toJson()))
            Ui.toast(ctx, (if (waiting) "${source.title} is next in line to be Supercleaned." else "Supercleaning ${source.title}.") +
                " Follow it on Home, under Your Scrubbed Movies.")
        }
    }
}
