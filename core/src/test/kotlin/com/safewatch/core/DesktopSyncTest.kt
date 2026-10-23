package com.safewatch.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The computer version of edenOS has its own copy of the word list and checks its language filter against answers
 * worked out here. This fails when those files are out of date: run it with SAFEWATCH_WRITE_DESKTOP=1 to write them again.
 */
class DesktopSyncTest {
    fun q(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c.code < 0x20 || c.code > 0x7e -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    private fun make(desktop: String): Pair<String, String> {
        // The word list, for the computer version.
        val wordlist = (WordList.groups.joinToString(",\n  ", "[\n  ", "\n]\n") { g ->
            "{\"id\": ${q(g.id)}, \"label\": ${q(g.label)}, \"level\": ${g.level}, \"blasphemy\": ${g.blasphemy}, \"patterns\": [" +
                g.patterns.joinToString(", ") { q(it) } + "]}"
        })
        val lines = File("$desktop/test/lines.txt").readLines().filter { it.isNotEmpty() }.map { it.replace("\\u2002", " ").replace("\\u00a0", " ") }
        val variants = listOf(
            "{}" to FilterSettings(),
            "{\"language\":\"LOW\"}" to FilterSettings(language = Strictness.LOW),
            "{\"language\":\"HIGH\"}" to FilterSettings(language = Strictness.HIGH),
            "{\"language\":\"OFF\"}" to FilterSettings(language = Strictness.OFF),
            "{\"blasphemy\":false}" to FilterSettings(blasphemy = false),
            "{\"allowedWords\":[\"ass\",\"damn\"]}" to FilterSettings(allowedWords = setOf("ass", "damn")),
            "{\"customWords\":[\"heck\",\"dang it\",\"*poop*\"]}" to FilterSettings(customWords = setOf("heck", "dang it", "*poop*")),
            "{\"wordChoices\":{\"fuck\":false,\"damn\":true,\"omg\":false}}" to FilterSettings(wordChoices = mapOf("fuck" to false, "damn" to true, "omg" to false)),
            "{\"wordChoices\":{" + WordList.groups.joinToString(",") { "\"${it.id}\":false" } + "}}" to FilterSettings(wordChoices = WordList.groups.associate { it.id to false }),
        )
        val sb = StringBuilder("{\n\"words\": [\n")
        sb.append(variants.joinToString(",\n") { (json, settings) ->
            val m = ProfanityMatcher(settings)
            "{\"settings\": $json, \"found\": [" + lines.joinToString(",") { line ->
                "[" + q(line) + ",[" + m.find(line).joinToString(",") { "[${it.start},${it.end},${it.level}]" } + "]]"
            } + "]}"
        })
        sb.append("\n],\n\"files\": [\n")
        val files = File("$desktop/test/captions").listFiles()!!.sortedBy { it.name }
        sb.append(files.joinToString(",\n") { f ->
            val text = f.readText()
            val cues = if (CaptionFormats.recognises(text)) CaptionFormats.parse(text) else emptyList()
            val tags = CueTagger.tagsFor(cues, ProfanityMatcher(FilterSettings()))
            "{\"name\": ${q(f.name)}, \"recognised\": ${CaptionFormats.recognises(text)}, \"cues\": [" +
                cues.joinToString(",") { "[${it.startMs},${it.endMs},${q(it.text)}]" } + "], \"mute\": [" +
                tags.joinToString(",") { "[${it.startMs},${it.endMs},${it.level}]" } + "]}"
        })
        sb.append("\n]\n}\n")
        return wordlist to sb.toString()
    }

    @Test fun desktopWordListAndAnswersAreUpToDate() {
        val desktop = listOf("../desktop", "desktop").firstOrNull { File(it, "test/lines.txt").exists() } ?: return
        val (wordlist, vectors) = make(desktop)
        if (System.getenv("SAFEWATCH_WRITE_DESKTOP") == "1") {
            File(desktop, "src/shared/wordlist.json").writeText(wordlist)
            File(desktop, "test/vectors.json").writeText(vectors)
        }
        assertEquals(wordlist, File(desktop, "src/shared/wordlist.json").readText(), "desktop/src/shared/wordlist.json is out of date")
        assertEquals(vectors, File(desktop, "test/vectors.json").readText(), "desktop/test/vectors.json is out of date")
    }
}
