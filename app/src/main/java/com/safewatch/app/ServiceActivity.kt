package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Accounts
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Service
import com.safewatch.app.data.Services
import com.safewatch.app.data.Shelf
import com.safewatch.app.ui.Ui

/**
 * One streaming service's page in SafeWatch's layout: its popular and new
 * shows and its biggest genres, as shelves. Titles open on their own pages
 * and play in the app's player.
 *
 * Rows that are personal to an account, such as Continue Watching, are not
 * here: services keep those private, so they are only on the service's own
 * site, which the button at the top opens.
 */
class ServiceActivity : AppCompatActivity() {

    private lateinit var service: Service
    private lateinit var column: LinearLayout
    private lateinit var shelves: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = Services.byId(intent.getStringExtra(EXTRA_SERVICE)) ?: run {
            finish()
            return
        }
        val (page, col) = Ui.page(this, padded = false, ownWindow = true)
        column = col
        shelves = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(page)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::column.isInitialized) buildHeader() // the sign-in may have changed while away
    }

    private fun buildHeader() {
        val side = Ui.dp(this, 20)
        column.removeAllViews()
        column.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = Ui.dp(context, 6); marginStart = Ui.dp(context, 9) }
        })
        column.addView(Ui.largeTitle(this, service.name).apply { setPadding(side, 0, side, Ui.dp(context, 10)) })
        val signedIn = Accounts.isSignedIn(this, service)
        column.addView(LinearLayout(this).apply {
            setPadding(side, 0, side, 0)
            addView(Ui.actionButton(context, if (signedIn) "Signed in" else "Sign in", filled = !signedIn) { WatchActivity.signIn(context, service) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(context, 10) })
            addView(Ui.actionButton(context, "Open ${service.name}", filled = false) { WatchActivity.open(context, service.homeUrl, service.name) },
                LinearLayout.LayoutParams(0, -2, 1f))
        })
        column.addView(shelves)
    }

    private fun load() {
        shelves.addView(Ui.caption(this, "Loading titles…").apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 28), Ui.dp(context, 20), 0) })
        Thread {
            val found = try { Catalog.servicePage(applicationContext, service) } catch (e: Exception) { emptyList() }
            runOnUiThread { if (!isDestroyed) show(found) }
        }.start()
    }

    private fun show(found: List<Shelf>) {
        shelves.removeAllViews()
        if (found.isEmpty()) {
            shelves.addView(Ui.caption(this, "No titles could be loaded for ${service.name} right now. You can still open its site with the button above.").apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 28), Ui.dp(context, 20), 0)
            })
            return
        }
        for (shelf in found) {
            shelves.addView(Ui.shelfTitle(this, shelf.name))
            val strip = LinearLayout(this).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 10), 0) }
            for (title in shelf.titles) {
                strip.addView(Ui.poster(this, title.name, title.thumbnail, 118) { TitleActivity.open(this, title) }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 10)
                })
            }
            shelves.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            })
        }
        shelves.addView(Ui.caption(this, "Show information from TVmaze.").apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 28), Ui.dp(context, 20), 0) })
    }

    companion object {
        private const val EXTRA_SERVICE = "service"

        fun open(ctx: Context, service: Service) =
            ctx.startActivity(Intent(ctx, ServiceActivity::class.java).putExtra(EXTRA_SERVICE, service.id))
    }
}
