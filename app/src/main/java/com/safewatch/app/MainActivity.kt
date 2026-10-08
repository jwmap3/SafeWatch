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
 * The app's main window: Home, Search, YouTube and Settings as tabs along the bottom, plus a Browser tab
 * that opens the built-in browser. The viewer can put the tabs in any order and hide all but Settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var home: HomeScreen
    private lateinit var search: SearchScreen
    private lateinit var youtube: YouTubeScreen
    private lateinit var filters: FiltersScreen
    private val tabViews = ArrayList<Triple<Int, ImageView, TextView>>()
    private lateinit var tabBar: FrameLayout
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
            tabBar = FrameLayout(context)
            addView(tabBar)
        }
        rebuildTabBar()
        setContentView(root)
        Ui.fitSystemBars(this, root)

        home = HomeScreen(this)
        search = SearchScreen(this)
        youtube = YouTubeScreen(this)
        filters = FiltersScreen(this)
        for (page in listOf(home.view, search.view, youtube.view, filters.view)) content.addView(page, FrameLayout.LayoutParams(-1, -1))

        // A start tab the viewer has since hidden gives way to the first tab on the bar.
        // Opening the app plays its short opening animation first; the welcome screen and the TV offer wait for it.
        val opening = savedInstanceState == null && !intent.hasExtra(EXTRA_TAB)
        if (opening) {
            introPlaying = true
            val played = com.safewatch.app.ui.Intro.play(this) {
                introPlaying = false
                if (!Prefs.welcomed(this)) startActivity(Intent(this, WelcomeActivity::class.java))
                else com.safewatch.app.tv.TvMode.offer(this, com.safewatch.app.tv.TvMode.display(this))
            }
            if (!played) introPlaying = false
        }

        val start = Prefs.startTab(this).takeIf { it !in Prefs.hiddenTabs(this) } ?: baseTab()
        show(savedInstanceState?.getInt(STATE_TAB) ?: intent.getIntExtra(EXTRA_TAB, if (start == TAB_BROWSER || start == TAB_FILTERS) baseTab() else start))
        if (savedInstanceState == null && !intent.hasExtra(EXTRA_TAB) && start == TAB_BROWSER && Prefs.welcomed(this)) show(TAB_BROWSER)
        if (savedInstanceState == null && !Prefs.welcomed(this) && !opening) startActivity(Intent(this, WelcomeActivity::class.java))
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

    private var stopWatchingTv: (() -> Unit)? = null
    private var introPlaying = false

    override fun onStart() {
        super.onStart()
        // Connecting the phone to a TV (Smart View, or a cable) offers TV Mode straight away.
        stopWatchingTv = com.safewatch.app.tv.TvMode.watch(this) { if (!introPlaying) com.safewatch.app.tv.TvMode.offer(this, it) }
        if (!introPlaying) com.safewatch.app.tv.TvMode.offer(this, com.safewatch.app.tv.TvMode.display(this))
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
        // Back goes to the first tab on the bar; from there it puts the app in the background instead of
        // closing it, so the browser keeps its page.
        if (tab != baseTab()) show(baseTab()) else moveTaskToBack(true)
    }

    /** The first tab on the bar that shows in this window (the Browser opens on top of it). */
    private fun baseTab(): Int {
        val hidden = Prefs.hiddenTabs(this)
        return Prefs.tabOrder(this).firstOrNull { it !in hidden && it != TAB_BROWSER } ?: TAB_FILTERS
    }

    /** Lays the tab bar out again after the viewer changes its order, hides a tab or changes its look. */
    fun rebuildTabBar() {
        tabBar.removeAllViews()
        tabBar.addView(buildTabBar())
        if (::home.isInitialized) show(tab)
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.color(context, R.color.bar))
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
        }
        tabViews.clear()
        val hidden = Prefs.hiddenTabs(this)
        val names = Prefs.tabNames(this)
        for (id in Prefs.tabOrder(this).filter { it !in hidden }) {
            val (label, iconRes) = tabInfo(id)
            val icon = Ui.icon(this, iconRes, R.color.text_secondary, if (names) 24 else 26)
            val text = TextView(this).apply {
                this.text = label
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, Ui.dp(context, 3), 0, 0)
                visibility = if (names) View.VISIBLE else View.GONE
            }
            tabViews += Triple(id, icon, text)
            bar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = label
                addView(icon)
                addView(text)
                setPadding(0, Ui.dp(context, if (names) 4 else 9), 0, Ui.dp(context, if (names) 2 else 9))
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
        // With a PIN set, Settings only opens once it is given, so children cannot switch the filters off.
        if (which == TAB_FILTERS && Prefs.settingsPin(this).isNotEmpty() && !settingsUnlocked) {
            askForPin { show(TAB_FILTERS) }
            return
        }
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
        tabViews.forEach { (id, icon, text) ->
            val color = Ui.color(this, if (id == which) R.color.accent else R.color.text_secondary)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            text.setTextColor(color)
        }
    }

    override fun onDestroy() {
        if (::home.isInitialized) home.release()
        if (::youtube.isInitialized) youtube.close()
        super.onDestroy()
    }

    override fun onStop() {
        stopWatchingTv?.invoke()
        stopWatchingTv = null
        super.onStop()
        // Leaving the app locks Settings again.
        if (!isChangingConfigurations) settingsUnlocked = false
    }

    private fun askForPin(then: () -> Unit) {
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            gravity = Gravity.CENTER
            textSize = 22f
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings are locked")
            .setMessage("Enter the PIN to change the filters.")
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                if (field.text.toString() == Prefs.settingsPin(this)) {
                    settingsUnlocked = true
                    then()
                } else {
                    Ui.toast(this, "That PIN is not right")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    companion object {
        const val TAB_HOME = 0
        const val TAB_SEARCH = 1
        const val TAB_BROWSER = 2
        const val TAB_FILTERS = 3
        const val TAB_YOUTUBE = 4
        /** The tabs, in the order they sit along the bottom until the viewer arranges them (Settings > Tabs). */
        val TAB_ORDER = listOf(TAB_HOME, TAB_BROWSER, TAB_SEARCH, TAB_YOUTUBE, TAB_FILTERS)

        /** Each tab's name and icon. */
        fun tabInfo(id: Int): Pair<String, Int> = when (id) {
            TAB_HOME -> "Home" to R.drawable.ic_home
            TAB_BROWSER -> "Browser" to R.drawable.ic_globe
            TAB_SEARCH -> "Search" to R.drawable.ic_search
            TAB_YOUTUBE -> "YouTube" to R.drawable.ic_play
            else -> "Settings" to R.drawable.ic_filters
        }

        /** Whether Settings has been opened with its PIN since the app was last left. */
        var settingsUnlocked = false
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
