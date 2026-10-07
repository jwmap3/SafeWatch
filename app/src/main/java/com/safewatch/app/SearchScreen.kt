package com.safewatch.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.data.Title
import com.safewatch.app.ui.Ui

/** The search tab: finds shows and movies by name, and offers the same search on each service. */
class SearchScreen(private val activity: MainActivity) {

    val view: View
    private val field: EditText
    private val results: LinearLayout
    private var searchNumber = 0

    init {
        val (page, column) = Ui.page(activity)
        view = page
        column.addView(Ui.largeTitle(activity, "Search"))
        field = EditText(activity).apply {
            hint = "Shows, movies or a website"
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Ui.color(context, R.color.text))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            setOnEditorActionListener { _, _, _ ->
                submit()
                true
            }
        }
        column.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 8) })
        results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(results)
        results.addView(Ui.caption(activity, "Search by name, then choose where to watch. A web address opens in the browser."))
    }

    fun onShown() {
        if (field.text.isEmpty()) {
            field.requestFocus()
            keyboard().showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun onHidden() {
        keyboard().hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun keyboard() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun submit() {
        val query = field.text.toString().trim()
        if (query.isEmpty()) return
        onHidden()
        field.clearFocus()
        if (BrowserActivity.looksLikeAddress(query)) {
            BrowserActivity.open(activity, query)
            return
        }
        val number = ++searchNumber
        results.removeAllViews()
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.caption(activity, "Searching…").apply { setPadding(0, Ui.dp(context, 20), 0, 0) })
        results.addView(titles)
        results.addView(servicesCard(query))

        Thread {
            val mine = Prefs.connectedServices(activity)
            // Titles on the viewer's own services come first.
            val found = try {
                Catalog.search(activity.applicationContext, query).sortedBy { if (it.serviceId in mine) 0 else 1 }
            } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isDestroyed || number != searchNumber) return@runOnUiThread
                titles.removeAllViews()
                when {
                    found == null -> titles.addView(note("The catalog could not be reached. You can still search each service below."))
                    found.isEmpty() -> titles.addView(note("No titles found by that name."))
                    else -> grid(titles, found.take(12))
                }
            }
        }.start()
    }

    private fun note(text: String): TextView = Ui.caption(activity, text).apply { setPadding(0, Ui.dp(context, 20), 0, 0) }

    /** Posters three to a row, each with its name underneath. */
    private fun grid(into: LinearLayout, found: List<Title>) {
        val gap = 10
        val screenDp = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
        val width = (screenDp - 40 - 2 * gap) / 3
        for (rowTitles in found.chunked(3)) {
            val row = LinearLayout(activity).apply { setPadding(0, Ui.dp(context, 18), 0, 0) }
            rowTitles.forEachIndexed { i, title ->
                row.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.poster(context, title.name, title.poster, width) { TitleActivity.open(activity, title) })
                    addView(TextView(context).apply {
                        text = title.name
                        textSize = 13f
                        maxLines = 2
                        setTextColor(Ui.color(context, R.color.text))
                        setPadding(0, Ui.dp(context, 6), 0, 0)
                    }, LinearLayout.LayoutParams(Ui.dp(context, width), -2))
                    val detail = listOfNotNull(title.year.ifEmpty { null }, Services.byId(title.serviceId)?.name).joinToString(" · ")
                    if (detail.isNotEmpty()) addView(TextView(context).apply {
                        text = detail
                        textSize = 12f
                        maxLines = 1
                        setTextColor(Ui.color(context, R.color.text_secondary))
                    }, LinearLayout.LayoutParams(Ui.dp(context, width), -2))
                }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = Ui.dp(activity, gap) })
            }
            into.addView(row)
        }
    }

    private fun servicesCard(query: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.sectionHeader(context, "Search for “$query” on"))
        val card = Ui.card(context)
        Services.connected(context).forEach { service ->
            card.addView(Ui.row(context, service.name, leading = Ui.monogram(context, service.name)) {
                WatchActivity.open(activity, service.searchFor(query), service.name)
            })
            card.addView(Ui.divider(context, 60))
        }
        card.addView(Ui.row(context, "The web", leading = Ui.monogram(context, "W")) {
            BrowserActivity.open(activity, BrowserActivity.WEB_SEARCH + Uri.encode(query))
        })
        addView(card)
    }
}
