package com.safewatch.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.safewatch.core.Action
import com.safewatch.core.FilterSettings
import com.safewatch.core.Strictness

/** Everything the app remembers between launches, apart from marked scenes. */
object Prefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("safewatch", Context.MODE_PRIVATE)

    fun settings(ctx: Context): FilterSettings {
        val p = prefs(ctx)
        return FilterSettings(
            language = strictness(p.getString("language", null), Strictness.MEDIUM),
            blasphemy = p.getBoolean("blasphemy", true),
            nudity = strictness(p.getString("nudity", null), Strictness.MEDIUM),
            nudityAction = if (p.getString("nudityAction", null) == Action.SKIP.name) Action.SKIP else Action.BLUR,
            customWords = p.getStringSet("customWords", emptySet())!!.toSet(),
            allowedWords = p.getStringSet("allowedWords", emptySet())!!.toSet(),
            wordChoices = p.getStringSet("wordsOn", emptySet())!!.associateWith { true } +
                p.getStringSet("wordsOff", emptySet())!!.associateWith { false },
        )
    }

    fun save(ctx: Context, s: FilterSettings) {
        prefs(ctx).edit()
            .putString("language", s.language.name)
            .putBoolean("blasphemy", s.blasphemy)
            .putString("nudity", s.nudity.name)
            .putString("nudityAction", s.nudityAction.name)
            .putStringSet("customWords", s.customWords)
            .putStringSet("allowedWords", s.allowedWords)
            .putStringSet("wordsOn", s.wordChoices.filterValues { it }.keys)
            .putStringSet("wordsOff", s.wordChoices.filterValues { !it }.keys)
            .apply()
    }

    private fun strictness(name: String?, fallback: Strictness): Strictness =
        Strictness.entries.firstOrNull { it.name == name } ?: fallback

    /** Dark unless the viewer chooses otherwise. */
    fun themeMode(ctx: Context): Int = prefs(ctx).getInt("theme", THEME_DARK)

    fun setThemeMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt("theme", mode).apply()
        applyTheme(ctx)
    }

    fun applyTheme(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode(ctx)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun desktopSite(ctx: Context): Boolean = prefs(ctx).getBoolean("desktopSite", false)

    fun setDesktopSite(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("desktopSite", on).apply()

    /** Ids of the streaming services shown on the home screen and used by search. */
    fun connectedServices(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("services", null)?.toSet() ?: Services.defaults

    fun setConnectedServices(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putStringSet("services", ids).apply()

    /** The viewer's own key for the movie catalog; empty when none has been added. */
    fun catalogKey(ctx: Context): String = prefs(ctx).getString("catalogKey", "")!!.trim()

    fun setCatalogKey(ctx: Context, key: String) = prefs(ctx).edit().putString("catalogKey", key.trim()).apply()

    /** The page the browser was last on, so the Browser tab reopens there. */
    fun lastPage(ctx: Context): String? = prefs(ctx).getString("lastPage", null)

    fun setLastPage(ctx: Context, url: String) = prefs(ctx).edit().putString("lastPage", url).apply()

    /** Set once a sign-in started from Settings has been seen through on a service. */
    fun signInSeen(ctx: Context, serviceId: String): Boolean = prefs(ctx).getBoolean("signedIn.$serviceId", false)

    fun setSignInSeen(ctx: Context, serviceId: String, seen: Boolean) =
        prefs(ctx).edit().putBoolean("signedIn.$serviceId", seen).apply()

    /** True for two minutes after "Test the blur" is tapped: faces are blurred so the blur can be seen working. */
    fun testingBlur(ctx: Context): Boolean = System.currentTimeMillis() < prefs(ctx).getLong("testBlurUntil", 0)

    fun startBlurTest(ctx: Context) =
        prefs(ctx).edit().putLong("testBlurUntil", System.currentTimeMillis() + 2 * 60 * 1000).apply()

    /** Whether a video's captions are shown. Off by default: a word that is muted should not be printed on the screen instead. */
    fun showCaptions(ctx: Context): Boolean = prefs(ctx).getBoolean("showCaptions", false)

    fun setShowCaptions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("showCaptions", on).apply()

    /** The strict choice: a video with no captions to go on plays without sound. */
    fun silentWithoutCaptions(ctx: Context): Boolean = prefs(ctx).getBoolean("silentWithoutCaptions", false)

    fun setSilentWithoutCaptions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("silentWithoutCaptions", on).apply()

    /** Whether pages are stopped from opening new windows and from sending the browser somewhere else. */
    fun blockPopups(ctx: Context): Boolean = prefs(ctx).getBoolean("blockPopups", true)

    fun setBlockPopups(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("blockPopups", on).apply()

    // ---- Customisation ----

    /** Loudness of the app's own sounds, 0 to 100. */
    fun soundVolume(ctx: Context): Int = prefs(ctx).getInt("soundVolume", 80)

    fun setSoundVolume(ctx: Context, v: Int) = prefs(ctx).edit().putInt("soundVolume", v.coerceIn(0, 100)).apply()

    /** The tab the app opens on (one of MainActivity's TAB_ values). */
    fun startTab(ctx: Context): Int = prefs(ctx).getInt("startTab", 0)

    fun setStartTab(ctx: Context, tab: Int) = prefs(ctx).edit().putInt("startTab", tab).apply()

    /** The browser's search engine: "google", "duckduckgo" or "bing". */
    fun searchEngine(ctx: Context): String = prefs(ctx).getString("searchEngine", "google")!!

    fun setSearchEngine(ctx: Context, id: String) = prefs(ctx).edit().putString("searchEngine", id).apply()

    fun searchPrefix(ctx: Context): String = when (searchEngine(ctx)) {
        "duckduckgo" -> "https://duckduckgo.com/?q="
        "bing" -> "https://www.bing.com/search?q="
        else -> "https://www.google.com/search?q="
    }

    /** Whether the browser's bar slides away while reading down a page. */
    fun hideBarWhileScrolling(ctx: Context): Boolean = prefs(ctx).getBoolean("autoHideBar", true)

    fun setHideBarWhileScrolling(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoHideBar", on).apply()

    /** How far the player's back and forward buttons jump, in seconds. */
    fun skipSeconds(ctx: Context): Int = prefs(ctx).getInt("skipSeconds", 10)

    fun setSkipSeconds(ctx: Context, s: Int) = prefs(ctx).edit().putInt("skipSeconds", s).apply()

    /** How hidden pictures look: "blur" (shapes and colours move, nothing can be made out) or "black". */
    fun hideStyle(ctx: Context): String = prefs(ctx).getString("hideStyle", "blur")!!

    fun setHideStyle(ctx: Context, style: String) = prefs(ctx).edit().putString("hideStyle", style).apply()

    /** A four-digit PIN asked for before Settings opens; empty for none. */
    fun settingsPin(ctx: Context): String = prefs(ctx).getString("settingsPin", "")!!

    fun setSettingsPin(ctx: Context, pin: String) = prefs(ctx).edit().putString("settingsPin", pin).apply()

    /** The browser's six quick links, as (name, address). Empty slots are left out. */
    fun quickLinks(ctx: Context): List<Pair<String, String>> {
        val saved = prefs(ctx).getString("quickLinks", null) ?: return DEFAULT_QUICK_LINKS
        return try {
            val list = org.json.JSONArray(saved)
            (0 until list.length()).map { list.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        } catch (e: Exception) {
            DEFAULT_QUICK_LINKS
        }
    }

    fun setQuickLinks(ctx: Context, links: List<Pair<String, String>>) = prefs(ctx).edit().putString("quickLinks",
        org.json.JSONArray(links.take(6).map { org.json.JSONArray().put(it.first).put(it.second) }).toString()).apply()

    private val DEFAULT_QUICK_LINKS = listOf(
        "Google" to "https://www.google.com/",
        "YouTube" to "https://m.youtube.com/",
        "Wikipedia" to "https://en.m.wikipedia.org/",
        "Weather" to "https://weather.com/",
        "Pluto TV" to "https://pluto.tv/",
        "Tubi" to "https://tubitv.com/",
    )

    /** Whether a hidden second copy of a video may play ahead of the viewer, so scenes are hidden before they arrive. */
    fun lookAhead(ctx: Context): Boolean = prefs(ctx).getBoolean("lookAhead", true)

    fun setLookAhead(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("lookAhead", on).apply()

    // ---- Colours the viewer picked. 0 means "use the built-in colour". ----

    const val COLOR_PRIMARY = "colorPrimary"
    const val COLOR_BACKGROUND = "colorBackground"
    const val COLOR_CARD = "colorCard"

    // Read on every colour lookup, so the three values are kept in memory.
    @Volatile private var colors: Map<String, Int>? = null

    fun customColor(ctx: Context, which: String): Int {
        val known = colors ?: prefs(ctx).let { p ->
            listOf(COLOR_PRIMARY, COLOR_BACKGROUND, COLOR_CARD).associateWith { p.getInt(it, 0) }
        }.also { colors = it }
        return known[which] ?: 0
    }

    fun setCustomColor(ctx: Context, which: String, color: Int) {
        prefs(ctx).edit().putInt(which, color).apply()
        colors = null
    }

    // ---- Sounds ----

    fun soundPack(ctx: Context): String = prefs(ctx).getString("soundPack", "soft")!!

    fun setSoundPack(ctx: Context, id: String) = prefs(ctx).edit().putString("soundPack", id).apply()

    fun starshipUnlocked(ctx: Context): Boolean = prefs(ctx).getBoolean("starship", false)

    fun unlockStarship(ctx: Context) = prefs(ctx).edit().putBoolean("starship", true).apply()

    // ---- First launch ----

    fun welcomed(ctx: Context): Boolean = prefs(ctx).getBoolean("welcomed", false)

    fun setWelcomed(ctx: Context) = prefs(ctx).edit().putBoolean("welcomed", true).apply()

    // ---- YouTube ----

    /** Channels the viewer follows inside SafeWatch, as channel id to name. Kept on the phone only. */
    fun followedChannels(ctx: Context): Map<String, String> =
        prefs(ctx).getStringSet("ytFollowing", emptySet())!!.associate { it.substringBefore('|') to it.substringAfter('|') }

    fun setFollowing(ctx: Context, channelId: String, name: String, follow: Boolean) {
        val all = followedChannels(ctx).toMutableMap()
        if (follow) all[channelId] = name else all.remove(channelId)
        prefs(ctx).edit().putStringSet("ytFollowing", all.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    /** Subjects whose videos fill the YouTube tab's shelves. */
    fun youtubeTopics(ctx: Context): List<String> =
        prefs(ctx).getString("ytTopics", null)?.split('\n')?.filter { it.isNotBlank() }
            ?: listOf("New movie trailers", "Documentaries", "Music videos", "Science explained")

    fun setYoutubeTopics(ctx: Context, topics: List<String>) =
        prefs(ctx).edit().putString("ytTopics", topics.joinToString("\n")).apply()
}
