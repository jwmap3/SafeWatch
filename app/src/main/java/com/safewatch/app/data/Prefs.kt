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
            .apply()
    }

    private fun strictness(name: String?, fallback: Strictness): Strictness =
        Strictness.entries.firstOrNull { it.name == name } ?: fallback

    fun themeMode(ctx: Context): Int = prefs(ctx).getInt("theme", THEME_SYSTEM)

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
}
