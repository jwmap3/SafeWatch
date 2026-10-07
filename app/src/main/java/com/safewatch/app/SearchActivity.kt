package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Service
import com.safewatch.app.data.Services
import com.safewatch.app.data.Title
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Ui
import java.util.Locale

/**
 * One search across every connected service.
 *
 * With a catalog key it first lists matching movies and shows; choosing one
 * shows which of the viewer's services include it. Either way, each service
 * row opens that service's own search results in the built-in browser.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var query: String
    private var title: Title? = null
    private lateinit var titlesHolder: LinearLayout
    private lateinit var servicesHolder: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (id != 0) {
            title = Title(id, intent.getStringExtra(EXTRA_TYPE) ?: "movie", query, intent.getStringExtra(EXTRA_YEAR).orEmpty(), null)
        }
        val (page, column) = Ui.page(this)
        column.addView(Ui.barButton(this, "‹ Back") { finish() }.apply { setPadding(0, 0, Ui.dp(context, 10), 0) },
            LinearLayout.LayoutParams(-2, -2))
        column.addView(Ui.largeTitle(this, query))
        title?.year?.takeIf { it.isNotEmpty() }?.let { column.addView(Ui.subtitle(this, it)) }

        titlesHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servicesHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(titlesHolder)
        column.addView(servicesHolder)
        setContentView(page)

        showServices(null)
        val key = Prefs.catalogKey(this)
        if (key.isNotEmpty()) {
            val chosen = title
            if (chosen == null) loadTitles(key) else loadProviders(key, chosen)
        }
    }

    private fun loadTitles(key: String) {
        titlesHolder.addView(Ui.sectionHeader(this, "Movies and shows"))
        val card = Ui.card(this)
        card.addView(Ui.row(this, "Searching…", chevron = false))
        titlesHolder.addView(card)
        Thread {
            val found = try { Catalog.search(key, query).take(8) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                card.removeAllViews()
                when {
                    found == null -> card.addView(Ui.row(this, "The catalog could not be reached", chevron = false))
                    found.isEmpty() -> card.addView(Ui.row(this, "No titles found", chevron = false))
                    else -> found.forEachIndexed { i, t ->
                        if (i > 0) card.addView(Ui.divider(this, 68))
                        card.addView(Ui.row(this, t.name, t.year, leading = poster(t)) { open(this, t) })
                    }
                }
            }
        }.start()
    }

    private fun loadProviders(key: String, chosen: Title) {
        Thread {
            val region = Locale.getDefault().country.ifEmpty { "US" }
            val providers = try { Catalog.providers(key, chosen, region) } catch (e: Exception) { null }
            runOnUiThread { if (!isDestroyed && providers != null) showServices(providers) }
        }.start()
    }

    private fun poster(t: Title): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 6).toFloat())
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 60)).apply { marginEnd = Ui.dp(context, 12) }
        t.posterPath?.let { Images.load(Catalog.posterUrl(it), this) }
    }

    /** Lists the connected services. When [providers] is known, the ones that include the title come first. */
    private fun showServices(providers: List<String>?) {
        servicesHolder.removeAllViews()
        val connected = Services.connected(this)
        val included = if (providers == null) emptyList() else connected.filter { it.carries(providers) }
        val others = connected - included.toSet()

        if (included.isNotEmpty()) {
            servicesHolder.addView(Ui.sectionHeader(this, "Included with"))
            servicesHolder.addView(serviceCard(included, withWeb = false))
        }
        servicesHolder.addView(Ui.sectionHeader(this, if (included.isEmpty()) "Find it on" else "Also search"))
        servicesHolder.addView(serviceCard(others, withWeb = true))

        if (providers != null && included.isEmpty()) {
            servicesHolder.addView(Ui.caption(this, "None of your services lists this title as included right now. It may still be there to rent or buy."))
        }
        if (connected.isEmpty()) {
            servicesHolder.addView(Ui.caption(this, "Add your streaming services on the home screen to search them here."))
        }
        if (providers != null) {
            servicesHolder.addView(Ui.caption(this, "Availability from JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB."))
        }
    }

    private fun serviceCard(services: List<Service>, withWeb: Boolean): LinearLayout {
        val card = Ui.card(this)
        services.forEachIndexed { i, service ->
            if (i > 0) card.addView(Ui.divider(this, 60))
            card.addView(Ui.row(this, service.name, leading = Ui.monogram(this, service.name)) {
                BrowserActivity.open(this, service.searchFor(query))
            })
        }
        if (withWeb) {
            if (services.isNotEmpty()) card.addView(Ui.divider(this, 60))
            card.addView(Ui.row(this, "The web", leading = Ui.monogram(this, "W")) {
                BrowserActivity.open(this, BrowserActivity.WEB_SEARCH + Uri.encode(query))
            })
        }
        return card
    }

    companion object {
        const val EXTRA_QUERY = "query"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_YEAR = "year"

        fun open(ctx: Context, query: String) =
            ctx.startActivity(Intent(ctx, SearchActivity::class.java).putExtra(EXTRA_QUERY, query))

        /** Opens the page for one catalog title, showing where it can be watched. */
        fun open(ctx: Context, title: Title) = ctx.startActivity(
            Intent(ctx, SearchActivity::class.java)
                .putExtra(EXTRA_QUERY, title.name).putExtra(EXTRA_ID, title.id)
                .putExtra(EXTRA_TYPE, title.type).putExtra(EXTRA_YEAR, title.year)
        )
    }
}
