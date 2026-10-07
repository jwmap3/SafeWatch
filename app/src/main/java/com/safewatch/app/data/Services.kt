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
) {
    fun searchFor(query: String): String = searchUrl.replace("%s", Uri.encode(query))
}

object Services {
    val all = listOf(
        Service("youtube", "YouTube", "https://m.youtube.com/", "https://m.youtube.com/results?search_query=%s"),
        Service("netflix", "Netflix", "https://www.netflix.com/", "https://www.netflix.com/search?q=%s"),
        Service("prime", "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/search?phrase=%s"),
        Service("disney", "Disney+", "https://www.disneyplus.com/", "https://www.disneyplus.com/browse/search?q=%s"),
        Service("hulu", "Hulu", "https://www.hulu.com/", "https://www.hulu.com/search?q=%s"),
        Service("hbomax", "HBO Max", "https://www.hbomax.com/", "https://play.hbomax.com/search?q=%s"),
        Service("peacock", "Peacock", "https://www.peacocktv.com/", "https://www.peacocktv.com/watch/search?q=%s"),
        Service("paramount", "Paramount+", "https://www.paramountplus.com/", "https://www.paramountplus.com/search/?query=%s"),
        Service("appletv", "Apple TV+", "https://tv.apple.com/", "https://tv.apple.com/search?term=%s"),
        Service("tubi", "Tubi", "https://tubitv.com/", "https://tubitv.com/search/%s"),
        Service("pluto", "Pluto TV", "https://pluto.tv/", "https://pluto.tv/search/details?query=%s"),
    )

    val defaults = setOf("youtube", "netflix", "prime", "disney", "hulu")

    fun connected(ctx: Context): List<Service> {
        val ids = Prefs.connectedServices(ctx)
        return all.filter { it.id in ids }
    }
}
