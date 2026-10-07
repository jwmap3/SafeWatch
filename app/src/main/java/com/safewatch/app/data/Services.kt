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
    /**
     * True when the service's phone website refuses to play and sends viewers to its own app.
     * The browser asks these for their computer website instead, which plays in a browser.
     */
    val needsDesktopSite: Boolean = protectedVideo,
    /** The page where the viewer signs in. */
    val signInUrl: String = homeUrl,
    /** Names of cookies the service only sets once someone is signed in, where known. */
    val sessionCookies: List<String> = emptyList(),
) {
    fun searchFor(query: String): String = searchUrl.replace("%s", Uri.encode(query))

    fun isNamed(catalogName: String): Boolean = catalogName.trim().lowercase() in catalogNames

    fun owns(url: String?): Boolean = url != null && (Uri.parse(url).host ?: "").endsWith(domain)
}

object Services {
    val all = listOf(
        Service("netflix", "Netflix", "https://www.netflix.com/", "https://www.netflix.com/search?q=%s",
            "netflix.com", listOf("netflix"), listOf(8),
            signInUrl = "https://www.netflix.com/login", sessionCookies = listOf("NetflixId", "SecureNetflixId")),
        Service("hbomax", "HBO Max", "https://www.hbomax.com/", "https://play.hbomax.com/search?q=%s",
            "hbomax.com", listOf("hbo max", "max", "hbo"), listOf(1899, 384),
            signInUrl = "https://auth.hbomax.com/login"),
        Service("prime", "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/search?phrase=%s",
            "primevideo.com", listOf("prime video", "amazon prime video", "amazon video"), listOf(9),
            sessionCookies = listOf("at-main-av", "x-main-av", "at-main", "x-main")),
        Service("disney", "Disney+", "https://www.disneyplus.com/", "https://www.disneyplus.com/browse/search?q=%s",
            "disneyplus.com", listOf("disney+", "disney plus"), listOf(337),
            signInUrl = "https://www.disneyplus.com/login"),
        Service("hulu", "Hulu", "https://www.hulu.com/", "https://www.hulu.com/search?q=%s",
            "hulu.com", listOf("hulu"), listOf(15),
            signInUrl = "https://auth.hulu.com/web/login", sessionCookies = listOf("_hulu_uid")),
        Service("appletv", "Apple TV+", "https://tv.apple.com/", "https://tv.apple.com/search?term=%s",
            "tv.apple.com", listOf("apple tv+", "apple tv", "apple tv plus"), listOf(350)),
        Service("peacock", "Peacock", "https://www.peacocktv.com/", "https://www.peacocktv.com/watch/search?q=%s",
            "peacocktv.com", listOf("peacock", "peacock premium"), listOf(386, 387),
            signInUrl = "https://www.peacocktv.com/signin"),
        Service("paramount", "Paramount+", "https://www.paramountplus.com/", "https://www.paramountplus.com/search/?query=%s",
            "paramountplus.com", listOf("paramount+", "paramount plus"), listOf(531),
            signInUrl = "https://www.paramountplus.com/account/signin/", sessionCookies = listOf("CBS_COM")),
        Service("youtube", "YouTube", "https://m.youtube.com/", "https://m.youtube.com/results?search_query=%s",
            "youtube.com", listOf("youtube", "youtube premium"), listOf(192), protectedVideo = false,
            signInUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F", sessionCookies = listOf("LOGIN_INFO")),
        Service("tubi", "Tubi", "https://tubitv.com/", "https://tubitv.com/search/%s",
            "tubitv.com", listOf("tubi", "tubi tv"), listOf(73),
            signInUrl = "https://tubitv.com/login"),
        Service("pluto", "Pluto TV", "https://pluto.tv/", "https://pluto.tv/search/details?query=%s",
            "pluto.tv", listOf("pluto tv"), listOf(300)),
    )

    val defaults = setOf("netflix", "hbomax", "prime", "disney", "hulu", "youtube")

    fun byId(id: String?): Service? = all.firstOrNull { it.id == id }

    /** The service a web address belongs to, if any. */
    fun forUrl(url: String?): Service? = all.firstOrNull { it.owns(url) }

    fun named(catalogName: String?): Service? = if (catalogName == null) null else all.firstOrNull { it.isNamed(catalogName) }

    fun connected(ctx: Context): List<Service> {
        val ids = Prefs.connectedServices(ctx)
        return all.filter { it.id in ids }
    }
}
