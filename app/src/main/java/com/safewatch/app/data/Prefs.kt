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
