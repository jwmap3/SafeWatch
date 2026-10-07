package com.safewatch.app

import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.data.Catalog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.data.Title
import com.safewatch.app.player.PlayerActivity
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Ui
import com.safewatch.core.Strictness

/** Home: search, the viewer's streaming services, files on the phone, and filters. */
class MainActivity : AppCompatActivity() {

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some file apps only grant access for now, which is enough to play it.
        }
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onResume() {
        super.onResume()
        build() // rebuilt each time so changed filters and services show at once
    }

    private fun build() {
        val (page, column) = Ui.page(this)

        column.addView(Ui.largeTitle(this, "SafeWatch"))
        column.addView(Ui.subtitle(this, "Watch what you like, without what you don't."))

        val search = EditText(this).apply {
            hint = "Search movies, shows or a website"
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Ui.color(context, R.color.text))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            setOnEditorActionListener { v, _, _ ->
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    if (BrowserActivity.looksLikeAddress(query)) BrowserActivity.open(this@MainActivity, query)
                    else SearchActivity.open(this@MainActivity, query)
                }
                true
            }
        }
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })

        addShelf(column)

        column.addView(Ui.sectionHeader(this, "Your services"))
        val services = Ui.card(this)
        val connected = Services.connected(this)
        connected.forEach { service ->
            services.addView(Ui.row(this, service.name, leading = Ui.monogram(this, service.name)) {
                BrowserActivity.open(this, service.homeUrl)
            })
            services.addView(Ui.divider(this, 60))
        }
        services.addView(Ui.row(this, if (connected.isEmpty()) "Add services" else "Edit services", chevron = false) { editServices() })
        column.addView(services)
        column.addView(Ui.caption(this, "Sign in on the service's own page. Anything you play there goes through your filters."))

        column.addView(Ui.sectionHeader(this, "On this phone"))
        column.addView(Ui.card(this).apply {
            addView(Ui.row(this@MainActivity, "Open a video file") { pickVideo.launch(arrayOf("video/*")) })
        })

        val s = Prefs.settings(this)
        column.addView(Ui.sectionHeader(this, "Filters"))
        column.addView(Ui.card(this).apply {
            addView(Ui.row(this@MainActivity, "Language", label(s.language)) { openFilters() })
            addView(Ui.divider(this@MainActivity))
            addView(Ui.row(this@MainActivity, "Nudity", label(s.nudity)) { openFilters() })
            addView(Ui.divider(this@MainActivity))
            addView(Ui.row(this@MainActivity, "Send to TV") { Ui.sendToTv(this@MainActivity) })
        })

        setContentView(page)
    }

    /** A sideways-scrolling row of this week's popular movies and shows. Shown once a catalog key is added. */
    private fun addShelf(column: LinearLayout) {
        val key = Prefs.catalogKey(this)
        if (key.isEmpty()) return
        val header = Ui.sectionHeader(this, "Popular this week")
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }
        column.addView(header)
        column.addView(scroller)

        fun fill(titles: List<Title>) {
            strip.removeAllViews()
            for (t in titles.filter { it.posterPath != null }.take(20)) {
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 10).toFloat())
                    clipToOutline = true
                    contentDescription = t.name
                    setOnClickListener { SearchActivity.open(context, t) }
                }
                Images.load(Catalog.posterUrl(t.posterPath!!), poster)
                strip.addView(poster, LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 168)).apply { marginEnd = Ui.dp(this@MainActivity, 10) })
            }
        }

        val known = popular
        if (known != null) {
            fill(known)
            return
        }
        Thread {
            val loaded = try { Catalog.trending(key) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (loaded.isNullOrEmpty()) {
                    header.visibility = View.GONE
                    scroller.visibility = View.GONE
                } else {
                    popular = loaded
                    fill(loaded)
                }
            }
        }.start()
    }

    private fun label(s: Strictness): String = s.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun openFilters() = startActivity(Intent(this, FiltersActivity::class.java))

    private fun editServices() {
        val chosen = Prefs.connectedServices(this).toMutableSet()
        val all = Services.all
        AlertDialog.Builder(this)
            .setTitle("Your services")
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), all.map { it.id in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen += all[i].id else chosen -= all[i].id
            }
            .setPositiveButton("Done") { _, _ ->
                Prefs.setConnectedServices(this, chosen)
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Kept for as long as the app stays open, so the shelf does not reload on every visit to the home screen. */
        private var popular: List<Title>? = null
    }
}
