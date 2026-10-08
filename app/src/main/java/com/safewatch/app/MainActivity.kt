package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.detect.NudityDetector
import com.safewatch.app.player.PlayerActivity
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui

/**
 * The app's main window: Home, Search and Filters as tabs along the bottom,
 * plus a Browser tab that opens the built-in browser.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var home: HomeScreen
    private lateinit var search: SearchScreen
    private lateinit var youtube: YouTubeScreen
    private lateinit var filters: FiltersScreen
    private val tabViews = ArrayList<Pair<ImageView, TextView>>()
    private var tab = TAB_HOME

    val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some file apps only grant access for now, which is enough to play it.
        }
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Ui.toast(this, "Checking the model…")
        Thread {
            val ok = NudityDetector.install(this, uri)
            runOnUiThread {
                Ui.toast(this, if (ok) "Nudity detection is ready" else "That file is not a model this app can use")
                if (!isDestroyed) filters.rebuild()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(context, R.color.bg))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Ui.divider(context, 0))
            addView(buildTabBar())
        }
        setContentView(root)
        Ui.fitSystemBars(this, root)

        home = HomeScreen(this)
        search = SearchScreen(this)
        youtube = YouTubeScreen(this)
        filters = FiltersScreen(this)
        for (page in listOf(home.view, search.view, youtube.view, filters.view)) content.addView(page, FrameLayout.LayoutParams(-1, -1))

        show(savedInstanceState?.getInt(STATE_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_HOME))
        if (savedInstanceState == null && !Prefs.welcomed(this)) startActivity(Intent(this, WelcomeActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) show(intent.getIntExtra(EXTRA_TAB, TAB_HOME))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        home.refresh()
        if (tab == TAB_FILTERS) filters.onShown()
    }

    /** Lets the viewer tick which streaming services they use. */
    fun editServices(onDone: () -> Unit) {
        val chosen = Prefs.connectedServices(this).toMutableSet()
        val all = Services.choices
        AlertDialog.Builder(this)
            .setTitle("Your services")
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), all.map { it.id in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen += all[i].id else chosen -= all[i].id
            }
            .setPositiveButton("Done") { _, _ ->
                Prefs.setConnectedServices(this, chosen)
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Leaving from Home puts the app in the background instead of closing it, so the browser keeps its page.
        if (tab != TAB_HOME) show(TAB_HOME) else moveTaskToBack(true)
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.color(context, R.color.bar))
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
        }
        val tabs = listOf(
            Triple(TAB_HOME, "Home", R.drawable.ic_home),
            Triple(TAB_SEARCH, "Search", R.drawable.ic_search),
            Triple(TAB_YOUTUBE, "YouTube", R.drawable.ic_play),
            Triple(TAB_BROWSER, "Browser", R.drawable.ic_globe),
            Triple(TAB_FILTERS, "Settings", R.drawable.ic_filters),
        )
        for ((id, label, iconRes) in tabs) {
            val icon = Ui.icon(this, iconRes, R.color.text_secondary)
            val text = TextView(this).apply {
                this.text = label
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, Ui.dp(context, 3), 0, 0)
            }
            tabViews += icon to text
            bar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(icon)
                addView(text)
                setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 2))
                setOnClickListener { Sounds.play(context, Sounds.TAP); show(id) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    /** Shows YouTube's results for a search, in the YouTube tab. */
    fun searchYouTube(query: String) {
        show(TAB_YOUTUBE)
        youtube.search(query)
    }

    /** Switches tab. The Browser tab is its own screen, so it opens on top and the tab shown here stays put. */
    fun show(which: Int) {
        if (which == TAB_BROWSER) {
            BrowserActivity.resume(this)
            return
        }
        tab = which
        home.view.visibility = if (which == TAB_HOME) View.VISIBLE else View.GONE
        search.view.visibility = if (which == TAB_SEARCH) View.VISIBLE else View.GONE
        youtube.view.visibility = if (which == TAB_YOUTUBE) View.VISIBLE else View.GONE
        filters.view.visibility = if (which == TAB_FILTERS) View.VISIBLE else View.GONE
        if (which == TAB_YOUTUBE) youtube.onShown() else youtube.onHidden()
        if (which == TAB_SEARCH) search.onShown() else search.onHidden()
        if (which == TAB_FILTERS) filters.onShown()
        val order = listOf(TAB_HOME, TAB_SEARCH, TAB_YOUTUBE, TAB_BROWSER, TAB_FILTERS)
        tabViews.forEachIndexed { i, (icon, text) ->
            val color = Ui.color(this, if (order[i] == which) R.color.accent else R.color.text_secondary)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            text.setTextColor(color)
        }
    }

    override fun onDestroy() {
        if (::youtube.isInitialized) youtube.close()
        super.onDestroy()
    }

    companion object {
        const val TAB_HOME = 0
        const val TAB_SEARCH = 1
        const val TAB_BROWSER = 2
        const val TAB_FILTERS = 3
        const val TAB_YOUTUBE = 4
        private const val EXTRA_TAB = "tab"
        private const val STATE_TAB = "tab"

        /** Brings the main window back to the front on the given tab, leaving the browser's page as it is. */
        fun open(ctx: Context, tab: Int = TAB_HOME) = ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_TAB, tab)
        )
    }
}
