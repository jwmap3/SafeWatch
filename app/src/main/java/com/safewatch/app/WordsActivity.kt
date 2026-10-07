package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.data.Prefs
import com.safewatch.app.ui.Ui
import com.safewatch.core.FilterSettings
import com.safewatch.core.Strictness
import com.safewatch.core.WordGroup
import com.safewatch.core.WordList

/**
 * Every built-in word with its own switch, shown part-hidden. The filter
 * level decides which start switched on; anything changed here stays as set.
 */
class WordsActivity : AppCompatActivity() {

    private lateinit var settings: FilterSettings
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Prefs.settings(this)
        val (page, col) = Ui.page(this, ownWindow = true)
        column = col
        setContentView(page)
        build()
    }

    private fun build() {
        column.removeAllViews()
        column.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = Ui.dp(context, 6); marginStart = -Ui.dp(context, 11) }
        })
        column.addView(Ui.largeTitle(this, "Words").apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })

        if (settings.language == Strictness.OFF) {
            column.addView(Ui.subtitle(this, "The language filter is off, so nothing is muted. Turn it on in Settings to choose words."))
            return
        }
        val muted = WordList.groups.count { settings.mutes(it) }
        column.addView(Ui.subtitle(this, "$muted of ${WordList.groups.size} muted. Every form of a word is covered, so “Sh*t” also mutes “bullsh*t”."))

        section("Strong", WordList.groups.filter { !it.blasphemy && it.level == 3 })
        section("Common", WordList.groups.filter { !it.blasphemy && it.level == 2 })
        section("Mild", WordList.groups.filter { !it.blasphemy && it.level == 1 })
        section("Blasphemy", WordList.groups.filter { it.blasphemy })

        if (settings.wordChoices.isNotEmpty()) {
            column.addView(Ui.sectionHeader(this, ""))
            column.addView(Ui.card(this).apply {
                addView(Ui.row(context, "Reset to the ${settings.language.name.lowercase()} level", chevron = false) {
                    save(settings.copy(wordChoices = emptyMap()))
                    build()
                })
            })
        }
        column.addView(Ui.caption(this, "For a word that is not listed, use Extra words to mute in Settings."))
    }

    private fun section(name: String, groups: List<WordGroup>) {
        column.addView(Ui.sectionHeader(this, name))
        val card = Ui.card(this)
        groups.forEachIndexed { i, group ->
            if (i > 0) card.addView(Ui.divider(this))
            card.addView(Ui.switchRow(this, group.label, settings.mutes(group)) { on ->
                // A choice that matches the level is not kept, so the word follows the level again.
                val choices = settings.wordChoices.toMutableMap()
                if (on == settings.mutesByDefault(group)) choices.remove(group.id) else choices[group.id] = on
                save(settings.copy(wordChoices = choices))
            })
        }
        column.addView(card)
    }

    private fun save(changed: FilterSettings) {
        settings = changed
        Prefs.save(this, changed)
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, WordsActivity::class.java))
    }
}
