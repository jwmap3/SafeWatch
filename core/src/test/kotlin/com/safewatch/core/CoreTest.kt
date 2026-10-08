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
        // Whatever the caption writer blanked out is muted at every setting but Off.
        assertTrue(matcher(FilterSettings(language = Strictness.LOW)).containsProfanity("what the [ __ ]"))
        assertFalse(matcher(FilterSettings(language = Strictness.OFF)).containsProfanity("what the [ __ ]"))
    }

    @Test fun everyUsualWayOfBlankingAWordCounts() {
        for (line in listOf("oh sh*t", "you b*tch", "f**k that", "what the f___", "s__t happens", "[bleep] off", "(BLEEPING) car",
            "you [expletive] idiot", "what the @#\$%!", "that is ****", "[\u00a0__\u00a0] this")) {
            assertTrue(matcher().containsProfanity(line))
        }
    }

    @Test fun ordinaryPunctuationIsNotTakenForABlankedWord() {
        for (line in listOf("I--I don't know", "wait--what?", "No!!!!", "5 * 3 * 2", "a_b_c", "snake_case_name", "*laughs*", "[music]",
            "(sighs)", "Rated 4**", "e-mail me @home", "100% sure!!")) {
            assertFalse(matcher().containsProfanity(line))
        }
    }

    @Test fun commonSpellingsAndShorthandAreCaught() {
        for (line in listOf("wtf is that", "effing hell", "stfu", "what a biatch", "bull shite")) assertTrue(matcher().containsProfanity(line))
        // Sincere uses are left alone.
        for (line in listOf("Thank God you're safe", "Dear God, we pray", "Jesus said to them", "she began to sob")) {
            assertFalse(matcher().containsProfanity(line))
        }
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
        assertEquals(listOf(Tag(10000 - CueTagger.EDGE_MS, 11000 + CueTagger.EDGE_MS, Category.LANGUAGE, Action.MUTE, 2, Tag.SOURCE_CAPTIONS)), tags)
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

class CaptionFormatsTest {
    @Test fun readsTtmlWithClockAndTickTimes() {
        val ttml = """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml" ttp:tickRate="10000000"><body><div>
            <p begin="00:00:01.500" end="00:00:03.000">Hello <span>there</span><br/>friend</p>
            <p xml:id="s2" begin="50000000t" end="62500000t">Tick &amp; tock</p>
            <p begin="12.5s" dur="2s">Offset time</p>
            </div></body></tt>"""
        assertTrue(CaptionFormats.recognises(ttml))
        assertEquals(listOf(Cue(1500, 3000, "Hello there friend"), Cue(5000, 6250, "Tick & tock"), Cue(12500, 14500, "Offset time")),
            CaptionFormats.parse(ttml))
    }

    @Test fun readsYouTubeWordTimes() {
        val json = """{"wireMagic":"pb3","events":[{"tStartMs":0,"dDurationMs":500,"id":1},
            {"tStartMs":1200,"dDurationMs":3000,"segs":[{"utf8":"so"},{"utf8":" what","tOffsetMs":320},{"utf8":" now","tOffsetMs":900}]},
            {"tStartMs":5000,"dDurationMs":2000,"segs":[{"utf8":"A whole line\nhere"}]}]}"""
        assertTrue(CaptionFormats.recognises(json))
        assertEquals(listOf(Cue(1200, 1520, "so"), Cue(1520, 2100, "what"), Cue(2100, 3000, "now"), Cue(5000, 7000, "A whole line here")),
            CaptionFormats.parse(json))
    }

    @Test fun readsYouTubeXmlAndPlainSubtitles() {
        assertEquals(listOf(Cue(1200, 4200, "so what")),
            CaptionFormats.parse("""<?xml version="1.0"?><timedtext format="3"><body><p t="1200" d="3000">so <s t="320">what</s></p></body></timedtext>"""))
        val vtt = "WEBVTT\n\n00:05.000 --> 00:07.500\nHi"
        assertTrue(CaptionFormats.recognises(vtt))
        assertEquals(listOf(Cue(5000, 7500, "Hi")), CaptionFormats.parse(vtt))
        assertFalse(CaptionFormats.recognises("{\"status\":\"ok\"}"))
    }

    @Test fun aSwearWordTimedAloneIsMutedAlone() {
        val json = """{"events":[{"tStartMs":10000,"dDurationMs":4000,"segs":[{"utf8":"well"},{"utf8":" shit","tOffsetMs":1500},{"utf8":" happens","tOffsetMs":2100}]}]}"""
        val tag = CueTagger.tagsFor(CaptionFormats.parse(json), ProfanityMatcher(FilterSettings())).single()
        assertEquals(11500 - CueTagger.EDGE_MS, tag.startMs)
        assertEquals(12100 + CueTagger.EDGE_MS, tag.endMs)
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

class LookAheadTest {
    private fun looked(vararg samples: Pair<Long, Int>) = LookAhead(reachMs = 2000).apply { samples.forEach { add(it.first, it.second) } }

    @Test fun hidesBeforeTheSceneAndForAllOfIt() {
        // Looked at every half second from 0 to 20 s; nudity found from 8 s to 12 s.
        val ahead = LookAhead(reachMs = 2000)
        for (t in 0L..20000L step 500) ahead.add(t, if (t in 8000..12000) 3 else 0)
        assertEquals(0, ahead.levelAt(5500))
        assertEquals(3, ahead.levelAt(6100))  // two seconds before it starts
        assertEquals(3, ahead.levelAt(10000))
        assertEquals(3, ahead.levelAt(13900)) // two seconds after it ends
        assertEquals(0, ahead.levelAt(14600))
        assertEquals(listOf(6000L..14000L), ahead.stretches(1))
    }

    @Test fun aMissedPictureInTheMiddleDoesNotLiftTheBlur() {
        // A scene from 8 s to 14 s in which the detector missed the pictures around 11 s.
        val ahead = LookAhead(reachMs = 2000)
        for (t in 0L..20000L step 500) ahead.add(t, if (t in 8000..14000 && t !in 10500..11500) 2 else 0)
        for (t in 6100L..15900L step 100) assertTrue(ahead.levelAt(t) > 0)
        assertEquals(0, ahead.levelAt(16600))
    }

    @Test fun slowLookingHidesEverythingBetweenAFlaggedPictureAndItsNeighbours() {
        val ahead = looked(0L to 0, 3000L to 2, 6000L to 0, 9000L to 0)
        assertTrue(ahead.levelAt(500) > 0)
        assertTrue(ahead.levelAt(5900) > 0)
        assertEquals(0, ahead.levelAt(6500))
    }

    @Test fun knowsWhatItHasNotLookedAt() {
        val ahead = looked(0L to 0, 1000L to 0, 2000L to 0, 9000L to 0, 10000L to 0)
        assertTrue(ahead.covers(1500))
        assertFalse(ahead.covers(5000)) // a seven-second hole
        assertFalse(ahead.covers(10500)) // past the last look
        assertTrue(ahead.covers(9500))
        assertEquals(1500, ahead.knownAheadOf(500))
        assertEquals(0, ahead.knownAheadOf(20000))
        ahead.clear()
        assertFalse(ahead.covers(1500))
    }

    @Test fun aSecondLookKeepsTheWorseFinding() {
        val ahead = looked(4000L to 2, 4020L to 0)
        assertEquals(2, ahead.levelAt(4000))
        assertEquals(1, ahead.size)
    }
}

class CleanPlanTest {
    private fun tag(s: Long, e: Long, c: Category, a: Action, level: Int = 3, source: String = Tag.SOURCE_MANUAL) = Tag(s, e, c, a, level, source)

    @Test fun mutesBlursAndCutsWhatTheSettingsFilter() {
        val tags = listOf(
            tag(1000, 2000, Category.LANGUAGE, Action.MUTE), tag(1500, 2500, Category.LANGUAGE, Action.MUTE),
            tag(10_000, 20_000, Category.NUDITY, Action.BLUR, source = Tag.SOURCE_SCAN),
            tag(30_000, 40_000, Category.NUDITY, Action.SKIP), tag(35_000, 36_000, Category.LANGUAGE, Action.MUTE),
            tag(50_000, 51_000, Category.LANGUAGE, Action.MUTE, level = 1), // mild: left alone at Medium
        )
        val plan = CleanPlanner.plan(60_000, tags, FilterSettings())
        assertEquals(listOf(1000L..2500L), plan.mute)
        assertEquals(listOf(10_000L..20_000L), plan.blur)
        assertEquals(listOf(0L..30_000L, 40_000L..60_000L), plan.keep)
        assertTrue(plan.mutedAt(2000))
        assertFalse(plan.mutedAt(2600))
        assertTrue(plan.blurredAt(15_000))
        assertFalse(plan.blurredAt(25_000))
        assertEquals("1 word muted, 1 scene blurred, 1 scene cut (10 s)", plan.summary())
    }

    @Test fun scannedNudityIsCutWhenTheViewerChoseSkip() {
        val tags = listOf(tag(0, 5000, Category.NUDITY, Action.BLUR, source = Tag.SOURCE_SCAN))
        val plan = CleanPlanner.plan(20_000, tags, FilterSettings(nudityAction = Action.SKIP))
        assertEquals(listOf(5000L..20_000L), plan.keep)
        assertTrue(plan.blur.isEmpty())
    }

    @Test fun aCleanVideoIsKeptWhole() {
        val plan = CleanPlanner.plan(20_000, emptyList(), FilterSettings())
        assertEquals(listOf(0L..20_000L), plan.keep)
        assertEquals("Nothing needed filtering", plan.summary())
    }
}

class TvLinkTest {
    @Test fun readsDiscoveryAnswers() {
        val answer = "HTTP/1.1 200 OK\r\nCache-Control: max-age=3600\r\nST: roku:ecp\r\nLocation: http://192.168.1.40:8060/\r\nUSN: uuid:roku:ecp:X\r\n\r\n"
        val h = com.safewatch.core.tv.Ssdp.headers(answer)
        assertEquals("http://192.168.1.40:8060/", h["LOCATION"])
        assertTrue(com.safewatch.core.tv.Ssdp.isRoku(h))
        assertFalse(com.safewatch.core.tv.Ssdp.isRoku(mapOf("ST" to "urn:schemas-upnp-org:device:MediaRenderer:1", "SERVER" to "Samsung UPnP")))
        assertTrue(com.safewatch.core.tv.Ssdp.question("roku:ecp").startsWith("M-SEARCH * HTTP/1.1\r\n"))
    }

    @Test fun findsASmartTvsPlaybackControls() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>[TV] Living Room</friendlyName>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service></serviceList></device></root>"""
        val tv = com.safewatch.core.tv.Dlna.fromDescription(xml, "http://192.168.1.50:9197/dmr")!!
        assertEquals("[TV] Living Room", tv.name)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", tv.controlUrl)
        assertEquals("192.168.1.50", tv.host)
        assertEquals(null, com.safewatch.core.tv.Dlna.fromDescription("<root><device><friendlyName>Speaker</friendlyName></device></root>", "http://x/"))
    }

    @Test fun asksARokuToPlayTheFile() {
        val roku = com.safewatch.core.tv.TvDevice(com.safewatch.core.tv.TvDevice.Kind.ROKU, "Roku", "http://192.168.1.40:8060/")
        val request = com.safewatch.core.tv.Roku.playRequest(roku, "http://192.168.1.9:4000/abc/video.mp4", "My film")
        assertEquals("http://192.168.1.40:8060/input/15985?t=v&u=http%3A%2F%2F192.168.1.9%3A4000%2Fabc%2Fvideo.mp4&k=(null)&videoName=My+film&videoFormat=mp4", request)
    }

    @Test fun tellsASmartTvWhatToPlayInOrder() {
        // A stand-in TV that records what it is told.
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = java.net.ServerSocket(0)
        Thread {
            repeat(3) {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var length = 0
                    var action = ""
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("SOAPAction", ignoreCase = true)) action = line.substringAfter('#').trim('"', ' ')
                        if (line.startsWith("Content-Length", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var got = 0
                    while (got < length) { val n = input.read(body, got, length - got); if (n < 0) break; got += n }
                    heard += action + "|" + String(body)
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }.start()
        val tv = com.safewatch.core.tv.TvDevice(com.safewatch.core.tv.TvDevice.Kind.DLNA, "TV", "http://127.0.0.1:${server.localPort}/d", "http://127.0.0.1:${server.localPort}/ctl")
        com.safewatch.core.tv.Dlna.play(tv, "http://192.168.1.9:4000/abc/video.mp4", "Film & more")
        server.close()
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), heard.map { it.substringBefore('|') })
        val set = heard[1]
        assertTrue(set.contains("<CurrentURI>http://192.168.1.9:4000/abc/video.mp4</CurrentURI>"))
        assertTrue(set.contains("&lt;dc:title&gt;Film &amp;amp; more&lt;/dc:title&gt;")) // the metadata travels as escaped text
        assertTrue(set.contains("http-get:*:video/mp4:DLNA.ORG_OP=01"))
    }

    @Test fun servesTheFileWholeAndInPieces() {
        val file = java.io.File.createTempFile("clean", ".mp4").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }); deleteOnExit() }
        val server = com.safewatch.core.tv.FileServer(file).start(java.net.InetAddress.getLoopbackAddress())
        try {
            val url = server.url("127.0.0.1")
            val whole = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            assertEquals(200, whole.responseCode)
            assertEquals("bytes", whole.getHeaderField("Accept-Ranges"))
            assertEquals("Streaming", whole.getHeaderField("transferMode.dlna.org"))
            assertEquals(100_000, whole.inputStream.readBytes().size)
            val piece = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            piece.setRequestProperty("Range", "bytes=1000-1999")
            assertEquals(206, piece.responseCode)
            assertEquals("bytes 1000-1999/100000", piece.getHeaderField("Content-Range"))
            val bytes = piece.inputStream.readBytes()
            assertEquals(1000, bytes.size)
            assertEquals((1000 % 251).toByte(), bytes[0])
            val end = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            end.setRequestProperty("Range", "bytes=-500")
            assertEquals(500, end.inputStream.readBytes().size)
            val other = java.net.URL("http://127.0.0.1:${server.port}/video.mp4").openConnection() as java.net.HttpURLConnection
            assertEquals(404, other.responseCode) // nothing but the file's own secret address is answered
            assertTrue(server.requests >= 4)
        } finally {
            server.stop()
        }
    }
}

/**
 * Android's regular expressions are not Java's: they reject a "}" or "]" that is not escaped,
 * where Java takes it as a plain character. A pattern that compiles in these tests can still
 * fail on the phone, as one did, silently stopping all muting from caption files. This checks
 * every pattern in the app's source is written the way both accept.
 */
class AndroidRegexTest {
    private fun problem(p: String): String? {
        var i = 0
        var inSet = false
        while (i < p.length) {
            val c = p[i]
            if (c == '\\') {
                // A property such as \p{L} is one item, braces and all.
                if (i + 2 < p.length && (p[i + 1] == 'p' || p[i + 1] == 'P') && p[i + 2] == '{') {
                    i = p.indexOf('}', i) + 1
                    continue
                }
                i += 2
                continue
            }
            if (inSet) {
                when (c) {
                    ']' -> inSet = false
                    '[' -> return "an unescaped [ inside a set"
                    '{', '}' -> return "an unescaped brace inside a set"
                }
            } else {
                when (c) {
                    '[' -> inSet = true
                    ']' -> return "an unescaped ]"
                    '}' -> return "an unescaped }"
                    '{' -> {
                        val q = Regex("\\{\\d+(,\\d*)?\\}").find(p, i)?.takeIf { it.range.first == i } ?: return "an unescaped {"
                        i += q.value.length
                        continue
                    }
                }
            }
            i++
        }
        return null
    }

    private fun unescape(literal: String): String {
        val text = literal.replace(Regex("(?<!\\\\)\\$\\{[^\\}]*\\}|(?<!\\\\)\\$[A-Za-z_]\\w*"), "x")
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                when (n) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> { out.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    @Test fun everyPatternIsWrittenTheWayAndroidAccepts() {
        var dir = java.io.File("").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile ?: return
        val literal = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val problems = ArrayList<String>()
        var checked = 0
        for (root in listOf("core/src/main", "app/src/main/java")) {
            for (file in java.io.File(dir, root).walk().filter { it.extension == "kt" }) {
                file.readLines().forEachIndexed { n, line ->
                    for (m in literal.findAll(line)) {
                        checked++
                        val pattern = unescape(m.groupValues[1])
                        problem(pattern)?.let { problems += "${file.name}:${n + 1}: $it in $pattern" }
                    }
                }
            }
        }
        assertTrue(checked > 30)
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun theCheckCatchesWhatAndroidRejects() {
        assertEquals("an unescaped }", problem("<[^>]*>|\\{[^x]*}"))
        assertEquals("an unescaped brace inside a set", problem("\\{[^{}]*\\}"))
        assertEquals("an unescaped ]", problem("\\[ _+ ]"))
        assertEquals(null, problem("\\d{3}\\s*-->[\\[(]x[\\])]"))
    }
}

class StreamInfoTest {
    private val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aac"
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aac"
high/index.m3u8
"""
    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\na.ts\n#EXTINF:6.0,\nb.ts\n#EXT-X-ENDLIST\n"

    @Test fun readsAnHlsMasterPlaylist() {
        assertEquals(StreamInfo.Kind.HLS, StreamInfo.kindOf(master))
        assertTrue(StreamInfo.hlsIsMaster(master))
        assertEquals(listOf("high/index.m3u8", "low/index.m3u8"), StreamInfo.hlsVariants(master))
        assertEquals(listOf("audio/en.m3u8"), StreamInfo.hlsRenditions(master, "AUDIO"))
        assertEquals(null, StreamInfo.refusal(master, listOf(vod)))
    }

    @Test fun refusesEncryptedAndLiveStreams() {
        val locked = vod.replace("#EXTINF:6.0,\na.ts", "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6.0,\na.ts")
        assertEquals(StreamInfo.LOCKED, StreamInfo.refusal(locked))
        assertEquals(StreamInfo.LOCKED, StreamInfo.refusal(master, listOf(locked)))
        assertEquals(StreamInfo.LOCKED, StreamInfo.refusal("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n"))
        assertEquals(null, StreamInfo.refusal(vod.replace("#EXTINF:6.0,\na.ts", "#EXT-X-KEY:METHOD=NONE\n#EXTINF:6.0,\na.ts")))
        assertEquals(StreamInfo.LIVE, StreamInfo.refusal(vod.replace("#EXT-X-ENDLIST\n", "")))
        assertEquals(StreamInfo.LIVE, StreamInfo.refusal(master, listOf(vod.replace("#EXT-X-ENDLIST\n", ""))))
    }

    @Test fun readsDashManifests() {
        val clear = """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10M"><Period/></MPD>"""
        assertEquals(StreamInfo.Kind.DASH, StreamInfo.kindOf(clear))
        assertEquals(null, StreamInfo.refusal(clear))
        val drm = clear.replace("<Period/>", "<Period><AdaptationSet><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/></AdaptationSet></Period>")
        assertEquals(StreamInfo.LOCKED, StreamInfo.refusal(drm))
        assertEquals(StreamInfo.LIVE, StreamInfo.refusal(clear.replace("static", "dynamic")))
        assertEquals(null, StreamInfo.kindOf("<html>not a manifest</html>"))
    }
}

class YouTubeLoungeTest {
    @Test fun readsTheTvsMessages() {
        val json = com.safewatch.core.tv.MiniJson.parse("""{"a":[1,2.5,-3e2],"b":"x\"yé\n","c":true,"d":null,"e":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), json["a"])
        assertEquals("x\"yé\n", json["b"])
        assertEquals(true, json["c"])
        assertTrue(json.containsKey("d") && json["d"] == null)
        // Chunks: a length line, then the JSON, which may run over several lines.
        val chunk1 = """[[0,["c","SID1","",8]
],[1,["S","GS1"]]]"""
        val chunk2 = """[[2,["onStateChange",{"currentTime":"12.5","state":"1"}]]]"""
        val text = "${chunk1.length}\n$chunk1\n${chunk2.length}\n$chunk2\n"
        val got = ArrayList<com.safewatch.core.tv.LoungeMessages.Event>()
        com.safewatch.core.tv.LoungeMessages.read(java.io.BufferedReader(java.io.StringReader(text))) { got += it }
        assertEquals(listOf("c", "S", "onStateChange"), got.map { it.name })
        assertEquals("SID1", got[0].args[0])
        assertEquals("12.5", got[2].data["currentTime"])
        assertEquals(2L, got[2].id)
    }

    @Test fun muteTimesMergeAndComeEarly() {
        val t = com.safewatch.core.tv.TvMuteTimeline(listOf(1000L..2000L, 1800L..2600L, 5000L..5500L), leadMs = 400)
        assertEquals(2, t.count)
        assertFalse(t.mutedAt(500))
        assertTrue(t.mutedAt(650))   // 400 ms early
        assertTrue(t.mutedAt(2500))
        assertFalse(t.mutedAt(2700))
        assertEquals(1000, t.untilChange(3000, 1000))
        assertTrue(t.untilChange(4500, 1000) in 100..150)
    }

    @Test fun followsTheTvBetweenItsReports() {
        var now = 10_000L
        val f = com.safewatch.core.tv.TvYouTubeFollower("abc", listOf(5000L..6000L), listOf(9000L..12000L)) { now }
        fun event(name: String, vararg data: Pair<String, String>) = f.take(com.safewatch.core.tv.LoungeMessages.Event(1, name, listOf(mapOf(*data))))
        event("nowPlaying", "videoId" to "abc", "currentTime" to "4", "state" to "1")
        assertEquals(4000, f.positionMs())
        now += 500
        assertEquals(4500, f.positionMs())
        assertTrue(f.shouldMute()) // the stretch at 5 s is within the lead
        event("onStateChange", "currentTime" to "4.6", "state" to "2")
        now += 10_000
        assertEquals(4600, f.positionMs()) // paused: the clock does not move it
        event("onStateChange", "currentTime" to "8.5", "state" to "1")
        assertFalse(f.shouldMute())
        assertEquals(12000L, f.skipTo())
        event("nowPlaying", "videoId" to "zzz", "currentTime" to "1", "state" to "1")
        assertEquals("zzz", f.otherVideo)
        assertFalse(f.shouldMute())
    }

    @Test fun linksATvAndKeepsItsVideoClean() {
        val tv = FakeLounge().apply { start() }
        try {
            val client = com.safewatch.core.tv.LoungeClient(tv.base)
            val wrong = runCatching { client.pair("999 999 999 999") }.exceptionOrNull() as com.safewatch.core.tv.LoungeException
            assertEquals(404, wrong.status)
            val screen = client.pair("123 456 789 012")
            assertEquals("Living Room TV", screen.name)
            assertEquals("tok1", screen.token)

            tv.token = "tok2" // the link has run out since: the remote must renew it by itself
            var renewed: com.safewatch.core.tv.LoungeScreen? = null
            val follower = com.safewatch.core.tv.TvYouTubeFollower("abc123", listOf(1500L..2500L), listOf(4000L..6000L))
            val said = java.util.Collections.synchronizedList(ArrayList<String>())
            val remote = com.safewatch.core.tv.TvYouTubeRemote(client, screen, follower, startAtMs = 0, hideCaptions = true,
                listener = object : com.safewatch.core.tv.TvYouTubeListener {
                    override fun status(text: String) { said += text }
                    override fun finished(reason: String) { said += "finished: $reason" }
                }, onNewToken = { renewed = it })
            val runner = Thread { remote.run() }.apply { start() }
            Thread.sleep(5200)
            remote.stop()
            runner.join(3000)
            assertEquals("tok2", renewed?.token)
            val names = tv.commands.map { it.second["req0__sc"] }
            assertEquals("setAutoplayMode", names[0])
            assertEquals("setPlaylist", names[1])
            assertEquals("abc123", tv.commands[1].second["req0_videoId"])
            assertTrue("setSubtitlesTrack" in names)
            fun at(name: String, muted: String? = null) = tv.commands.first { it.second["req0__sc"] == name && (muted == null || it.second["req0_muted"] == muted) }.first - tv.startedAt
            // The mute goes out ahead of 1.5 s, the sound comes back near 2.5 s, and the jump past 4 s goes out ahead of it.
            assertTrue(at("setVolume", "true") in 600..1200, "mute at ${at("setVolume", "true")}")
            assertTrue(at("setVolume", "false") in 2100..2600, "unmute at ${at("setVolume", "false")}")
            assertEquals("40", tv.commands.first { it.second["req0__sc"] == "setVolume" }.second["req0_volume"])
            assertTrue(at("seekTo") in 3200..3800, "jump at ${at("seekTo")}")
            assertEquals("6.0", tv.commands.first { it.second["req0__sc"] == "seekTo" }.second["req0_newTime"])
            assertTrue(said.any { it.startsWith("Filtering on Living Room TV") }, said.toString())
            assertTrue(tv.terminated)
        } finally {
            tv.stop()
        }
    }
}

/** A stand-in for YouTube's TV remote service and a TV on it, enough to test against. */
class FakeLounge {
    private val server = java.net.ServerSocket(0)
    val base get() = "http://127.0.0.1:${server.localPort}/api/lounge"
    val commands = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Map<String, String>>>())
    @Volatile var token = "tok1"
    @Volatile var playingAt = 0L
    /** When the video first started playing, in the stand-in's own milliseconds. */
    @Volatile var startedAt = 0L
    @Volatile var terminated = false
    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()
    private var nextId = 10
    private val start = System.nanoTime()
    private fun now() = (System.nanoTime() - start) / 1_000_000

    fun start() {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread { try { handle(s) } catch (e: Exception) { /* the client went away */ } }.start()
            }
        }.start()
    }

    fun stop() = server.close()

    private fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun obj(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { str(it.first) + ":" + str(it.second) }

    @Synchronized private fun chunk(vararg events: String): String {
        val json = events.joinToString(",", "[", "]") { "[${nextId++},$it]" }
        return "${json.length}\n$json\n"
    }

    private fun event(name: String, vararg kv: Pair<String, String>) = queue.put(chunk("[${str(name)},${obj(*kv)}]"))

    private fun parse(text: String): Map<String, String> = text.split('&').filter { it.contains('=') }.associate {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun position(): String = String.format(java.util.Locale.US, "%.3f", (now() - playingAt) / 1000.0)

    private fun handle(s: java.net.Socket) = s.use {
        val input = java.io.BufferedInputStream(s.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; head.append(b.toChar()) }
        val (method, target) = head.lines().first().split(" ").let { it[0] to it[1] }
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val form = parse(String(input.readNBytes(length)))
        val path = target.substringBefore('?')
        val query = parse(target.substringAfter('?', ""))
        val out = s.getOutputStream()
        fun reply(status: String, text: String) =
            out.write("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${text.toByteArray().size}\r\nConnection: close\r\n\r\n$text".toByteArray())
        when {
            path.endsWith("/pairing/get_screen") ->
                if (form["pairing_code"] == "123456789012") reply("200 OK", """{"screen":{"screenId":"scr1","loungeToken":"tok1","name":"Living Room TV"}}""")
                else reply("404 Not Found", "")
            path.endsWith("/pairing/get_lounge_token_batch") -> reply("200 OK", """{"screens":[{"screenId":"scr1","loungeToken":"$token"}]}""")
            path.endsWith("/pairing/get_screen_availability") -> reply("200 OK", """{"screens":[{"status":"online"}]}""")
            method == "POST" && query["RID"] == "1" -> {
                if (form["loungeIdToken"] != token) { reply("401 Unauthorized", "Expired"); return }
                val devices = """[{"type":"LOUNGE_SCREEN","name":"Living Room TV","deviceInfo":"{\"clientName\":\"TVHTML5\"}"}]"""
                reply("200 OK", chunk("""["c","SID1","",8]""", """["S","GS1"]""", "[\"loungeStatus\",${obj("devices" to devices)}]",
                    "[\"onVolumeChanged\",${obj("volume" to "40", "muted" to "false")}]"))
            }
            method == "GET" -> {
                if (query["SID"] != "SID1" || query["gsessionid"] != "GS1") { reply("400 Unknown SID", "Unknown SID"); return }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val until = now() + 2500 // ends the wait now and then, as YouTube does
                while (now() < until) {
                    val next = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    out.write(next.toByteArray())
                    out.flush()
                }
            }
            method == "POST" && form["TYPE"] == "terminate" -> { terminated = true; reply("200 OK", "") }
            method == "POST" -> {
                commands += now() to form
                when (form["req0__sc"]) {
                    "setPlaylist" -> {
                        playingAt = now() + 100
                        startedAt = playingAt
                        Thread {
                            Thread.sleep(100)
                            event("nowPlaying", "videoId" to form["req0_videoId"].orEmpty(), "currentTime" to "0", "state" to "1")
                        }.start()
                    }
                    "setVolume" -> event("onVolumeChanged", "volume" to form["req0_volume"].orEmpty(), "muted" to form["req0_muted"].orEmpty())
                    "seekTo" -> {
                        val to = form["req0_newTime"]!!.toDouble()
                        playingAt = now() - (to * 1000).toLong()
                        event("onStateChange", "currentTime" to form["req0_newTime"]!!, "state" to "1")
                    }
                    "getNowPlaying" -> event("nowPlaying", "videoId" to "abc123", "currentTime" to position(), "state" to "1")
                }
                reply("200 OK", "ok")
            }
            else -> reply("404 Not Found", "")
        }
    }
}
