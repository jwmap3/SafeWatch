package com.safewatch.app.data

import android.content.Context
import android.net.Uri

/**
 * A streaming service the app can open in its built-in browser. The viewer
 * signs in on the service's own website; the app never sees the password.
 */
data class Service(
    val id: String,
    val name: String,
    val homeUrl: String,
    /** Search page address with `%s` where the search words go. */
    val searchUrl: String,
    /** Part of the web address that identifies a page as belonging to this service. */
    val domain: String,
    /** Names the title catalogs use for this service, in lower case. */
    val catalogNames: List<String>,
    /** The service's numbers in the TMDB catalog. */
    val tmdbIds: List<Int>,
    /** True when the service hides its picture from other software, so nudity cannot be detected live. */
    val protectedVideo: Boolean = true,
) {
    fun searchFor(query: String): String = searchUrl.replace("%s", Uri.encode(query))

    fun isNamed(catalogName: String): Boolean = catalogName.trim().lowercase() in catalogNames

    fun owns(url: String?): Boolean = url != null && (Uri.parse(url).host ?: "").endsWith(domain)
}

object Services {
    val all = listOf(
        Service("netflix", "Netflix", "https://www.netflix.com/", "https://www.netflix.com/search?q=%s",
            "netflix.com", listOf("netflix"), listOf(8)),
        Service("hbomax", "HBO Max", "https://www.hbomax.com/", "https://play.hbomax.com/search?q=%s",
            "hbomax.com", listOf("hbo max", "max", "hbo"), listOf(1899, 384)),
        Service("prime", "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/search?phrase=%s",
            "primevideo.com", listOf("prime video", "amazon prime video", "amazon video"), listOf(9)),
        Service("disney", "Disney+", "https://www.disneyplus.com/", "https://www.disneyplus.com/browse/search?q=%s",
            "disneyplus.com", listOf("disney+", "disney plus"), listOf(337)),
        Service("hulu", "Hulu", "https://www.hulu.com/", "https://www.hulu.com/search?q=%s",
            "hulu.com", listOf("hulu"), listOf(15)),
        Service("appletv", "Apple TV+", "https://tv.apple.com/", "https://tv.apple.com/search?term=%s",
            "tv.apple.com", listOf("apple tv+", "apple tv", "apple tv plus"), listOf(350)),
        Service("peacock", "Peacock", "https://www.peacocktv.com/", "https://www.peacocktv.com/watch/search?q=%s",
            "peacocktv.com", listOf("peacock", "peacock premium"), listOf(386, 387)),
        Service("paramount", "Paramount+", "https://www.paramountplus.com/", "https://www.paramountplus.com/search/?query=%s",
            "paramountplus.com", listOf("paramount+", "paramount plus"), listOf(531)),
        Service("youtube", "YouTube", "https://m.youtube.com/", "https://m.youtube.com/results?search_query=%s",
            "youtube.com", listOf("youtube", "youtube premium"), listOf(192), protectedVideo = false),
        Service("tubi", "Tubi", "https://tubitv.com/", "https://tubitv.com/search/%s",
            "tubitv.com", listOf("tubi", "tubi tv"), listOf(73)),
        Service("pluto", "Pluto TV", "https://pluto.tv/", "https://pluto.tv/search/details?query=%s",
            "pluto.tv", listOf("pluto tv"), listOf(300)),
    )

    val defaults = setOf("netflix", "hbomax", "prime", "disney", "hulu", "youtube")

    fun byId(id: String?): Service? = all.firstOrNull { it.id == id }

    fun named(catalogName: String?): Service? = if (catalogName == null) null else all.firstOrNull { it.isNamed(catalogName) }

    fun connected(ctx: Context): List<Service> {
        val ids = Prefs.connectedServices(ctx)
        return all.filter { it.id in ids }
    }
}
