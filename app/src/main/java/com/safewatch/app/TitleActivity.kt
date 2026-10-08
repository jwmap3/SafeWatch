package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Episode
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Service
import com.safewatch.app.data.Services
import com.safewatch.app.data.Title
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import com.safewatch.core.Strictness
import com.safewatch.core.WordList
import org.json.JSONObject

/**
 * One show or movie: its artwork and description, where to watch it, and for
 * a series its episodes. Watching opens the service's page for it in the
 * built-in browser, where the filters apply.
 */
class TitleActivity : AppCompatActivity() {

    private lateinit var title: Title
    private lateinit var watchHolder: LinearLayout
    private lateinit var episodesHolder: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = try {
            Title.fromJson(JSONObject(intent.getStringExtra(EXTRA_TITLE).orEmpty()))
        } catch (e: Exception) {
            finish()
            return
        }
        val (page, column) = Ui.page(this, padded = false, ownWindow = true)
        val bg = Ui.color(this, R.color.bg)
        val side = Ui.dp(this, 20)

        column.addView(FrameLayout(this).apply {
            val width = resources.displayMetrics.widthPixels
            layoutParams = LinearLayout.LayoutParams(-1, (width * 1.1f).toInt())
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Ui.color(context, R.color.fill))
                title.poster?.let { Images.load(it, this, minWidth = 700) }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { background = Ui.fade(bg) }, FrameLayout.LayoutParams(-1, -1))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back", R.color.on_accent) { finish() }.apply {
                background = Ui.rounded(Color.argb(120, 0, 0, 0), Ui.dp(context, 23).toFloat())
            }, FrameLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)).apply {
                leftMargin = Ui.dp(context, 12)
                topMargin = Ui.dp(context, 8)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(side, 0, side, 0)
                addView(TextView(context).apply {
                    text = title.name
                    textSize = 30f
                    maxLines = 3
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = -0.02f
                    setTextColor(Ui.color(context, R.color.text))
                })
                addView(TextView(context).apply {
                    text = listOfNotNull(title.year.ifEmpty { null }, title.kind, Services.byId(title.serviceId)?.name).joinToString("  ·  ")
                    textSize = 14f
                    setTextColor(Ui.color(context, R.color.text_secondary))
                    setPadding(0, Ui.dp(context, 6), 0, 0)
                })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })

        watchHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 18), side, 0)
        }
        column.addView(watchHolder)

        if (title.overview.isNotEmpty()) column.addView(TextView(this).apply {
            text = title.overview
            textSize = 15f
            setLineSpacing(Ui.dp(context, 3).toFloat(), 1f)
            setTextColor(Ui.color(context, R.color.text))
            setPadding(side, Ui.dp(context, 18), side, 0)
        })

        episodesHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(episodesHolder)

        val s = Prefs.settings(this)
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, 0)
            addView(Ui.sectionHeader(context, "Your filters"))
            addView(Ui.card(context).apply {
                addView(Ui.row(context, "Language", level(s.language.name)) { MainActivity.open(context, MainActivity.TAB_FILTERS) })
                addView(Ui.divider(context))
                addView(Ui.row(context, "Words muted", if (s.language == Strictness.OFF) "None"
                    else "${WordList.groups.count { g -> s.mutes(g) } + s.customWords.size}") { WordsActivity.open(context) })
                addView(Ui.divider(context))
                addView(Ui.row(context, "Nudity", level(s.nudity.name)) { MainActivity.open(context, MainActivity.TAB_FILTERS) })
            })
            Services.byId(title.serviceId)?.takeIf { it.protectedVideo }?.let { service ->
                addView(Ui.caption(context,
                    "Cursing is muted from the captions, so switch captions on in the ${service.name} player. " +
                        "${service.name} hides its picture from other software, so nudity is only hidden in scenes that have been marked."))
            }
        })

        setContentView(page)
        showWatch(listOfNotNull(Services.byId(title.serviceId)))
        loadMore()
    }

    private fun level(name: String) = name.lowercase().replaceFirstChar { it.uppercase() }

    /** Shows a "Watch on" button for each service known to have the title, then a way to search the rest. */
    private fun showWatch(known: List<Service>) {
        watchHolder.removeAllViews()
        known.forEachIndexed { i, service ->
            watchHolder.addView(Ui.actionButton(this, "Watch on ${service.name}", filled = i == 0, iconRes = R.drawable.ic_play) {
                watch(this, title, service)
            }, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = Ui.dp(this@TitleActivity, 10) })
        }
        val others = Services.connected(this) - known.toSet()
        if (others.isEmpty()) return
        watchHolder.addView(TextView(this).apply {
            text = if (known.isEmpty()) "Find it on" else "Or look for it on"
            textSize = 13f
            setTextColor(Ui.color(context, R.color.text_secondary))
            setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 8))
        })
        val strip = LinearLayout(this)
        for (service in others) strip.addView(Ui.chip(this, service.name, strong = true) { watch(this, title, service) })
        watchHolder.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
    }

    /** Fetches what the catalog knows beyond the basics: more services that carry the title, and its episodes. */
    private fun loadMore() {
        Thread {
            val services = try { Catalog.servicesFor(applicationContext, title) } catch (e: Exception) { null }
            val episodes = try { Catalog.episodes(applicationContext, title) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!services.isNullOrEmpty()) {
                    val mine = Prefs.connectedServices(this)
                    // The viewer's own services first.
                    showWatch(services.mapNotNull { Services.byId(it) }.sortedBy { if (it.id in mine) 0 else 1 })
                }
                if (episodes.isNotEmpty()) showEpisodes(episodes)
            }
        }.start()
    }

    private fun showEpisodes(episodes: List<Episode>) {
        val side = Ui.dp(this, 20)
        val seasons = episodes.map { it.season }.distinct().sorted()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 12), side, 0)
        }
        val chips = LinearLayout(this).apply { setPadding(side, 0, side, 0) }

        fun select(season: Int) {
            chips.removeAllViews()
            for (s in seasons) chips.addView(Ui.chip(this, "Season $s", strong = s == season) { select(s) })
            list.removeAllViews()
            val card = Ui.card(this)
            episodes.filter { it.season == season }.forEachIndexed { i, episode ->
                if (i > 0) card.addView(Ui.divider(this))
                val label = if (episode.name.isEmpty()) "Episode ${episode.number}" else "${episode.number}. ${episode.name}"
                card.addView(Ui.row(this, label) { watch(this, title, Services.byId(title.serviceId), episode) })
            }
            list.addView(card)
        }

        episodesHolder.addView(Ui.shelfTitle(this, "Episodes"))
        episodesHolder.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        episodesHolder.addView(list)
        // Open on the newest season, which is usually the one being watched.
        select(seasons.last())
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private val NETFLIX_TITLE = Regex("netflix\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?title/(\\d+)")

        /**
         * Turns a title's page into its player page where a service's addresses allow it, so
         * Watch starts playing instead of stopping at a description. A chosen episode still
         * goes to the title's page, where the episode can be picked.
         */
        private fun playerLink(service: Service, link: String, episode: Episode?): String {
            if (episode == null && service.id == "netflix") {
                NETFLIX_TITLE.find(link)?.let { return "https://www.netflix.com/watch/" + it.groupValues[1] }
            }
            return link
        }

        fun open(ctx: Context, title: Title) =
            ctx.startActivity(Intent(ctx, TitleActivity::class.java).putExtra(EXTRA_TITLE, title.toJson().toString()))

        /**
         * Opens the title on a service in the built-in browser: its own page there when the
         * catalog gave one, otherwise the service's search results for its name.
         */
        fun watch(ctx: Context, title: Title, service: Service? = Services.byId(title.serviceId), episode: Episode? = null) {
            val url = when {
                service == null -> Prefs.searchPrefix(ctx) + android.net.Uri.encode("watch ${title.name}")
                service.id == title.serviceId && title.link != null -> playerLink(service, title.link, episode)
                else -> service.searchFor(title.name)
            }
            if (episode != null) {
                // Catalogs do not give addresses for single episodes, so the last step is done on the service's page.
                Ui.toast(ctx, "Choose season ${episode.season}, episode ${episode.number} on the page")
            }
            Sounds.play(ctx, Sounds.PLAY)
            val label = if (episode == null) title.name else "${title.name}  \u00B7  S${episode.season} E${episode.number}"
            WatchActivity.open(ctx, url, label)
        }
    }
}
