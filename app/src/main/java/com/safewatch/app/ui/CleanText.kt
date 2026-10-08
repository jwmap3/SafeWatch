package com.safewatch.app.ui

import android.content.Context
import com.safewatch.app.data.Prefs
import com.safewatch.core.FilterSettings
import com.safewatch.core.ProfanityMatcher
import com.safewatch.core.Strictness

/**
 * Text shown in the app's own layout (YouTube titles, descriptions and comments) with the
 * filtered words part-hidden, as the word list shows them: "sh*t" becomes "s***".
 */
object CleanText {
    @Volatile private var cached: Pair<FilterSettings, ProfanityMatcher>? = null

    fun of(ctx: Context, text: String): String {
        if (text.isEmpty()) return text
        val settings = Prefs.settings(ctx)
        if (settings.language == Strictness.OFF) return text
        val matcher = cached?.takeIf { it.first == settings }?.second ?: ProfanityMatcher(settings).also { cached = settings to it }
        val found = matcher.find(text)
        if (found.isEmpty()) return text
        val out = StringBuilder(text)
        var before = Int.MAX_VALUE
        for (m in found.sortedByDescending { it.start }) {
            if (m.end > before) continue // inside a longer match already hidden
            before = m.start
            val word = text.substring(m.start, m.end)
            val first = word.firstOrNull { it.isLetterOrDigit() } ?: '*'
            out.replace(m.start, m.end, first + "*".repeat(maxOf(2, word.length - 1)))
        }
        return out.toString()
    }
}
