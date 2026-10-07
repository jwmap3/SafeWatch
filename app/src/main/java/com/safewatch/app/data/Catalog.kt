package com.safewatch.app.data

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A movie or show in the catalog. [type] is "movie" or "tv". */
data class Title(val id: Int, val type: String, val name: String, val year: String, val posterPath: String?)

/**
 * The movie and show catalog, from The Movie Database (TMDB). It supplies the
 * posters on the home screen, title search, and which streaming services carry
 * a title (TMDB gets that part from JustWatch).
 *
 * Needs the viewer's own free TMDB key, added under Filters. All calls here
 * use the network and must run off the main thread.
 */
object Catalog {
    private const val API = "https://api.themoviedb.org/3"

    fun trending(key: String): List<Title> = titles(get(key, "/trending/all/week", ""))

    fun search(key: String, query: String): List<Title> =
        titles(get(key, "/search/multi", "include_adult=false&query=" + Uri.encode(query)))

    /** Names of the services that include [title] in a subscription or show it free, in [region] (a country code such as "US"). */
    fun providers(key: String, title: Title, region: String): List<String> {
        val byRegion = get(key, "/${title.type}/${title.id}/watch/providers", "").optJSONObject("results")
        val here = byRegion?.optJSONObject(region.uppercase()) ?: return emptyList()
        val names = ArrayList<String>()
        for (kind in listOf("flatrate", "free", "ads")) {
            val list = here.optJSONArray(kind) ?: continue
            for (i in 0 until list.length()) names += list.getJSONObject(i).optString("provider_name")
        }
        return names.filter { it.isNotEmpty() }.distinct()
    }

    fun posterUrl(path: String): String = "https://image.tmdb.org/t/p/w185$path"

    private fun titles(json: JSONObject): List<Title> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val type = o.optString("media_type", "movie")
            if (type != "movie" && type != "tv") continue
            val name = o.optString("title").ifEmpty { o.optString("name") }
            if (name.isEmpty()) continue
            val date = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
            val poster = if (o.isNull("poster_path")) null else o.optString("poster_path").ifEmpty { null }
            out += Title(o.getInt("id"), type, name, date.take(4), poster)
        }
        return out
    }

    private fun get(key: String, path: String, query: String): JSONObject {
        // TMDB issues two kinds of key: a short one sent in the address and a long token sent as a header.
        val isToken = key.length > 60
        val params = listOf(query, if (isToken) "" else "api_key=" + Uri.encode(key)).filter { it.isNotEmpty() }
        val url = URL(API + path + if (params.isEmpty()) "" else "?" + params.joinToString("&"))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (isToken) connection.setRequestProperty("Authorization", "Bearer $key")
            if (connection.responseCode != 200) throw IOException("Catalog answered ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
