package com.safewatch.app

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.data.Shelf
import com.safewatch.app.data.Title
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import java.time.LocalDate

/**
 * The home tab: a featured title, the viewer's services, and shelves of what
 * is new on each one. Saved shelves show at once; fresh ones replace them
 * every time the app is opened.
 */
@androidx.media3.common.util.UnstableApi
class HomeScreen(private val activity: MainActivity) {

    private val column: LinearLayout
    /** Your Scrubbed Movies: Supercleans and clean copies being made, waiting, and ready. Updated as they go. */
    private val scrubbed = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val onTvChange: () -> Unit = { if (!activity.isDestroyed) showScrubbed() }
    val view: View
    private var shown: List<Shelf>? = null
    private var shownFor = ""
    private var loading = false
    private var loadedAt = 0L

    init {
        val (page, col) = Ui.page(activity, padded = false)
        view = page
        column = col
        com.safewatch.app.tv.TvState.listeners += onTvChange
        showScrubbed()
    }

    /** Called when the app closes, so the progress updates stop. */
    fun release() {
        com.safewatch.app.tv.TvState.listeners -= onTvChange
    }

    /** Called whenever the home tab comes back into view. */
    fun refresh() {
        // The shelves depend on which services are chosen and on the catalog in use.
        val state = Prefs.connectedServices(activity).sorted().joinToString(",") + "|" + Prefs.catalogKey(activity)
        if (state != shownFor) {
            if (shownFor.isNotEmpty()) Catalog.reset(activity)
            shownFor = state
            shown = null
            loadedAt = 0
        }
        if (shown == null) {
            val saved = Catalog.savedHome(activity)
            if (saved != null) render(saved) else renderMessage("Loading titles…", retry = false)
        }
        if (!loading && System.currentTimeMillis() - loadedAt > REFRESH_AFTER_MS) load()
        showScrubbed()
    }

    private fun showScrubbed() {
        val ctx = activity
        scrubbed.removeAllViews()
        val tv = com.safewatch.app.tv.TvState
        val job = tv.job
        val making = job?.takeIf { it.made == null && it.error == null }
        val failed = job?.takeIf { it.error != null }
        val queued = tv.queued
        val ready = com.safewatch.app.tv.CleanCopy.all(ctx).take(8)
        scrubbed.addView(Ui.shelfTitle(ctx, "Your Scrubbed Movies"))
        val card = Ui.card(ctx)
        val lastFailure = if (failed == null) com.safewatch.app.tv.TvState.lastFailure(ctx) else null
        if (making == null && failed == null && queued.isEmpty() && ready.isEmpty() && lastFailure == null) {
            card.addView(TextView(ctx).apply {
                text = "Nothing yet. While a movie plays, tap Superclean at the top of the player: it is downloaded and scrubbed " +
                    "here in the background, and shows up here as it goes."
                textSize = 14f
                setTextColor(Ui.color(ctx, R.color.text_secondary))
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 14))
            })
        }
        lastFailure?.let { (title, error) ->
            card.addView(Ui.row(ctx, title, "Not made") {
                android.app.AlertDialog.Builder(ctx).setTitle(title).setMessage(error)
                    .setPositiveButton("Dismiss") { _, _ -> com.safewatch.app.tv.TvState.clearFailure(ctx); showScrubbed() }
                    .setNegativeButton("Close", null).show()
            })
        }
        fun gap() { if (card.childCount > 0) card.addView(Ui.divider(ctx)) }
        if (making != null) {
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
                addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = making.title
                        textSize = 17f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Ui.color(ctx, R.color.text))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(ctx).apply {
                        text = if (making.percent >= 0) "${making.percent}%" else ""
                        textSize = 17f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Ui.color(ctx, R.color.accent))
                    })
                })
                addView(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = making.percent < 0
                    max = 100
                    progress = making.percent.coerceAtLeast(0)
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.color(ctx, R.color.accent))
                    indeterminateTintList = progressTintList
                }, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 14)).apply { topMargin = Ui.dp(ctx, 6) })
                addView(TextView(ctx).apply {
                    text = making.step
                    textSize = 13f
                    setTextColor(Ui.color(ctx, R.color.text_secondary))
                })
            })
            card.addView(Ui.divider(ctx))
            card.addView(Ui.row(ctx, "Stop", chevron = false) {
                android.app.AlertDialog.Builder(ctx).setMessage("Stop scrubbing ${making.title}?")
                    .setPositiveButton("Stop") { _, _ -> com.safewatch.app.tv.TvService.cancel(ctx) }
                    .setNegativeButton("Keep going", null).show()
            })
        }
        for (title in queued) {
            gap()
            card.addView(Ui.row(ctx, title, "Waiting") {
                android.app.AlertDialog.Builder(ctx).setMessage("Take $title off the list?")
                    .setPositiveButton("Take off") { _, _ -> com.safewatch.app.tv.TvService.drop(ctx, title) }
                    .setNegativeButton("Keep", null).show()
            })
        }
        if (failed != null) {
            gap()
            card.addView(Ui.row(ctx, failed.title, "Not made") {
                android.app.AlertDialog.Builder(ctx).setTitle(failed.title).setMessage(failed.error)
                    .setPositiveButton("Dismiss") { _, _ -> tv.job = null; com.safewatch.app.tv.TvState.clearFailure(ctx); showScrubbed() }
                    .setNegativeButton("Close", null).show()
            })
        }
        for (copy in ready) {
            gap()
            card.addView(Ui.row(ctx, copy.title, "Ready") { com.safewatch.app.tv.TvActivity.open(ctx) })
        }
        scrubbed.addView(FrameLayout(ctx).apply {
            setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), 0)
            addView(card)
        })
    }

    /** Puts Your Scrubbed Movies near the top of Home, wherever the page is drawn from. */
    private fun addScrubbed() {
        (scrubbed.parent as? android.view.ViewGroup)?.removeView(scrubbed)
        column.addView(scrubbed)
    }

    private fun load() {
        loading = true
        val state = shownFor
        Thread {
            val fresh = try { Catalog.loadHome(activity.applicationContext) } catch (e: Exception) { null }
            activity.runOnUiThread {
                loading = false
                if (activity.isDestroyed || state != shownFor) return@runOnUiThread
                when {
                    fresh == null -> if (shown == null) renderMessage("Titles could not be loaded. Check your connection.", retry = true)
                    fresh.isEmpty() -> if (shown == null) renderMessage("Nothing new was found for your services.", retry = true)
                    else -> {
                        loadedAt = System.currentTimeMillis()
                        if (fresh != shown) render(fresh)
                    }
                }
            }
        }.start()
    }

    /** The top of Home: the edenOS mark and name in the middle, TV Mode at one side and Send to TV at the other. */
    private fun header(): View = FrameLayout(activity).apply {
        setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 6))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "edenOS"
            addView(Ui.logo(context, 34))
            addView(com.safewatch.app.ui.Brand.wordmark(context, 23f), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(context, 8) })
            setOnClickListener { onNameTapped() }
        }, FrameLayout.LayoutParams(-2, Ui.dp(context, 46), Gravity.CENTER))
        addView(Ui.iconButton(context, R.drawable.ic_tv, "TV Mode") { com.safewatch.app.tv.TvModeActivity.open(activity) },
            FrameLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(Ui.iconButton(context, R.drawable.ic_cast, "Send to TV") { Ui.sendToTv(activity) },
            FrameLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private var nameTaps = 0

    private fun onNameTapped() {
        if (Prefs.starshipUnlocked(activity) || ++nameTaps < 7) return
        Prefs.unlockStarship(activity)
        Prefs.setSoundPack(activity, "starship")
        Sounds.play(activity, Sounds.PLAY)
        Ui.toast(activity, "Starship sounds unlocked. Change them in Settings.")
    }

    private fun services(): View {
        val strip = LinearLayout(activity).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 12), 0)
        }
        for (service in Services.connected(activity)) {
            strip.addView(Ui.chip(activity, service.name, strong = true) { ServiceActivity.open(activity, service) })
        }
        strip.addView(Ui.chip(activity, "Edit") { activity.editServices { refresh() } })
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun footer() {
        column.addView(Ui.shelfTitle(activity, "On this phone"))
        column.addView(FrameLayout(activity).apply {
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
            addView(Ui.card(context).apply {
                addView(Ui.row(context, "Open a video file") { activity.pickVideo.launch(arrayOf("video/*")) })
            })
        })
    }

    private fun renderMessage(text: String, retry: Boolean) {
        column.removeAllViews()
        column.addView(header())
        addScrubbed()
        column.addView(services())
        column.addView(TextView(activity).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.color(context, R.color.text_secondary))
            setPadding(Ui.dp(context, 32), Ui.dp(context, 72), Ui.dp(context, 32), Ui.dp(context, 20))
        })
        if (retry) column.addView(FrameLayout(activity).apply {
            addView(Ui.pill(context, "Try again", filled = false) {
                renderMessage("Loading titles…", retry = false)
                load()
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            setPadding(0, 0, 0, Ui.dp(context, 48))
        })
        footer()
    }

    private fun render(shelves: List<Shelf>) {
        shown = shelves
        column.removeAllViews()
        column.addView(header())
        // A different featured title each day, taken from the first shelf.
        val candidates = shelves.first().titles.filter { it.poster != null }.take(7)
        addScrubbed()
        if (candidates.isNotEmpty()) column.addView(hero(candidates[(LocalDate.now().toEpochDay() % candidates.size).toInt()]))
        column.addView(services())
        for (shelf in shelves) {
            column.addView(Ui.shelfTitle(activity, shelf.name))
            val strip = LinearLayout(activity).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 10), 0) }
            for (title in shelf.titles) {
                strip.addView(Ui.poster(activity, title.name, title.thumbnail, 118) { TitleActivity.open(activity, title) }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 10)
                })
            }
            column.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            })
        }
        footer()
    }

    /** The large featured title at the top. */
    private fun hero(title: Title): View {
        val bg = Ui.color(activity, R.color.bg)
        val service = Services.byId(title.serviceId)
        return FrameLayout(activity).apply {
            val width = resources.displayMetrics.widthPixels
            layoutParams = LinearLayout.LayoutParams(-1, (width * 1.18f).toInt())
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                title.poster?.let { Images.load(it, this, minWidth = 700) }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { background = Ui.fade(bg) }, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), Ui.dp(context, 4))
                addView(TextView(context).apply {
                    text = listOfNotNull(service?.let { "New on ${it.name}" }, title.kind).joinToString("  ·  ").uppercase()
                    textSize = 12f
                    letterSpacing = 0.06f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Ui.color(context, R.color.text_secondary))
                })
                addView(TextView(context).apply {
                    text = title.name
                    textSize = 32f
                    maxLines = 2
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = -0.02f
                    setTextColor(Ui.color(context, R.color.text))
                    setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 14))
                })
                addView(LinearLayout(context).apply {
                    addView(Ui.actionButton(context, "Watch", iconRes = R.drawable.ic_play) { TitleActivity.watch(activity, title) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(context, 10) })
                    addView(Ui.actionButton(context, "Details", filled = false) { TitleActivity.open(activity, title) },
                        LinearLayout.LayoutParams(0, -2, 1f))
                })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            // A sliver of the page colour along the bottom edge, so no line of the picture shows under the fade.
            addView(View(context).apply { setBackgroundColor(bg) }, FrameLayout.LayoutParams(-1, Ui.dp(context, 2), Gravity.BOTTOM))
            setOnClickListener { TitleActivity.open(activity, title) }
        }
    }

    private companion object {
        const val REFRESH_AFTER_MS = 10 * 60 * 1000L
    }
}
