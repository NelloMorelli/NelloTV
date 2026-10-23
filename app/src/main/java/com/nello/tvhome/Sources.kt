package com.nello.tvhome

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** TMDB v3 — Italian metadata, Italian region. */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    private fun url(path: String, params: String = "") =
        "$BASE$path?api_key=${BuildConfig.TMDB_KEY}&language=it-IT&region=IT$params"

    /** [path] like "/trending/all/week" or "/movie/popular"; [type] forces movie/series when the endpoint doesn't say. */
    suspend fun list(path: String, type: String? = null, page: Int = 1): List<MediaItem> {
        val results = JSONObject(Net.get(url(path, if (page > 1) "&page=$page" else ""))).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val kind = type ?: when (o.optString("media_type")) {
                "movie" -> "movie"
                "tv" -> "series"
                else -> return@mapNotNull null
            }
            parse(o, kind)
        }.filter { it.backdrop != null }
    }

    /** Title search; keeps results without a backdrop, falling back to the poster. */
    suspend fun search(type: String, query: String): List<MediaItem> {
        val path = if (type == "movie") "/search/movie" else "/search/tv"
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(Net.get(url(path, "&query=$q&include_adult=false"))).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).map { results.getJSONObject(it) }
            .sortedByDescending { it.optDouble("popularity", 0.0) }
            .map { o -> parse(o, type).let { if (it.image == null) it.copy(image = o.str("poster_path")?.let { p -> "$IMG/w342$p" }) else it } }
            .filter { it.image != null }
    }

    private fun parse(o: JSONObject, type: String): MediaItem {
        val date = o.str("release_date") ?: o.str("first_air_date")
        val vote = o.optDouble("vote_average", 0.0)
        return MediaItem(
            title = o.str("title") ?: o.str("name") ?: "",
            type = type,
            tmdbId = o.optInt("id"),
            image = o.str("backdrop_path")?.let { "$IMG/w780$it" },
            poster = o.str("poster_path")?.let { "$IMG/w342$it" },
            backdrop = o.str("backdrop_path")?.let { "$IMG/w1280$it" },
            overview = o.str("overview"),
            year = date?.take(4),
            rating = if (vote > 0) String.format(Locale.ITALY, "%.1f", vote) else null,
        )
    }

    private fun tmdbType(type: String) = if (type == "movie") "movie" else "tv"

    suspend fun imdbId(type: String, tmdbId: Int): String? =
        JSONObject(Net.get(url("/${tmdbType(type)}/$tmdbId/external_ids"))).str("imdb_id")

    /** Italian title, overview, backdrop and logo for an item we only know by id (Trakt). */
    suspend fun details(type: String, tmdbId: Int): JSONObject =
        JSONObject(Net.get(url("/${tmdbType(type)}/$tmdbId", "&append_to_response=images&include_image_language=it,en,null")))

    /** Italian details (with logos) for an IMDb id, or null if TMDB doesn't know it. */
    suspend fun findByImdb(imdbId: String, type: String): JSONObject? {
        if (!imdbId.startsWith("tt")) return null
        val found = JSONObject(Net.get(url("/find/$imdbId", "&external_source=imdb_id")))
        val list = found.optJSONArray(if (type == "movie") "movie_results" else "tv_results")
        val id = list?.optJSONObject(0)?.optInt("id") ?: return null
        return details(type, id)
    }

    /** "If you liked X": TMDB recommendations for a title. */
    suspend fun recommendations(type: String, tmdbId: Int): List<MediaItem> {
        val results = JSONObject(Net.get(url("/${tmdbType(type)}/$tmdbId/recommendations"))).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).map { parse(results.getJSONObject(it), type) }.filter { it.backdrop != null }
    }

    private val logoCache = HashMap<String, String?>()

    /**
     * Title treatment (logo) for any title: Italian first, then English, then any.
     * Works from the TMDB id or, failing that, the IMDb id. Cached; null if TMDB has none.
     */
    suspend fun logoFor(item: MediaItem): String? {
        item.logo?.let { return it }
        val key = "${item.type}:${item.tmdbId ?: item.imdbId ?: return null}"
        if (logoCache.containsKey(key)) return logoCache[key]
        val id = item.tmdbId ?: run {
            val found = JSONObject(Net.get(url("/find/${item.imdbId}", "&external_source=imdb_id")))
            found.optJSONArray(if (item.type == "movie") "movie_results" else "tv_results")?.optJSONObject(0)?.optInt("id")
        }
        val logo = id?.let {
            val imgs = JSONObject(Net.get("$BASE/${tmdbType(item.type)}/$it/images?api_key=${BuildConfig.TMDB_KEY}&include_image_language=it,en,null"))
            logo(JSONObject().put("images", imgs))
        }
        logoCache[key] = logo
        return logo
    }

    fun poster(d: JSONObject) = d.str("poster_path")?.let { "$IMG/w342$it" }

    private val posterCache = HashMap<String, String?>()

    /** Vertical poster for an IMDb id (one cheap /find call, cached). */
    suspend fun posterByImdb(imdbId: String, type: String): String? {
        posterCache[imdbId]?.let { return it }
        val found = JSONObject(Net.get(url("/find/$imdbId", "&external_source=imdb_id")))
        val list = found.optJSONArray(if (type == "movie") "movie_results" else "tv_results")
        val p = list?.optJSONObject(0)?.str("poster_path")?.let { "$IMG/w342$it" }
        posterCache[imdbId] = p
        return p
    }

    fun backdrop(d: JSONObject, size: String = "w1280") = d.str("backdrop_path")?.let { "$IMG/$size$it" }

    fun logo(d: JSONObject): String? {
        val logos = d.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val all = (0 until logos.length()).map { logos.getJSONObject(it) }
        val best = all.firstOrNull { it.optString("iso_639_1") == "it" } ?: all.firstOrNull()
        return best?.str("file_path")?.let { "$IMG/w500$it" }
    }
}

/** The "Top X Italia" Stremio addon that Nuvio uses. */
object TopX {
    val services = listOf(
        "netflix" to "Netflix",
        "amazon" to "Prime Video",
        "disney" to "Disney+",
        "hbo" to "HBO Max",
        "apple" to "Apple TV+",
        "now" to "NOW",
        "paramount" to "Paramount+",
    )

    suspend fun catalog(type: String, service: String): List<MediaItem> {
        val json = JSONObject(Net.get("${BuildConfig.TOPX_BASE}/catalog/$type/tx.$type.${service}_topx.json"))
        val metas = json.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).map { i ->
            val m = metas.getJSONObject(i)
            MediaItem(
                title = m.optString("name"),
                type = type,
                imdbId = m.str("id")?.takeIf { it.startsWith("tt") },
                image = m.str("landscapePoster") ?: m.str("poster"),
                rank = i + 1,
                backdrop = m.str("background"),
                logo = m.str("logo"),
                overview = m.str("description"),
                year = m.str("releaseInfo"),
                rating = m.str("imdbRating"),
                artworkHasTitle = m.has("landscapePoster"),
            )
        }
    }
}
