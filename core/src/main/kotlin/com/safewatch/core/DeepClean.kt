package com.safewatch.core

import com.safewatch.core.tv.MiniJson
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * A call to Claude through Anthropic's Messages API, with the viewer's own API key. Used only for a
 * Deep clean, which the viewer asks for each time.
 */
class ClaudeApi(
    private val apiKey: String,
    val model: String = SONNET,
    private val base: String = "https://api.anthropic.com",
) {
    /** One piece of a message: a JPEG picture or some text. */
    sealed class Part {
        class Jpeg(val bytes: ByteArray) : Part()
        class Text(val text: String) : Part()
    }

    /** Why a call failed; [status] is the HTTP status, 0 when Anthropic could not be reached. */
    class Failure(message: String, val status: Int, val retryAfterMs: Long = 0) : Exception(message) {
        val worthRetrying: Boolean get() = status == 0 || status == 429 || status == 500 || status == 529 || status == 503
    }

    /** Sends one message and returns Claude's text answer. */
    fun ask(system: String, parts: List<Part>, maxTokens: Int = 2000, timeoutMs: Int = 180_000): String {
        val body = StringBuilder()
        body.append("{\"model\":").append(Json.str(model))
            .append(",\"max_tokens\":").append(maxTokens)
            .append(",\"system\":").append(Json.str(system))
            .append(",\"messages\":[{\"role\":\"user\",\"content\":[")
        parts.forEachIndexed { i, part ->
            if (i > 0) body.append(',')
            when (part) {
                is Part.Jpeg -> body.append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"")
                    .append(Base64.getEncoder().encodeToString(part.bytes)).append("\"}}")
                is Part.Text -> body.append("{\"type\":\"text\",\"text\":").append(Json.str(part.text)).append('}')
            }
        }
        body.append("]}]}")
        val c = try {
            URI("$base/v1/messages").toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw Failure("Could not reach Anthropic: ${e.message}", 0)
        }
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status != 200) {
                val said = errorMessage(text)
                val wait = (c.getHeaderField("retry-after")?.toDoubleOrNull() ?: 0.0) * 1000
                throw Failure(when (status) {
                    401 -> "The Claude key was not accepted. Check it in Settings > Deep clean."
                    402, 403 -> "Anthropic refused the request: $said"
                    429 -> "Too many requests to Claude at once; waiting."
                    else -> "Claude answered $status: $said"
                }, status, wait.toLong())
            }
            val answer = MiniJson.parse(text) as? Map<*, *> ?: throw Failure("Claude's answer could not be read", 200)
            val content = answer["content"] as? List<*> ?: emptyList<Any>()
            return content.mapNotNull { (it as? Map<*, *>)?.takeIf { b -> b["type"] == "text" }?.get("text") as? String }.joinToString("\n")
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure("Could not reach Anthropic: ${e.message}", 0)
        } finally {
            c.disconnect()
        }
    }

    private fun errorMessage(text: String): String = try {
        ((MiniJson.parse(text) as? Map<*, *>)?.get("error") as? Map<*, *>)?.get("message") as? String ?: text.take(200)
    } catch (e: IllegalArgumentException) {
        text.take(200)
    }

    companion object {
        const val SONNET = "claude-sonnet-5-5"
        const val HAIKU = "claude-haiku-5-5"
    }
}

/** Writing JSON text. */
object Json {
    fun str(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}

/**
 * What a Deep clean asks Claude, and how its answers become things to mute, blur or skip.
 *
 * Pictures go as sheets of frames, each frame with its time printed on it, so Claude can say which
 * moments to hide. Captions go as numbered lines, so Claude can say which words to mute; the words
 * are then muted with the same timing as every other curse word.
 */
object DeepClean {
    /** Frames in each sheet, across and down. */
    const val ACROSS = 3
    const val DOWN = 3

    fun pictureSystem(): String =
        "You help a parent make a family-safe copy of a video they own, for their young children. You are shown sheets of " +
            "frames from the video; each frame has its time printed in its top-left corner. Report every frame that shows " +
            "nudity (exposed breasts, buttocks or genitals, including partial or brief), sexual content (sex scenes, sexual " +
            "touching, suggestive undress) or graphic violence (visible blood, wounds, gore, a killing or a corpse shown " +
            "plainly). Mild action without blood, kissing and swimwear on a beach are not reported. Be careful: a missed " +
            "frame is worse than an extra one."

    fun pictureAsk(): String =
        "Answer with JSON only, no other text, in this form: {\"flagged\":[{\"time\":\"0:12:34\",\"kind\":\"nudity\",\"severity\":2}]}. " +
            "kind is nudity, sexual or gore; severity is 1 (mild or brief), 2 (clear) or 3 (explicit or graphic). " +
            "Use the times printed on the frames. If nothing needs hiding, answer {\"flagged\":[]}."

    fun wordsSystem(settings: FilterSettings): String {
        val level = when (settings.language) {
            Strictness.HIGH -> "strict: mute all profanity, including mild words such as damn, hell, crap and sucks used as cursing"
            Strictness.MEDIUM -> "moderate: mute profanity and crude words, but not the mildest (damn, heck, crap)"
            else -> "light: mute only strong profanity, slurs and crude sexual words"
        }
        return "You help a parent make a family-safe copy of a video for their young children. You are given the video's " +
            "caption lines, numbered. Find every word or short phrase that should be muted. The family's choice is $level. " +
            "Always include slurs and crude sexual language, and words written to dodge a filter (misspelt, letters swapped " +
            "for symbols, other languages). " +
            (if (settings.blasphemy) "Also include using God's or Jesus' name as an exclamation or curse; never sincere prayer or worship. "
            else "Do not include uses of God's name. ") +
            "Copy each word exactly as it is written in the line."
    }

    fun wordsAsk(lines: List<String>): String =
        lines.mapIndexed { i, text -> "${i + 1}. ${text.replace('\n', ' ')}" }.joinToString("\n") +
            "\n\nAnswer with JSON only, no other text, in this form: {\"mute\":[{\"line\":3,\"words\":[\"word\"]}]}. " +
            "If nothing needs muting, answer {\"mute\":[]}."

    /** A moment Claude said to hide. */
    data class Flag(val atMs: Long, val kind: String, val severity: Int)

    /** Reads the times and kinds out of Claude's answer about a sheet of frames. */
    fun readFlags(answer: String): List<Flag> {
        val o = jsonIn(answer) ?: return emptyList()
        val list = o["flagged"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val at = time(m["time"]?.toString() ?: return@mapNotNull null) ?: return@mapNotNull null
            val kind = (m["kind"]?.toString() ?: "nudity").lowercase()
            val severity = (m["severity"] as? Double)?.toInt() ?: m["severity"]?.toString()?.toIntOrNull() ?: 2
            Flag(at, kind, severity.coerceIn(1, 3))
        }
    }

    /** Reads which lines (counted from 1) have which words to mute. */
    fun readWords(answer: String): List<Pair<Int, List<String>>> {
        val o = jsonIn(answer) ?: return emptyList()
        val list = o["mute"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val line = (m["line"] as? Double)?.toInt() ?: m["line"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
            val words = (m["words"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf { w -> w.isNotEmpty() } }.orEmpty()
            line to words
        }
    }

    /**
     * Turns the moments Claude flagged into scenes to hide. Each frame stands for the time until the next
     * one ([stepMs]); close moments join into one scene, with a little extra on each side.
     */
    fun scenes(flags: List<Flag>, stepMs: Long): List<Tag> {
        val out = ArrayList<Tag>()
        for (f in flags.sortedBy { it.atMs }) {
            val category = if (f.kind.startsWith("gore") || f.kind.startsWith("viol")) Category.GORE else Category.NUDITY
            out += Tag(maxOf(0, f.atMs - stepMs), f.atMs + stepMs + 500, category, Action.BLUR, f.severity, Tag.SOURCE_CLAUDE)
        }
        return Ranges.merge(out)
    }

    /**
     * Turns the words Claude found into stretches to mute: each word is muted with the same timing as the
     * built-in words; a line where the word cannot be found again is muted whole.
     */
    fun wordTags(cues: List<Cue>, found: List<Pair<Cue, List<String>>>, settings: FilterSettings): List<Tag> {
        val words = found.flatMap { it.second }.map { it.lowercase() }.toSet()
        if (words.isEmpty() && found.isEmpty()) return emptyList()
        val matcher = ProfanityMatcher(settings.copy(language = if (settings.language == Strictness.OFF) Strictness.HIGH else settings.language,
            customWords = settings.customWords + words))
        val out = ArrayList<Tag>()
        for ((cue, list) in found) {
            val tags = CueTagger.tagsFor(listOf(cue), matcher)
            out += if (tags.isNotEmpty() || list.isEmpty()) tags else listOf(Tag(cue.startMs, cue.endMs, Category.LANGUAGE, Action.MUTE, 3, Tag.SOURCE_CLAUDE))
        }
        return out.map { it.copy(source = Tag.SOURCE_CLAUDE) }
    }

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

    /** The first JSON object in a reply, which may come with words around it or in a code block. */
    private fun jsonIn(answer: String): Map<*, *>? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { MiniJson.parse(answer.substring(start, end + 1)) as? Map<*, *> } catch (e: IllegalArgumentException) { null }
    }
}
