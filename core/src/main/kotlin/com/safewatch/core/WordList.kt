package com.safewatch.core

/**
 * Built-in word lists, by level.
 *
 * Pattern syntax: `word` matches the whole word, `word*` matches words that
 * start with it, `*word*` matches words that contain it. Entries with a space
 * are phrases and match whole words in sequence.
 *
 * Slurs are deliberately not listed here; add any you want muted under
 * "Extra words to mute" in the app.
 */
object WordList {
    val strong = listOf(
        "*fuck*", "cunt*", "cock", "cocks", "cocksuck*", "pussy", "pussies",
        "twat*", "wank*", "jizz*",
    )

    val moderate = listOf(
        "*shit*", "*bitch*", "ass", "asses", "arse", "arses", "*asshole*", "*arsehole*",
        "jackass*", "dumbass*", "badass*", "smartass*", "kickass", "asshat*",
        "bastard*", "dick", "dicks", "dickhead*", "prick", "pricks", "piss*",
        "slut*", "whore*", "douche*", "bollocks", "tits", "titties",
    )

    val mild = listOf(
        "*damn*", "dammit", "hell", "crap", "crappy", "crapped",
        "frick*", "friggin*", "freakin", "freaking",
    )

    val blasphemy = listOf(
        "*goddam*", "omg", "oh my god", "oh god", "my god", "good god",
        "god damn", "jesus christ", "for christs sake", "for gods sake", "swear to god",
    )
}
