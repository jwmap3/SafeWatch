package com.safewatch.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One YouTube video as shown in a list. [meta] is a ready-made line such as "1.2M views · 3 days ago". */
data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String?,
    val meta: String,
    val duration: String,
) {
    /** A 16:9 picture that exists for every video. */
    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val largeThumbnail: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("channel", channel)
        .put("channelId", channelId ?: JSONObject.NULL).put("meta", meta).put("duration", duration)

    companion object {
        fun fromJson(o: JSONObject) = Video(o.getString("id"), o.optString("title"), o.optString("channel"),
            if (o.isNull("channelId")) null else o.optString("channelId"), o.optString("meta"), o.optString("duration"))
    }
}

data class Comment(val author: String, val text: String, val likes: String)

/** Everything shown on a video's own page besides the video. */
data class VideoPage(
    val title: String,
    val channel: String,
    val channelId: String?,
    val description: String,
    val related: List<Video>,
    /** Hand this to [YouTubeData.comments] to fetch the comments; null when the video has none to fetch. */
    val commentsToken: String?,
)

/**
 * Reads YouTube's lists so the app can show them in its own layout: search
 * results, a video's description, related videos and comments, and a
 * channel's latest uploads.
 *
 * Search, related videos and comments come from the same requests YouTube's
 * own website makes. YouTube does not publish or promise that format, so this
 * part can stop working when YouTube changes its site and will then need
 * updating. To keep that risk small, answers are not read by position: the
 * whole answer is searched for the pieces that describe a video, wherever
 * they sit. A channel's uploads come from YouTube's public feed, which is
 * stable.
 *
 * The videos themselves are not fetched here. They play in YouTube's own
 * embedded player. Everything here uses the network and must run off the main thread.
 */
object YouTubeData {
    private const val API = "https://www.youtube.com/youtubei/v1"
    private const val CLIENT_VERSION = "2.20250925.01.00"
    private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val VIDEO_KEYS = setOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer")

    fun search(query: String): List<Video> = videosIn(post("search", JSONObject().put("query", query)))

    fun page(videoId: String): VideoPage = pageIn(post("next", JSONObject().put("videoId", videoId)), videoId)

    /** Every video in answers YouTube's own website was given (see YouTubeMirror), in order, without repeats. */
    fun videosIn(answers: List<JSONObject>): List<Video> {
        val out = LinkedHashMap<String, Video>()
        for (answer in answers) for (video in videosIn(answer as Any)) out.putIfAbsent(video.id, video)
        return out.values.toList()
    }

    /** Reads a video's page from what YouTube's website was given for it. */
    fun pageIn(answer: JSONObject, videoId: String): VideoPage {
        val primary = find(answer, "videoPrimaryInfoRenderer")
        val owner = find(answer, "videoOwnerRenderer")
        val secondary = find(answer, "videoSecondaryInfoRenderer")
        val description = secondary?.optJSONObject("attributedDescription")?.optString("content").orEmpty()
            .ifEmpty { text(secondary?.opt("description")) }
        // The comments are fetched separately, using a token found in the comments part of the answer.
        var token: String? = null
        walk(answer) { key, value ->
            if (token == null && key == "itemSectionRenderer" && value is JSONObject &&
                value.optString("sectionIdentifier") == "comment-item-section") token = firstString(value, "token")
        }
        return VideoPage(
            title = text(primary?.opt("title")),
            channel = text(owner?.opt("title")),
            channelId = owner?.let { firstChannelId(it) },
            description = description,
            related = videosIn(find(answer, "secondaryResults") ?: answer).filter { it.id != videoId },
            commentsToken = token,
        )
    }

    fun comments(token: String): List<Comment> = commentsIn(post("next", JSONObject().put("continuation", token)))

    /** The comments in one of YouTube's answers; empty when it holds none. */
    fun commentsIn(answer: JSONObject): List<Comment> {
        val out = ArrayList<Comment>()
        walk(answer) { key, value ->
            if (key == "commentEntityPayload" && value is JSONObject) {
                val body = value.optJSONObject("properties")?.optJSONObject("content")?.optString("content").orEmpty()
                val author = value.optJSONObject("author")?.optString("displayName").orEmpty()
                val likes = value.optJSONObject("toolbar")?.optString("likeCountNotliked").orEmpty().trim()
                if (body.isNotEmpty()) out += Comment(author, body, likes)
            }
        }
        return out
    }

    /** A channel's newest uploads, from its public feed. */
    fun channelFeed(channelId: String): List<Video> {
        val xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        val channel = Regex("<author>\\s*<name>([^<]*)</name>").find(xml)?.groupValues?.get(1).orEmpty()
        return xml.split("<entry>").drop(1).mapNotNull { entry ->
            val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(entry)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("<title>([^<]*)</title>").find(entry)?.groupValues?.get(1).orEmpty()
            val published = Regex("<published>([^<T]+)").find(entry)?.groupValues?.get(1).orEmpty()
            Video(id, unescape(title), unescape(channel), channelId, published, "")
        }
    }

    // ---- Reading YouTube's answers ----

    private fun videosIn(root: Any): List<Video> {
        val out = LinkedHashMap<String, Video>()
        walk(root) { key, value ->
            if (value !is JSONObject) return@walk
            val video = when (key) {
                in VIDEO_KEYS -> fromRenderer(value)
                "lockupViewModel" -> fromLockup(value)
                else -> null
            }
            if (video != null && video.title.isNotEmpty()) out.putIfAbsent(video.id, video)
        }
        return out.values.toList()
    }

    // The long-standing way YouTube describes a video in a list.
    private fun fromRenderer(o: JSONObject): Video? {
        val id = o.optString("videoId").ifEmpty { return null }
        val owner = o.opt("ownerText") ?: o.opt("longBylineText") ?: o.opt("shortBylineText")
        val views = text(o.opt("shortViewCountText")).ifEmpty { text(o.opt("viewCountText")) }
        val age = text(o.opt("publishedTimeText"))
        return Video(id, text(o.opt("title")), text(owner), (owner as? JSONObject)?.let { firstChannelId(it) },
            listOf(views, age).filter { it.isNotEmpty() }.joinToString(" · "), text(o.opt("lengthText")))
    }

    // The newer way, which YouTube is moving its lists over to.
    private fun fromLockup(o: JSONObject): Video? {
        if (!o.optString("contentType").contains("VIDEO")) return null
        val id = o.optString("contentId").ifEmpty { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val lines = ArrayList<String>()
        if (rows != null) for (i in 0 until rows.length()) {
            val parts = rows.getJSONObject(i).optJSONArray("metadataParts") ?: continue
            lines += (0 until parts.length()).map { text(parts.getJSONObject(it).opt("text")) }.filter { it.isNotEmpty() }.joinToString(" · ")
        }
        // The length sits in a badge over the picture; it is the only text there shaped like a time.
        var duration = ""
        walk(o.opt("contentImage") ?: JSONObject()) { key, value ->
            if (duration.isEmpty() && key == "text" && value is String && Regex("\\d+:\\d\\d(:\\d\\d)?").matches(value)) duration = value
        }
        return Video(id, text(meta.opt("title")), lines.getOrElse(0) { "" }, firstChannelId(o), lines.drop(1).joinToString(" · "), duration)
    }

    /** Text as YouTube writes it: plain, as "content", or in a list of runs. */
    private fun text(value: Any?): String {
        if (value is String) return value
        if (value !is JSONObject) return ""
        value.optString("simpleText").ifEmpty { null }?.let { return it }
        value.optString("content").ifEmpty { null }?.let { return it }
        val runs = value.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }
    }

    /** Visits every named value in the answer, however deeply it is nested. */
    private fun walk(node: Any?, visit: (key: String, value: Any) -> Unit) {
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                val value = node.opt(key) ?: continue
                visit(key, value)
                walk(value, visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun find(root: Any, name: String): JSONObject? {
        var found: JSONObject? = null
        walk(root) { key, value -> if (found == null && key == name && value is JSONObject) found = value }
        return found
    }

    private fun firstString(root: Any, name: String): String? {
        var found: String? = null
        walk(root) { key, value -> if (found == null && key == name && value is String && value.isNotEmpty()) found = value }
        return found
    }

    /** Channel ids start with "UC"; the first one inside a video's description is its channel. */
    private fun firstChannelId(root: Any): String? {
        var found: String? = null
        walk(root) { key, value -> if (found == null && key == "browseId" && value is String && value.startsWith("UC")) found = value }
        return found
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ---- Network ----

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val locale = Locale.getDefault()
        body.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", CLIENT_VERSION)
            .put("hl", locale.language.ifEmpty { "en" }).put("gl", locale.country.ifEmpty { "US" })))
        val connection = URL("$API/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", AGENT)
            connection.setRequestProperty("Origin", "https://www.youtube.com")
            connection.setRequestProperty("X-YouTube-Client-Name", "1")
            connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode != 200) throw IOException("YouTube answered ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", AGENT)
            if (connection.responseCode != 200) throw IOException("YouTube answered ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
