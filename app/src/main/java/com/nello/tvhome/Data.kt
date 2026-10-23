package com.nello.tvhome

import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppInfo(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
)

data class MediaItem(
    val title: String,
    /** "movie" or "series", as Nuvio expects it in nuvio://detail/<type>/<imdb> */
    val type: String,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val image: String? = null,
    /** vertical 2:3 poster, for the collapsed cards of the carousel */
    val poster: String? = null,
    /** position in a Top 10, drawn big on the card */
    val rank: Int? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: String? = null,
    /** short text in the top-right corner of the card, e.g. "S2 E1" */
    val badge: String? = null,
    /** 0..1, shows a progress bar when set */
    val progress: Float? = null,
    /** the image already carries the title (Top X artwork), so no label on the card */
    val artworkHasTitle: Boolean = false,
    /** a Nuvio/addon id that isn't an IMDb id, opened directly on its Nuvio page */
    val contentId: String? = null,
    /** for "continue watching": the exact video to resume ("tt…" or "tt…:season:episode") */
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val action: Action = Action.OPEN,
) {
    enum class Action { OPEN, LINK_NUVIO }
}

data class Match(
    val id: String,
    val league: League,
    val home: String,
    val away: String,
    val homeFull: String,
    val awayFull: String,
    val homeLogo: String?,
    val awayLogo: String?,
    val homeScore: String?,
    val awayScore: String?,
    /** "pre", "in" or "post" */
    val state: String,
    /** e.g. "67'", "HT", "FT" */
    val detail: String,
    val startMillis: Long,
    val venue: String?,
)

enum class League(val espn: String, val label: String) {
    SERIE_A("ita.1", "Serie A"),
    UCL("uefa.champions", "Champions League"),
    UEL("uefa.europa", "Europa League"),
    UECL("uefa.europa.conf", "Conference League"),
    COPPA("ita.coppa_italia", "Coppa Italia"),
    SUPERCOPPA("ita.super_cup", "Supercoppa"),
}

/** A Formula 1 session (race, qualifying, sprint) for "Per te". */
data class RaceSession(
    val grandPrix: String,
    /** "Gara", "Qualifiche", "Sprint", "Qualifiche Sprint" */
    val session: String,
    /** English session name used by the sports addon ("Race", "Qualifying", "Sprint") */
    val sessionEn: String,
    val startMillis: Long,
    val state: String,
)

enum class RowKind { APPS, LANDSCAPE, POSTER, MATCH, MIXED, DOCK }

class Row(
    val key: String,
    val title: String,
    val kind: RowKind,
    var media: List<MediaItem> = emptyList(),
    var apps: List<AppInfo> = emptyList(),
    var matches: List<Match> = emptyList(),
    /** MIXED rows ("Per te"): MediaItem, Match and AppInfo together */
    var items: List<Any> = emptyList(),
) {
    val size
        get() = when (kind) {
            RowKind.APPS, RowKind.DOCK -> apps.size
            RowKind.MIXED -> items.size
            RowKind.MATCH -> matches.size
            else -> media.size
        }
}

class HttpException(val code: Int, body: String) : Exception("HTTP $code: ${body.take(200)}")

object Net {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, null, headers)

    suspend fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, body.toString(), headers + ("Content-Type" to "application/json"))

    private suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "NelloTV/1.0")
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw HttpException(code, text)
                text
            } finally {
                conn.disconnect()
            }
        }
}

fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
