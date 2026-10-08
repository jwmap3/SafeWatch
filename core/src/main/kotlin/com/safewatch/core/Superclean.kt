package com.safewatch.core

import com.safewatch.core.tv.MiniJson

/**
 * Superclean: a clean copy for the TV that Claude has also been through. Claude looks at the video's
 * pictures and reads its captions, and takes out whatever the family chose, from a list modelled on
 * VidAngel's filter categories, plus specific scenes the family picked from the title's IMDb Parents
 * Guide, which Claude looks up first.
 */
object Superclean {

    /** One thing a family can have taken out. [pictures]: found by looking; [words]: single words muted; [talk]: whole lines muted. */
    data class Choice(
        val id: String,
        val group: String,
        val name: String,
        val detail: String,
        val pictures: Boolean = false,
        val words: Boolean = false,
        val talk: Boolean = false,
        val onByDefault: Boolean = false,
    )

    val GROUPS = listOf("Language", "Sex", "Nudity", "Kissing", "Immodesty", "Violence", "Alcohol & drugs", "Other")

    /** Everything Superclean can take out, grouped as VidAngel groups its filters. */
    val CHOICES = listOf(
        Choice("profanity", "Language", "Profanity", "Curse words and their variants", words = true, onByDefault = true),
        Choice("blasphemy", "Language", "God's name in vain", "God's or Jesus' name used as a curse or exclamation; never sincere prayer or worship", words = true, onByDefault = true),
        Choice("slurs", "Language", "Slurs and bigoted words", "Racist, sexist and other demeaning names for people", words = true, onByDefault = true),
        Choice("sexual_talk", "Language", "Sexual references", "Sex jokes, innuendo, crude talk about bodies or sex", words = true, talk = true, onByDefault = true),
        Choice("crude", "Language", "Crude and potty talk", "Toilet words and gross-out talk", words = true),
        Choice("childish", "Language", "Childish language", "Words you would not want a three-year-old to repeat: stupid, idiot, shut up, butt", words = true),
        Choice("written", "Language", "Profanity written on screen", "Swear words on signs, phones, graffiti or titles", pictures = true, onByDefault = true),
        Choice("suggestive", "Sex", "Sexually suggestive moments", "Sexual undertones, lustful staring, suggestive dancing", pictures = true, onByDefault = true),
        Choice("implied_sex", "Sex", "Implied sex", "Sex just out of sight, the moments before and after, kissing with undressing", pictures = true, onByDefault = true),
        Choice("sex", "Sex", "Sex scenes", "Sex shown, with or without nudity", pictures = true, onByDefault = true),
        Choice("assault", "Sex", "Sexual assault", "Rape, attempted rape or molestation, shown or described", pictures = true, talk = true, onByDefault = true),
        Choice("nudity", "Nudity", "Nudity", "Bare breasts, buttocks or genitals of anyone, however brief or partial", pictures = true, onByDefault = true),
        Choice("implied_nudity", "Nudity", "Implied nudity", "No clothes on, with the private parts out of sight", pictures = true, onByDefault = true),
        Choice("art_nudity", "Nudity", "Nude statues and paintings", "Art that shows nudity", pictures = true),
        Choice("kissing", "Kissing", "Kissing", "Kisses on the lips, for any couple", pictures = true),
        Choice("passionate", "Kissing", "Passionate kissing", "Making out, French kissing, sensual kissing, for any couple", pictures = true, onByDefault = true),
        Choice("immodesty", "Immodesty", "Revealing clothing", "Lingerie and underwear, bikinis, very short or tight clothing, close-ups of chests or behinds", pictures = true, onByDefault = true),
        Choice("violence_talk", "Violence", "Violent descriptions and threats", "Graphic talk of violence or killing, and threats", talk = true),
        Choice("violence", "Violence", "Fighting and violence", "Punching, shooting and stabbing without blood", pictures = true),
        Choice("graphic_violence", "Violence", "Graphic violence", "Violence with blood, wounds or broken bones", pictures = true, onByDefault = true),
        Choice("gore", "Violence", "Gore", "Guts, severed body parts, decapitation, heavy bloodshed", pictures = true, onByDefault = true),
        Choice("disturbing", "Violence", "Disturbing images", "Gruesome injuries, corpses, mass graves, the death of a child", pictures = true, onByDefault = true),
        Choice("animal_harm", "Violence", "Animals being hurt", "Animals hurt or killed", pictures = true),
        Choice("drinking", "Alcohol & drugs", "Drinking", "Alcohol being drunk", pictures = true),
        Choice("smoking", "Alcohol & drugs", "Smoking and vaping", "Cigarettes, cigars and vapes", pictures = true),
        Choice("drugs", "Alcohol & drugs", "Drug use", "Illegal drugs, or medicine misused, being taken", pictures = true, onByDefault = true),
        Choice("drug_talk", "Alcohol & drugs", "Talk about drugs and drinking", "Talk that dwells on or makes light of drugs or getting drunk", talk = true),
        Choice("gestures", "Other", "Vulgar gestures", "The middle finger, crotch-grabbing, mimed sex acts", pictures = true, onByDefault = true),
        Choice("self_harm", "Other", "Self-harm and suicide", "Shown, or talked about in detail", pictures = true, talk = true, onByDefault = true),
        Choice("scary", "Other", "Frightening scenes", "Jump scares, monsters and intense peril that could frighten young children", pictures = true),
        Choice("bodily", "Other", "Bodily functions", "Vomiting, passing gas, toilet humour", pictures = true),
        Choice("medical", "Other", "Graphic medical scenes", "Surgery, needles going in, blood in a hospital", pictures = true),
        Choice("death", "Other", "Death and dying", "Distressing deaths, even without violence", pictures = true),
        Choice("credits", "Other", "Credits and recaps", "Opening and closing credits, and 'previously on' recaps", pictures = true),
    )

    fun choice(id: String): Choice? = CHOICES.firstOrNull { it.id == id }

    /** Ready-made sets: everything for young children, the usual family set, or only the strongest content. */
    val PRESETS: List<Pair<String, Set<String>>> = listOf(
        "Young children" to (CHOICES.map { it.id }.toSet() - "credits" - "art_nudity"),
        "Family" to CHOICES.filter { it.onByDefault }.map { it.id }.toSet(),
        "Teens" to setOf("profanity", "slurs", "sex", "nudity", "assault", "gore", "drugs", "written"),
    )

    val DEFAULT: Set<String> = CHOICES.filter { it.onByDefault }.map { it.id }.toSet()

    /**
     * What a family asked a Superclean to take out of one title. [cut]: cut scenes out rather than blur them.
     * [autoGuide]: look up the title's Parents Guide while the video downloads, and take out the scenes it lists in
     * the sections the family's choices cover, without asking.
     */
    data class Wishes(val remove: Set<String>, val guide: List<String> = emptyList(), val cut: Boolean = true, val autoGuide: Boolean = false) {
        fun toJson(): String = "{\"remove\":[" + remove.joinToString(",") { Json.str(it) } + "],\"guide\":[" +
            guide.joinToString(",") { Json.str(it) } + "],\"cut\":" + cut + ",\"autoGuide\":" + autoGuide + "}"

        /** The Parents Guide's scenes in the sections these choices cover, as "Section: what happens". */
        fun scenesFrom(found: Guide): List<String> =
            found.sections.filter { sectionWanted(it.name, remove) }.flatMap { s -> s.items.map { "${s.name}: $it" } }

        val pictureChoices: List<Choice> get() = CHOICES.filter { it.pictures && it.id in remove }
        val wordChoices: List<Choice> get() = CHOICES.filter { (it.words || it.talk) && it.id in remove }

        companion object {
            fun fromJson(text: String?): Wishes {
                val o = try { MiniJson.parse(text ?: "") as? Map<*, *> } catch (e: Exception) { null } ?: return Wishes(DEFAULT)
                val remove = (o["remove"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: DEFAULT
                val guide = (o["guide"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                return Wishes(remove, guide, o["cut"] != false, o["autoGuide"] == true)
            }
        }
    }

    // ---- The IMDb Parents Guide, looked up by Claude ----

    /** One section of a Parents Guide, such as "Sex & Nudity", with how strong IMDb says it is and what happens. */
    data class GuideSection(val name: String, val severity: String, val items: List<String>)

    data class Guide(val title: String, val year: String, val sections: List<GuideSection>) {
        val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
    }

    /** The web tools Claude may use to find the Parents Guide: a few searches and a few pages read. */
    const val GUIDE_TOOLS = "[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":4}," +
        "{\"type\":\"web_fetch_20250910\",\"name\":\"web_fetch\",\"max_uses\":3,\"max_content_tokens\":30000}]"

    /** Searching only, for an organisation that does not allow Claude to read web pages. */
    const val SEARCH_TOOLS = "[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":5}]"

    fun guideSystem(): String =
        "You help a parent decide what to take out of a film or TV episode before their children watch it. Find the title's " +
            "Parents Guide on IMDb (imdb.com/title/.../parentalguide) with your web tools and read it. If IMDb cannot be read, use " +
            "another parents' guide such as Kids-In-Mind or Common Sense Media, and say so in the source field. For an episode, use " +
            "the episode's guide if there is one, otherwise the series'. Keep every item short (under 25 words), plain and factual, " +
            "with no spoilers beyond what is needed, and say roughly when it happens if the guide says so."

    fun guideAsk(title: String, page: String): String =
        "The title, as the streaming page names it: \"$title\"" + (if (page.isNotBlank()) "\nThe page it is playing on: $page" else "") +
            "\n\nAnswer with JSON only, no other text, in this form:\n" +
            "{\"title\":\"...\",\"year\":\"...\",\"source\":\"IMDb\",\"sections\":[{\"name\":\"Sex & Nudity\",\"severity\":\"Moderate\",\"items\":[\"...\"]}]}\n" +
            "Use these sections, in this order: Sex & Nudity; Violence & Gore; Profanity; Alcohol, Drugs & Smoking; Frightening & " +
            "Intense Scenes. severity is None, Mild, Moderate or Severe. At most 10 items a section. If you cannot find the title, " +
            "answer {\"title\":\"\",\"sections\":[]}."

    fun readGuide(answer: String): Guide {
        val o = jsonIn(answer, "sections") ?: return Guide("", "", emptyList())
        val sections = (o["sections"] as? List<*>).orEmpty().mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val items = (m["items"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { t -> t.isNotEmpty() } }
            GuideSection(m["name"]?.toString().orEmpty(), m["severity"]?.toString().orEmpty(), items)
        }
        return Guide(o["title"]?.toString().orEmpty(), o["year"]?.toString().orEmpty(), sections)
    }

    /** Whether the family's standing choices cover a Parents Guide section, so its items start ticked. */
    fun sectionWanted(section: String, remove: Set<String>): Boolean {
        val s = section.lowercase()
        val ids = when {
            "sex" in s || "nud" in s -> listOf("nudity", "sex", "implied_sex", "suggestive", "immodesty", "passionate")
            "violen" in s || "gore" in s -> listOf("graphic_violence", "gore", "violence", "disturbing")
            "profan" in s || "language" in s -> listOf("profanity", "blasphemy", "slurs")
            "alcohol" in s || "drug" in s || "smok" in s -> listOf("drugs", "drinking", "smoking")
            "frighten" in s || "intense" in s -> listOf("scary", "disturbing")
            else -> emptyList()
        }
        return ids.any { it in remove }
    }

    // ---- Looking at the pictures ----

    /** Frames in each sheet, across and down. */
    const val ACROSS = 3
    const val DOWN = 3

    fun pictureSystem(wishes: Wishes): String {
        val asked = ArrayList<String>()
        if (wishes.pictureChoices.isNotEmpty()) asked += "Report every frame that shows any of the following, which the parent chose " +
            "to take out:\n" + wishes.pictureChoices.joinToString("\n") { "- ${it.id}: ${it.name}. ${it.detail}." }
        if (wishes.guide.isNotEmpty()) asked += "Also report, with what set to guide, every frame of these particular scenes from " +
            "the title's Parents Guide, which the parent asked to have taken out, from where each scene begins to where it ends:\n" +
            wishes.guide.joinToString("\n") { "- $it" }
        return "You help a parent make a family-safe copy of a video they own, for their children. You are shown sheets of " +
            "frames from the video; each frame has its time printed in its top-left corner.\n\n" + asked.joinToString("\n\n") +
            "\n\nBe careful: a missed frame is worse than an extra one. Ordinary hugs, kisses on the cheek and everyday clothing " +
            "are not reported unless chosen above."
    }

    fun pictureAsk(): String =
        "Answer with JSON only, no other text, in this form: {\"flagged\":[{\"time\":\"0:12:34\",\"what\":\"nudity\",\"severity\":2}]}. " +
            "what is one of the ids listed, or guide for a Parents Guide moment; severity is 1 (mild or brief), 2 (clear) or 3 " +
            "(explicit or graphic). Use the times printed on the frames. If nothing needs taking out, answer {\"flagged\":[]}."

    /** A moment Claude said to take out. */
    data class Flag(val atMs: Long, val what: String, val severity: Int)

    fun readFlags(answer: String): List<Flag> {
        val o = jsonIn(answer, "flagged") ?: return emptyList()
        return (o["flagged"] as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val at = time(m["time"]?.toString() ?: return@mapNotNull null) ?: return@mapNotNull null
            val what = (m["what"] ?: m["kind"])?.toString()?.lowercase() ?: "guide"
            val severity = (m["severity"] as? Double)?.toInt() ?: m["severity"]?.toString()?.toIntOrNull() ?: 2
            Flag(at, what, severity.coerceIn(1, 3))
        }
    }

    /**
     * Turns the moments Claude flagged into scenes to take out. Each frame stands for the time until the next
     * one ([stepMs]); close moments join into one scene, with a little extra on each side. They are cut out or
     * blurred, as [wishes] say. A moment of a kind the family did not choose is left in.
     */
    fun scenes(flags: List<Flag>, stepMs: Long, wishes: Wishes): List<Tag> {
        val action = if (wishes.cut) Action.SKIP else Action.BLUR
        val out = flags
            .filter { f -> choice(f.what) == null || f.what in wishes.remove }
            .sortedBy { it.atMs }
            .map { Tag(maxOf(0, it.atMs - stepMs), it.atMs + stepMs + 500, Category.SCENE, action, it.severity, Tag.SOURCE_CLAUDE) }
        return Ranges.merge(out)
    }

    // ---- Reading the captions ----

    fun wordsSystem(wishes: Wishes): String {
        val asked = ArrayList<String>()
        val words = wishes.wordChoices.filter { it.words }
        val talk = wishes.wordChoices.filter { it.talk }
        if (words.isNotEmpty()) asked += "Find every word or short phrase of these kinds, to be muted on its own:\n" +
            words.joinToString("\n") { "- ${it.id}: ${it.name}. ${it.detail}." } + "\nInclude words written to dodge a filter " +
            "(misspelt, letters swapped for symbols, other languages). Copy each word exactly as it is written in the line."
        if (talk.isNotEmpty()) asked += "Find every line that is this kind of talk, to be muted whole:\n" +
            talk.joinToString("\n") { "- ${it.id}: ${it.name}. ${it.detail}." }
        if (wishes.guide.isNotEmpty()) asked += "The parent also asked for these particular moments from the title's Parents Guide " +
            "to be taken out. Mute what belongs to them: just the words, when a moment is a word or phrase, or the whole lines, " +
            "when it is something said:\n" + wishes.guide.joinToString("\n") { "- $it" }
        return "You help a parent make a family-safe copy of a video for their children. You are given the video's caption " +
            "lines, numbered.\n\n" + asked.joinToString("\n\n")
    }

    fun wordsAsk(lines: List<String>): String =
        lines.mapIndexed { i, text -> "${i + 1}. ${text.replace('\n', ' ')}" }.joinToString("\n") +
            "\n\nAnswer with JSON only, no other text, in this form: {\"mute\":[{\"line\":3,\"words\":[\"word\"],\"whole\":false}]}. " +
            "words lists what to mute on its own; whole is true to mute the whole line. If nothing needs muting, answer {\"mute\":[]}."

    /** Which lines (counted from 1) have which words to mute, and whether to mute the whole line. */
    data class Mute(val line: Int, val words: List<String>, val whole: Boolean)

    fun readMutes(answer: String): List<Mute> {
        val o = jsonIn(answer, "mute") ?: return emptyList()
        return (o["mute"] as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val line = (m["line"] as? Double)?.toInt() ?: m["line"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
            val words = (m["words"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf { w -> w.isNotEmpty() } }.orEmpty()
            Mute(line, words, m["whole"] == true || words.isEmpty())
        }
    }

    /**
     * Turns what Claude found in the captions into stretches to mute: single words with the same timing as the
     * built-in words, and whole lines where asked (or where a word cannot be found again in its line).
     */
    fun muteTags(found: List<Pair<Cue, Mute>>, settings: FilterSettings): List<Tag> {
        val words = found.flatMap { it.second.words }.map { it.lowercase() }.toSet()
        val matcher = ProfanityMatcher(settings.copy(language = if (settings.language == Strictness.OFF) Strictness.HIGH else settings.language,
            customWords = settings.customWords + words))
        val out = ArrayList<Tag>()
        for ((cue, mute) in found) {
            val tags = if (mute.whole) emptyList() else CueTagger.tagsFor(listOf(cue), matcher)
            out += if (tags.isNotEmpty()) tags else listOf(Tag(cue.startMs, cue.endMs, Category.SCENE, Action.MUTE, 3, Tag.SOURCE_CLAUDE))
        }
        return out.map { it.copy(category = Category.SCENE, action = Action.MUTE, source = Tag.SOURCE_CLAUDE) }
    }

    // ---- Small helpers ----

    /** "1:02:03", "02:03" or "123.5" as milliseconds. */
    fun time(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) seconds = seconds * 60 + (p.trim().toDoubleOrNull() ?: return null)
        return (seconds * 1000).toLong()
    }

    /** The time printed on a frame. */
    fun label(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /**
     * The JSON object in a reply that has [key] (any object, without one). Claude may put words around it, a
     * code block, or, after searching the web, a few remarks of its own with braces in them.
     */
    fun jsonIn(answer: String, key: String? = null): Map<*, *>? {
        val starts = answer.indices.filter { answer[it] == '{' }.take(60)
        val ends = answer.indices.filter { answer[it] == '}' }.reversed().take(60)
        var first: Map<*, *>? = null
        var skipTo = -1
        for (start in starts) {
            if (start < skipTo) continue
            for (end in ends) {
                if (end <= start) break
                val found = try { MiniJson.parse(answer.substring(start, end + 1)) as? Map<*, *> } catch (e: Exception) { null } ?: continue
                if (key == null || key in found) return found
                if (first == null) first = found
                skipTo = end
                break
            }
        }
        return if (key == null) first else null
    }
}
