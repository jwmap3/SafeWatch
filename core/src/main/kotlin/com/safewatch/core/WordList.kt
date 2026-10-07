package com.safewatch.core

/**
 * One word the viewer can switch on or off, with every form of it that is
 * muted. [label] is how it is shown on screen, part-hidden.
 *
 * Pattern syntax: `word` matches the whole word, `word*` matches words that
 * start with it, `*word*` matches words that contain it. Entries with a space
 * are phrases and match whole words in sequence.
 */
data class WordGroup(
    val id: String,
    val label: String,
    /** 3 strong, 2 common, 1 mild. Decides which filter levels mute it by default. */
    val level: Int,
    val patterns: List<String>,
    /** Blasphemy follows its own switch instead of the level. */
    val blasphemy: Boolean = false,
)

/**
 * The built-in words. Slurs are deliberately not listed here; add any you
 * want muted under "Extra words to mute" in the app.
 */
object WordList {
    val groups = listOf(
        WordGroup("fuck", "F*ck", 3, listOf("*fuck*")),
        WordGroup("cunt", "C*nt", 3, listOf("cunt*")),
        WordGroup("cock", "C*ck", 3, listOf("cock", "cocks", "cocksuck*")),
        WordGroup("pussy", "P*ssy", 3, listOf("pussy", "pussies")),
        WordGroup("twat", "Tw*t", 3, listOf("twat*")),
        WordGroup("wank", "W*nk", 3, listOf("wank*")),

        WordGroup("shit", "Sh*t", 2, listOf("*shit*")),
        WordGroup("bitch", "B*tch", 2, listOf("*bitch*")),
        WordGroup("ass", "A*s", 2, listOf("ass", "asses", "arse", "arses", "jackass*", "dumbass*", "badass*", "smartass*", "kickass", "asshat*")),
        WordGroup("asshole", "A**hole", 2, listOf("*asshole*", "*arsehole*")),
        WordGroup("bastard", "B*stard", 2, listOf("bastard*")),
        WordGroup("dick", "D*ck", 2, listOf("dick", "dicks", "dickhead*")),
        WordGroup("prick", "Pr*ck", 2, listOf("prick", "pricks")),
        WordGroup("piss", "P*ss", 2, listOf("piss*")),
        WordGroup("slut", "Sl*t", 2, listOf("slut*")),
        WordGroup("whore", "Wh*re", 2, listOf("whore*")),
        WordGroup("douche", "D*uche", 2, listOf("douche*")),
        WordGroup("bollocks", "B*llocks", 2, listOf("bollocks")),
        WordGroup("tits", "T*ts", 2, listOf("tits", "titties")),

        WordGroup("damn", "D*mn", 1, listOf("*damn*", "dammit")),
        WordGroup("hell", "H*ll", 1, listOf("hell")),
        WordGroup("crap", "Cr*p", 1, listOf("crap", "crappy", "crapped")),
        WordGroup("frick", "Fr*ck, fr*ggin", 1, listOf("frick*", "friggin*", "freakin", "freaking")),

        WordGroup("goddamn", "G*ddamn", 3, listOf("*goddam*", "god damn"), blasphemy = true),
        WordGroup("omg", "Oh my G*d", 3, listOf("omg", "oh my god", "oh god", "my god", "good god", "swear to god", "for gods sake"), blasphemy = true),
        WordGroup("jesus", "J*sus Chr*st", 3, listOf("jesus christ", "for christs sake"), blasphemy = true),
    )
}
