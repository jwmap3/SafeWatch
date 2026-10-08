package com.safewatch.core

/**
 * What a streaming manifest says about a video, read before anything is downloaded.
 *
 * Websites that stream a video in pieces hand the player a manifest: HLS (".m3u8") or
 * DASH (".mpd"). It lists the pieces, and says whether they are encrypted. EdenOS only
 * ever saves a stream whose pieces are not encrypted: it never unlocks anything.
 */
object StreamInfo {
    enum class Kind { HLS, DASH }

    fun kindOf(text: String): Kind? {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("#EXTM3U") -> Kind.HLS
            Regex("<(?:\\w+:)?MPD[\\s>]").containsMatchIn(text.take(4000)) -> Kind.DASH
            else -> null
        }
    }

    // ---- HLS ----

    private val KEY = Regex("#EXT-X-(?:SESSION-)?KEY:([^\\r\\n]*)")

    /** Any key other than METHOD=NONE means the pieces are encrypted. */
    fun hlsLocked(text: String): Boolean = KEY.findAll(text).any { !it.groupValues[1].contains("METHOD=NONE") }

    fun hlsIsMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /** A media playlist that is still growing: a live broadcast, with no "ahead" to save. */
    fun hlsLive(mediaPlaylist: String): Boolean =
        mediaPlaylist.contains("#EXTINF") && !mediaPlaylist.contains("#EXT-X-ENDLIST") && !mediaPlaylist.contains("#EXT-X-PLAYLIST-TYPE:VOD")

    /** The video playlists a master playlist offers, the best first (by bandwidth). */
    fun hlsVariants(master: String): List<String> {
        val lines = master.lines().map { it.trim() }
        val out = ArrayList<Pair<Long, String>>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val bandwidth = Regex("[:,]BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            out += bandwidth to uri
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** The playlists of a master playlist's renditions of one [type] (AUDIO, SUBTITLES). */
    fun hlsRenditions(master: String, type: String): List<String> =
        master.lines().filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=$type") }
            .mapNotNull { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    // ---- DASH ----

    fun dashLocked(text: String): Boolean = Regex("<(?:\\w+:)?ContentProtection\\b").containsMatchIn(text)

    fun dashLive(text: String): Boolean = Regex("\\btype=\"dynamic\"").containsMatchIn(text)

    /** Why a manifest cannot be saved, in words for the viewer; null when it can. Media playlists are passed for HLS masters. */
    fun refusal(manifest: String, mediaPlaylists: List<String> = emptyList()): String? {
        return when (kindOf(manifest)) {
            Kind.HLS -> when {
                hlsLocked(manifest) || mediaPlaylists.any { hlsLocked(it) } -> LOCKED
                (if (hlsIsMaster(manifest)) mediaPlaylists.any { hlsLive(it) } else hlsLive(manifest)) -> LIVE
                else -> null
            }
            Kind.DASH -> when {
                dashLocked(manifest) -> LOCKED
                dashLive(manifest) -> LIVE
                else -> null
            }
            null -> "The video's list of pieces could not be read"
        }
    }

    const val LOCKED = "This video is locked (encrypted) by the site, so it cannot be saved. Use Mirror to TV for this one."
    const val LIVE = "This is a live broadcast, so there is nothing to save ahead of time. Use Mirror to TV for this one."
}
