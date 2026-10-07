package com.safewatch.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/** A show or movie in the catalog. */
data class Title(
    /** "tvmaze:123", "tmdb:movie:55" or "tmdb:tv:77". */
    val id: String,
    val name: String,
    val year: String,
    /** "Series" or "Movie". */
    val kind: String,
    val poster: String?,
    val overview: String,
    /** The service known to carry it, when the catalog says. */
    val serviceId: String?,
    /** The title's own page on that service, when the catalog gives one. */
    val link: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("year", year).put("kind", kind)
        .put("poster", poster ?: JSONObject.NULL).put("overview", overview)
        .put("serviceId", serviceId ?: JSONObject.NULL).put("link", link ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Title(
            o.getString("id"), o.getString("name"), o.optString("year"), o.optString("kind", "Series"),
            o.optStringOrNull("poster"), o.optString("overview"),
            o.optStringOrNull("serviceId"), o.optStringOrNull("link"),
        )
    }
}

data class Shelf(val name: String, val titles: List<Title>)

data class Episode(val season: Int, val number: Int, val name: String, val airDate: String)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

/**
 * Where the home screen's titles come from.
 *
 * Out of the box it uses TVmaze, which needs no account: it lists what each
 * streaming service released recently, with artwork and a link to the show's
 * page on that service. With the viewer's own TMDB key (added under Filters)
 * it uses TMDB instead, which adds movies and "most popular on each service".
 *
 * Everything here uses the network and must run off the main thread.
 */
object Catalog {
    private const val TVMAZE = "https://api.tvmaze.com"
    private const val TMDB = "https://api.themoviedb.org/3"
    private const val DAYS = 14
    private val SKIPPED_TYPES = setOf("News", "Talk Show", "Sports", "Award Show", "Panel Show", "Game Show", "Variety")

    @Volatile private var memory: List<Shelf>? = null

    // ---- Home ----

    /** The shelves from the last successful load, shown at once while fresh ones are fetched. */
    fun savedHome(ctx: Context): List<Shelf>? {
        memory?.let { return it }
        val file = homeFile(ctx)
        if (!file.exists()) return null
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("titles")
                Shelf(o.getString("name"), (0 until t.length()).map { Title.fromJson(t.getJSONObject(it)) })
            }.also { memory = it }
        } catch (e: Exception) {
            null
        }
    }

    fun loadHome(ctx: Context): List<Shelf> {
        val key = Prefs.catalogKey(ctx)
        val services = Services.connected(ctx)
        val shelves = if (key.isNotEmpty()) tmdbHome(key, services) else tvmazeHome(ctx, services)
        if (shelves.isNotEmpty()) {
            memory = shelves
            val arr = JSONArray()
            for (s in shelves) {
                val t = JSONArray()
                s.titles.forEach { t.put(it.toJson()) }
                arr.put(JSONObject().put("name", s.name).put("titles", t))
            }
            try { homeFile(ctx).writeText(arr.toString()) } catch (e: IOException) { /* shown fresh next time */ }
        }
        return shelves
    }

    /** Forgets saved shelves, for when the services or the catalog key change. */
    fun reset(ctx: Context) {
        memory = null
        homeFile(ctx).delete()
    }

    private fun homeFile(ctx: Context) = File(ctx.cacheDir, "home.json")

    // ---- TVmaze ----

    private fun tvmazeHome(ctx: Context, services: List<Service>): List<Shelf> {
        val region = region()
        val today = LocalDate.now()
        val dir = File(ctx.cacheDir, "tvmaze").apply { mkdirs() }
        val shows = LinkedHashMap<String, Pair<Title, Int>>() // id -> title and popularity
        var failures = 0

        for (back in 0 until DAYS) {
            val date = today.minusDays(back.toLong()).toString()
            // Services such as Netflix are listed as worldwide; others under their own country.
            for (country in listOf(region, "")) {
                val file = File(dir, "$date-${country.ifEmpty { "world" }}.json")
                val text = try {
                    // Past days never change, so each is fetched once. Today is fetched every time.
                    if (back > 0 && file.exists()) file.readText()
                    else get("$TVMAZE/schedule/web?date=$date&country=$country").also { file.writeText(it) }
                } catch (e: Exception) {
                    failures++
                    if (file.exists()) file.readText() else continue
                }
                val episodes = try { JSONArray(text) } catch (e: Exception) { continue }
                for (i in 0 until episodes.length()) {
                    val show = episodes.getJSONObject(i).optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                    if (show.optString("type") in SKIPPED_TYPES) continue
                    val title = tvmazeTitle(show) ?: continue
                    if (title.poster == null || title.serviceId == null) continue
                    shows.putIfAbsent(title.id, title to show.optInt("weight"))
                }
            }
        }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 30L * 24 * 3600 * 1000 }?.forEach { it.delete() }
        if (shows.isEmpty() && failures > 0) throw IOException("The catalog could not be reached")

        val mine = services.map { it.id }.toSet()
        val ranked = shows.values.sortedByDescending { it.second }.map { it.first }
        val shelves = ArrayList<Shelf>()
        val top = ranked.filter { it.serviceId in mine }.take(20)
        if (top.isNotEmpty()) shelves += Shelf("New this week", top)
        for (service in services) {
            val titles = ranked.filter { it.serviceId == service.id }.take(20)
            if (titles.size >= 3) shelves += Shelf("New on ${service.name}", titles)
        }
        return shelves
    }

    private fun tvmazeTitle(show: JSONObject): Title? {
        val name = show.optString("name").ifEmpty { return null }
        // Streaming originals name their service as a "web channel"; shows from a TV channel name the channel.
        val service = Services.named(show.optJSONObject("webChannel")?.optString("name"))
            ?: Services.named(show.optJSONObject("network")?.optString("name"))
        val site = show.optStringOrNull("officialSite")
        return Title(
            id = "tvmaze:" + show.getInt("id"),
            name = name,
            year = show.optStringOrNull("premiered").orEmpty().take(4),
            kind = "Series",
            poster = show.optJSONObject("image")?.optStringOrNull("original"),
            overview = plain(show.optString("summary")),
            serviceId = service?.id,
            link = if (service != null && service.owns(site)) site else null,
        )
    }

    // ---- TMDB ----

    private fun tmdbHome(key: String, services: List<Service>): List<Shelf> {
        val shelves = ArrayList<Shelf>()
        val trending = tmdbTitles(tmdb(key, "/trending/all/week", ""), null, null)
        if (trending.isNotEmpty()) shelves += Shelf("Trending this week", trending)
        val region = region()
        for (service in services) {
            val query = "watch_region=$region&sort_by=popularity.desc&with_watch_providers=" + service.tmdbIds.joinToString("%7C")
            val movies = try { tmdbTitles(tmdb(key, "/discover/movie", query), "movie", service.id) } catch (e: Exception) { emptyList() }
            val series = try { tmdbTitles(tmdb(key, "/discover/tv", query), "tv", service.id) } catch (e: Exception) { emptyList() }
            // Movies and series take turns so the shelf shows both.
            val mixed = ArrayList<Title>()
            for (i in 0 until maxOf(movies.size, series.size)) {
                series.getOrNull(i)?.let { mixed += it }
                movies.getOrNull(i)?.let { mixed += it }
            }
            if (mixed.size >= 3) shelves += Shelf("Popular on ${service.name}", mixed.take(20))
        }
        return shelves
    }

    private fun tmdbTitles(json: JSONObject, fixedType: String?, serviceId: String?): List<Title> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val type = fixedType ?: o.optString("media_type", "movie")
            if (type != "movie" && type != "tv") continue
            val name = o.optString("title").ifEmpty { o.optString("name") }
            if (name.isEmpty()) continue
            val date = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
            val poster = o.optStringOrNull("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
            out += Title("tmdb:$type:" + o.getInt("id"), name, date.take(4), if (type == "tv") "Series" else "Movie",
                poster, o.optString("overview"), serviceId, null)
        }
        return out
    }

    private fun tmdb(key: String, path: String, query: String): JSONObject {
        // TMDB issues two kinds of key: a short one sent in the address and a long token sent as a header.
        val isToken = key.length > 60
        val params = listOf(query, if (isToken) "" else "api_key=" + Uri.encode(key)).filter { it.isNotEmpty() }
        val url = TMDB + path + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return JSONObject(get(url, if (isToken) key else null))
    }

    // ---- Search and title pages ----

    fun search(ctx: Context, query: String): List<Title> {
        val key = Prefs.catalogKey(ctx)
        if (key.isNotEmpty()) {
            return tmdbTitles(tmdb(key, "/search/multi", "include_adult=false&query=" + Uri.encode(query)), null, null)
        }
        val found = JSONArray(get("$TVMAZE/search/shows?q=" + Uri.encode(query)))
        return (0 until found.length()).mapNotNull { tvmazeTitle(found.getJSONObject(it).getJSONObject("show")) }
    }

    /** Ids of the services that include the title, or null when this catalog cannot say more than the title already does. */
    fun servicesFor(ctx: Context, title: Title): List<String>? {
        val key = Prefs.catalogKey(ctx)
        val parts = title.id.split(':')
        if (parts[0] != "tmdb" || key.isEmpty()) return null
        val here = tmdb(key, "/${parts[1]}/${parts[2]}/watch/providers", "").optJSONObject("results")?.optJSONObject(region())
            ?: return emptyList()
        val ids = LinkedHashSet<String>()
        for (kind in listOf("flatrate", "free", "ads")) {
            val list = here.optJSONArray(kind) ?: continue
            for (i in 0 until list.length()) {
                val providerId = list.getJSONObject(i).optInt("provider_id")
                Services.all.firstOrNull { providerId in it.tmdbIds }?.let { ids += it.id }
            }
        }
        return ids.toList()
    }

    /** The episodes of a series, oldest first. Empty for movies. */
    fun episodes(ctx: Context, title: Title): List<Episode> {
        val parts = title.id.split(':')
        if (parts[0] == "tvmaze") {
            val arr = JSONArray(get("$TVMAZE/shows/${parts[1]}/episodes"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Episode(o.optInt("season"), o.optInt("number"), o.optStringOrNull("name").orEmpty(), o.optStringOrNull("airdate").orEmpty())
            }
        }
        if (parts[0] == "tmdb" && parts[1] == "tv") {
            val key = Prefs.catalogKey(ctx)
            val seasons = tmdb(key, "/tv/${parts[2]}", "").optJSONArray("seasons") ?: return emptyList()
            val out = ArrayList<Episode>()
            for (i in 0 until seasons.length()) {
                val number = seasons.getJSONObject(i).optInt("season_number")
                if (number == 0) continue // specials
                val eps = tmdb(key, "/tv/${parts[2]}/season/$number", "").optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val o = eps.getJSONObject(j)
                    out += Episode(number, o.optInt("episode_number"), o.optString("name"), o.optString("air_date"))
                }
            }
            return out
        }
        return emptyList()
    }

    // ---- Helpers ----

    private fun region(): String = Locale.getDefault().country.ifEmpty { "US" }.uppercase()

    private fun plain(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

    private fun get(url: String, bearer: String? = null): String {
        for (attempt in 0..2) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "SafeWatch/0.2 (personal Android app)")
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                val code = connection.responseCode
                if (code == 429 && attempt < 2) {
                    Thread.sleep(2500) // asked to slow down; wait and try again
                    continue
                }
                if (code != 200) throw IOException("Catalog answered $code")
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Catalog is busy")
    }
}
