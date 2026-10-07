package com.safewatch.core

import java.net.URI
import java.security.MessageDigest

/** A stable name for a video, so the scenes marked in it are found again next time. */
object MediaKey {
    // The parts of a web address that say which video it is. Everything else after the "?"
    // (tracking codes, where playback resumes, how the page was reached) changes from visit
    // to visit and would stop a video being recognised the next time.
    private val IDENTIFYING = setOf(
        "v", "id", "vid", "video", "videoid", "video_id", "episode", "ep", "season",
        "movie", "film", "title", "play", "watch", "p",
    )

    fun forFile(name: String, sizeBytes: Long): String = "file:$name:$sizeBytes"

    /** The same video reached through a slightly different link gets the same key. */
    fun forUrl(url: String): String {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return "web:${url.trim()}" }
        var host = (uri.host ?: return "web:${url.trim()}").lowercase()
        host = host.removePrefix("www.").removePrefix("m.")
        var path = (uri.rawPath ?: "").trimEnd('/')
        var params = (uri.rawQuery ?: "").split('&')
            .filter { it.substringBefore('=').lowercase() in IDENTIFYING }
        if (host == "youtu.be" && path.length > 1) {
            params = listOf("v=" + path.removePrefix("/"))
            host = "youtube.com"
            path = "/watch"
        }
        val query = params.sorted().joinToString("&")
        return "web:$host$path" + if (query.isEmpty()) "" else "?$query"
    }

    /** A name for the key that is safe to use as a file name. */
    fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
