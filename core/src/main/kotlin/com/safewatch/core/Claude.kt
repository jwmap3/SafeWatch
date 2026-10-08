package com.safewatch.core

import com.safewatch.core.tv.MiniJson
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * A call to Claude through Anthropic's Messages API, with the viewer's own API key. Used only for a
 * Superclean, which the viewer asks for each time.
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

    /**
     * Sends one message and returns Claude's text answer. [tools] is a JSON array of Anthropic's own tools
     * (web search, web fetch) for Claude to use while it answers, or null. A long turn with tools can come
     * back paused; it is then sent back as it is, for Claude to carry on, a few times at most.
     */
    fun ask(system: String, parts: List<Part>, maxTokens: Int = 2000, timeoutMs: Int = 180_000, tools: String? = null): String {
        val question = StringBuilder("{\"role\":\"user\",\"content\":[")
        parts.forEachIndexed { i, part ->
            if (i > 0) question.append(',')
            when (part) {
                is Part.Jpeg -> question.append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"")
                    .append(Base64.getEncoder().encodeToString(part.bytes)).append("\"}}")
                is Part.Text -> question.append("{\"type\":\"text\",\"text\":").append(Json.str(part.text)).append('}')
            }
        }
        question.append("]}")
        val messages = arrayListOf(question.toString())
        val said = StringBuilder()
        repeat(MAX_CONTINUES + 1) {
            val answer = post(system, messages, maxTokens, timeoutMs, tools)
            val content = answer["content"] as? List<*> ?: emptyList<Any>()
            // With tools, the answer comes in pieces between Claude's searches; the pieces are joined as one text.
            content.forEach { block -> ((block as? Map<*, *>)?.takeIf { it["type"] == "text" }?.get("text") as? String)?.let { said.append(it) } }
            if (answer["stop_reason"] != "pause_turn") return said.toString()
            messages += "{\"role\":\"assistant\",\"content\":" + Json.write(content) + "}"
        }
        return said.toString()
    }

    private fun post(system: String, messages: List<String>, maxTokens: Int, timeoutMs: Int, tools: String?): Map<*, *> {
        val body = StringBuilder()
        body.append("{\"model\":").append(Json.str(model))
            .append(",\"max_tokens\":").append(maxTokens)
            .append(",\"system\":").append(Json.str(system))
        if (tools != null) body.append(",\"tools\":").append(tools)
        body.append(",\"messages\":[").append(messages.joinToString(",")).append("]}")
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
                    401 -> "The Claude key was not accepted. Check it in Settings > Superclean."
                    402, 403 -> "Anthropic refused the request: $said"
                    429 -> "Too many requests to Claude at once; waiting."
                    else -> "Claude answered $status: $said"
                }, status, wait.toLong())
            }
            return MiniJson.parse(text) as? Map<*, *> ?: throw Failure("Claude's answer could not be read", 200)
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
        private const val MAX_CONTINUES = 3
    }
}

/** Writing JSON text. */
object Json {
    /** Any value as read by MiniJson (maps, lists, strings, numbers, true, false, null) written back as JSON. */
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> str(value)
        is Boolean -> value.toString()
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { str(it.key.toString()) + ":" + write(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> str(value.toString())
    }

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
