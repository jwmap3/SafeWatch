package com.safewatch.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.data.Services
import com.safewatch.app.ui.Ui

/** One search, offered on every connected service. Each row opens that service's own results. */
class SearchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val (page, column) = Ui.page(this)

        column.addView(Ui.barButton(this, "‹ Home") { finish() }.apply { setPadding(0, 0, Ui.dp(context, 10), 0) },
            android.widget.LinearLayout.LayoutParams(-2, -2))
        column.addView(Ui.largeTitle(this, query))

        column.addView(Ui.sectionHeader(this, "Find it on"))
        val card = Ui.card(this)
        val connected = Services.connected(this)
        connected.forEach { service ->
            card.addView(Ui.row(this, service.name, leading = Ui.monogram(this, service.name)) {
                BrowserActivity.open(this, service.searchFor(query))
            })
            card.addView(Ui.divider(this, 60))
        }
        card.addView(Ui.row(this, "The web", leading = Ui.monogram(this, "W")) {
            BrowserActivity.open(this, BrowserActivity.WEB_SEARCH + android.net.Uri.encode(query))
        })
        column.addView(card)
        if (connected.isEmpty()) column.addView(Ui.caption(this, "Add your streaming services on the home screen to search them here."))

        setContentView(page)
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
