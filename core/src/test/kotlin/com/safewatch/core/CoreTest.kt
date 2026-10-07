package com.safewatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfanityMatcherTest {
    private fun matcher(s: FilterSettings = FilterSettings()) = ProfanityMatcher(s)

    @Test fun findsWordAndItsPosition() {
        val text = "well, shit happens"
        val m = matcher().find(text).single()
        assertEquals("shit", text.substring(m.start, m.end))
        assertEquals(2, m.level)
    }

    @Test fun wholeWordPatternsDoNotMatchInsideOtherWords() {
        val m = matcher(FilterSettings(language = Strictness.HIGH))
        assertFalse(m.containsProfanity("Hello, pass the class notes to the shell"))
        assertFalse(m.containsProfanity("a scrap of paper"))
        assertTrue(m.containsProfanity("what the hell"))
        assertTrue(m.containsProfanity("kiss my ass"))
    }

    @Test fun strictnessDecidesWhichLevelsMatch() {
        val low = matcher(FilterSettings(language = Strictness.LOW, blasphemy = false))
        assertFalse(low.containsProfanity("damn, that is some bullshit"))
        assertTrue(low.containsProfanity("what the FUCK"))
        val medium = matcher(FilterSettings(language = Strictness.MEDIUM, blasphemy = false))
        assertTrue(medium.containsProfanity("bullshit"))
        assertFalse(medium.containsProfanity("damn it"))
        assertTrue(matcher(FilterSettings(language = Strictness.HIGH)).containsProfanity("damn it"))
        assertFalse(matcher(FilterSettings(language = Strictness.OFF)).containsProfanity("fuck"))
    }

    @Test fun compoundsAndApostrophesMatch() {
        assertTrue(matcher().containsProfanity("you motherfucker"))
        assertTrue(matcher().containsProfanity("for Christ's sake"))
    }

    @Test fun blasphemyIsItsOwnSwitch() {
        assertTrue(matcher().containsProfanity("Oh my God, look"))
        assertFalse(matcher(FilterSettings(blasphemy = false)).containsProfanity("Oh my God, look"))
        assertFalse(matcher().containsProfanity("They prayed to God"))
    }

    @Test fun customAndAllowedWords() {
        val m = matcher(FilterSettings(customWords = setOf("Fiddlesticks", "shut up"), allowedWords = setOf("ass")))
        assertTrue(m.containsProfanity("oh fiddlesticks"))
        assertTrue(m.containsProfanity("just Shut  up already"))
        assertFalse(m.containsProfanity("the ass carried the load"))
    }

    @Test fun singleWordsCanBeSwitchedOnOrOff() {
        val allowHell = matcher(FilterSettings(language = Strictness.HIGH, wordChoices = mapOf("hell" to false)))
        assertFalse(allowHell.containsProfanity("what the hell"))
        assertTrue(allowHell.containsProfanity("damn it"))
        val muteDamnOnly = matcher(FilterSettings(language = Strictness.LOW, blasphemy = false, wordChoices = mapOf("damn" to true)))
        assertTrue(muteDamnOnly.containsProfanity("damn it"))
        assertFalse(muteDamnOnly.containsProfanity("oh crap"))
        val keepOmg = matcher(FilterSettings(wordChoices = mapOf("omg" to false)))
        assertFalse(keepOmg.containsProfanity("Oh my God, look"))
        assertTrue(keepOmg.containsProfanity("Jesus Christ, look"))
        // Switching the whole language filter off still mutes nothing.
        assertFalse(matcher(FilterSettings(language = Strictness.OFF, wordChoices = mapOf("damn" to true))).containsProfanity("damn"))
    }

    @Test fun everyBuiltInWordHasItsOwnIdAndAHiddenLetter() {
        assertEquals(WordList.groups.size, WordList.groups.map { it.id }.toSet().size)
        assertTrue(WordList.groups.all { it.label.contains('*') && it.patterns.isNotEmpty() })
    }

    @Test fun alreadyCensoredCaptionsStillCount() {
        assertTrue(matcher().containsProfanity("what the [ __ ] is that"))
        assertTrue(matcher().containsProfanity("oh f*** off"))
        assertFalse(matcher(FilterSettings(language = Strictness.LOW)).containsProfanity("what the [ __ ]"))
    }
}

class SubtitleTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i> there\r\nfriend\r\n\r\n" +
        "2\r\n00:01:02,250 --> 00:01:04,000\r\n{\\an8}Second line\r\n"

    @Test fun parsesSrt() {
        val cues = SubtitleParser.parse(srt)
        assertEquals(listOf(Cue(1000, 3500, "Hello there friend"), Cue(62250, 64000, "Second line")), cues)
    }

    @Test fun parsesWebVttWithShortTimestamps() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:05.000 --> 00:07.5\nHi\n\n01:00:00.000 --> 01:00:01.000\nLate")
        assertEquals(listOf(Cue(5000, 7500, "Hi"), Cue(3600000, 3601000, "Late")), cues)
    }

    @Test fun shortCueIsMutedWhole() {
        val tags = CueTagger.tagsFor(Cue(10000, 11000, "Oh shit"), ProfanityMatcher(FilterSettings()))
        assertEquals(listOf(Tag(9850, 11150, Category.LANGUAGE, Action.MUTE, 2, Tag.SOURCE_CAPTIONS)), tags)
    }

    @Test fun longCueMutesOnlyAroundTheWord() {
        // 40 characters over 4 seconds; the word sits at characters 30..34.
        val text = "aaaaaaaaa bbbbbbbbb ccccccccc shit ddddd"
        val tag = CueTagger.tagsFor(Cue(10000, 14000, text), ProfanityMatcher(FilterSettings())).single()
        assertEquals(10000 + 3000 - CueTagger.PAD_BEFORE_MS, tag.startMs)
        assertEquals(10000 + 3400 + CueTagger.PAD_AFTER_MS, tag.endMs)
    }

    @Test fun cleanCueGivesNoTags() {
        assertTrue(CueTagger.tagsFor(Cue(0, 5000, "A perfectly nice day"), ProfanityMatcher(FilterSettings())).isEmpty())
    }

    @Test fun overlappingMutesMerge() {
        val merged = Ranges.merge(listOf(
            Tag(0, 1000, Category.LANGUAGE, Action.MUTE, 1), Tag(900, 2000, Category.LANGUAGE, Action.MUTE, 3),
            Tag(5000, 6000, Category.LANGUAGE, Action.MUTE, 2), Tag(500, 700, Category.NUDITY, Action.BLUR, 2),
        ))
        assertEquals(listOf(
            Tag(0, 2000, Category.LANGUAGE, Action.MUTE, 3), Tag(500, 700, Category.NUDITY, Action.BLUR, 2),
            Tag(5000, 6000, Category.LANGUAGE, Action.MUTE, 2),
        ), merged)
    }

    @Test fun scanSamplesBecomePaddedRanges() {
        val samples = listOf(0L to 0, 1000L to 2, 2000L to 3, 3000L to 0, 4000L to 2, 9000L to 1, 10000L to 0)
        val tags = Ranges.fromSamples(samples, 1000, Category.NUDITY, Action.BLUR)
        assertEquals(listOf(
            Tag(0, 5000, Category.NUDITY, Action.BLUR, 3, Tag.SOURCE_SCAN),
            Tag(8000, 10000, Category.NUDITY, Action.BLUR, 1, Tag.SOURCE_SCAN),
        ), tags)
    }
}

class FilterEngineTest {
    private val tags = listOf(
        Tag(1000, 2000, Category.LANGUAGE, Action.MUTE, 2),
        Tag(5000, 8000, Category.NUDITY, Action.SKIP, 3),
        Tag(7500, 9000, Category.NUDITY, Action.SKIP, 3),
        Tag(12000, 13000, Category.NUDITY, Action.BLUR, 2),
        Tag(20000, 21000, Category.NUDITY, Action.BLUR, 1, Tag.SOURCE_SCAN),
    )

    @Test fun mutesBlursAndClears() {
        val e = FilterEngine(tags, FilterSettings())
        assertEquals(PlaybackState.CLEAR, e.stateAt(500))
        assertEquals(PlaybackState(mute = true, blur = false, skipToMs = null), e.stateAt(1500))
        assertEquals(PlaybackState.CLEAR, e.stateAt(2000))
        assertEquals(PlaybackState(mute = false, blur = true, skipToMs = null), e.stateAt(12500))
    }

    @Test fun skipLandsPastOverlappingRanges() {
        val e = FilterEngine(tags, FilterSettings())
        assertEquals(9000L, e.stateAt(5000).skipToMs)
        assertEquals(9000L, e.stateAt(8500).skipToMs)
        assertEquals(null, e.stateAt(9000).skipToMs)
    }

    @Test fun settingsDecideWhatIsActive() {
        assertEquals(PlaybackState.CLEAR, FilterEngine(tags, FilterSettings(language = Strictness.LOW)).stateAt(1500))
        assertEquals(PlaybackState.CLEAR, FilterEngine(tags, FilterSettings(nudity = Strictness.OFF)).stateAt(6000))
        assertEquals(PlaybackState.CLEAR, FilterEngine(tags, FilterSettings()).stateAt(20500))
        assertTrue(FilterEngine(tags, FilterSettings(nudity = Strictness.HIGH)).stateAt(20500).blur)
    }

    @Test fun scannedNudityFollowsTheBlurOrSkipSetting() {
        val e = FilterEngine(tags, FilterSettings(nudity = Strictness.HIGH, nudityAction = Action.SKIP))
        assertEquals(21000L, e.stateAt(20500).skipToMs)
        assertTrue(e.stateAt(12500).blur) // marked by hand as blur, stays blur
    }
}

class DetectionTest {
    /** Builds a model output with the given boxes: (anchor, classId, score, cx, cy, w, h). */
    private fun output(numClasses: Int, numAnchors: Int, vararg boxes: FloatArray): FloatArray {
        val out = FloatArray((4 + numClasses) * numAnchors)
        for (b in boxes) {
            val i = b[0].toInt()
            out[i] = b[3]; out[numAnchors + i] = b[4]; out[2 * numAnchors + i] = b[5]; out[3 * numAnchors + i] = b[6]
            out[(4 + b[1].toInt()) * numAnchors + i] = b[2]
        }
        return out
    }

    @Test fun decodesBoxesAndDropsDuplicates() {
        val out = output(18, 10,
            floatArrayOf(0f, 3f, 0.9f, 100f, 100f, 40f, 60f),
            floatArrayOf(1f, 3f, 0.6f, 102f, 101f, 40f, 60f), // same thing, weaker
            floatArrayOf(2f, 1f, 0.8f, 250f, 50f, 30f, 30f),
            floatArrayOf(3f, 4f, 0.1f, 20f, 20f, 10f, 10f),   // under the threshold
        )
        val found = Yolo.decode(out, 18, 10, 0.3f)
        assertEquals(2, found.size)
        assertEquals(Detection(3, 0.9f, 80f, 70f, 120f, 130f), found[0])
        assertEquals(1, found[1].classId)
    }

    @Test fun labelsMapToLevels() {
        assertEquals(18, NudeLabels.names.size)
        assertEquals(3, NudeLabels.levelFor(NudeLabels.names.indexOf("MALE_GENITALIA_EXPOSED")))
        assertEquals(2, NudeLabels.levelFor(NudeLabels.names.indexOf("FEMALE_BREAST_EXPOSED")))
        assertEquals(1, NudeLabels.levelFor(NudeLabels.names.indexOf("FEMALE_BREAST_COVERED")))
        assertEquals(0, NudeLabels.levelFor(NudeLabels.names.indexOf("FACE_FEMALE")))
        assertEquals(0, NudeLabels.levelFor(99))
        assertEquals(0, NudeLabels.maxLevel(emptyList()))
        val face = listOf(Detection(NudeLabels.names.indexOf("FACE_MALE"), 0.9f, 0f, 0f, 1f, 1f))
        assertEquals(0, NudeLabels.maxLevel(face))
        assertEquals(3, NudeLabels.maxLevel(face, facesCount = true))
    }
}

class MediaKeyTest {
    @Test fun sameVideoDifferentLinksShareAKey() {
        val a = MediaKey.forUrl("https://www.youtube.com/watch?v=abc123&t=42s&utm_source=x")
        assertEquals("web:youtube.com/watch?v=abc123", a)
        assertEquals(a, MediaKey.forUrl("https://m.youtube.com/watch?v=abc123"))
        assertEquals(a, MediaKey.forUrl("https://youtu.be/abc123?si=zzz"))
        assertEquals("web:example.com/video/9", MediaKey.forUrl("https://Example.com/video/9/#top"))
        assertEquals("web:netflix.com/watch/8123", MediaKey.forUrl("https://www.netflix.com/watch/8123?trackId=14170286&tctx=1%2C2"))
        assertEquals("web:example.com/play?id=7", MediaKey.forUrl("https://example.com/play?ref=home&id=7&autoplay=1"))
    }

    @Test fun fileNamesAreSafe() {
        val name = MediaKey.fileName(MediaKey.forFile("My Movie (2020).mp4", 123L))
        assertEquals(40, name.length)
        assertTrue(name.all { it.isLetterOrDigit() })
    }
}
