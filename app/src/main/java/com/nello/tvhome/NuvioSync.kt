package com.nello.tvhome

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reads "continue watching" and the library from Nuvio Sync (Nuvio's Supabase backend at api.nuvio.tv).
 * The launcher signs in like a second Nuvio TV: QR code → approve on the phone → session tokens.
 */
class NuvioSync(context: Context) {
    private val prefs = context.getSharedPreferences("nuvio_sync", Context.MODE_PRIVATE)
    private val api = "https://api.nuvio.tv"

    /** Nuvio profile slot to read; 1 is the primary profile ("Nello"). */
    var profileIndex: Int
        get() = prefs.getInt("profile", 1)
        set(value) = prefs.edit().putInt("profile", value).apply()

    val isConfigured get() = BuildConfig.NUVIO_KEY.isNotBlank()
    val isLinked get() = prefs.getString("refresh", null) != null

    class LoginSession(val code: String, val nonce: String, val webUrl: String, val interval: Int)

    private fun headers(bearer: String = BuildConfig.NUVIO_KEY) = mapOf(
        "apikey" to BuildConfig.NUVIO_KEY,
        "Authorization" to "Bearer $bearer",
    )

    suspend fun startLogin(): LoginSession {
        val nonce = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("p_device_nonce", nonce)
            .put("p_redirect_base_url", "https://nuvio.tv/tv-login")
            .put("p_device_name", "Nello TV")
        val row = JSONArray(Net.postJson("$api/rest/v1/rpc/start_tv_login_session", body, headers())).getJSONObject(0)
        return LoginSession(row.getString("code"), nonce, row.getString("web_url"), row.optInt("poll_interval_seconds", 3))
    }

    /** "pending", "approved", "expired"… ; on approval the session is exchanged and stored. */
    suspend fun pollLogin(s: LoginSession): String {
        val body = JSONObject().put("p_code", s.code).put("p_device_nonce", s.nonce)
        val status = JSONArray(Net.postJson("$api/rest/v1/rpc/poll_tv_login_session", body, headers()))
            .optJSONObject(0)?.optString("status").orEmpty()
        if (status == "approved") {
            val tokens = JSONObject(Net.postJson(
                "$api/functions/v1/tv-logins-exchange",
                JSONObject().put("code", s.code).put("device_nonce", s.nonce),
                headers(),
            ))
            saveTokens(tokens)
        }
        return status
    }

    fun unlink() = prefs.edit().remove("access").remove("refresh").remove("expires").apply()

    private fun saveTokens(o: JSONObject) {
        val expiresIn = o.optLong("expires_in", o.optLong("expiresIn", 3600))
        prefs.edit()
            .putString("access", o.optString("access_token", o.optString("accessToken")))
            .putString("refresh", o.optString("refresh_token", o.optString("refreshToken")))
            .putLong("expires", System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    private suspend fun accessToken(): String {
        if (System.currentTimeMillis() > prefs.getLong("expires", 0) - 60_000) {
            try {
                val o = JSONObject(Net.postJson(
                    "$api/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", prefs.getString("refresh", "")),
                    mapOf("apikey" to BuildConfig.NUVIO_KEY),
                ))
                saveTokens(o)
            } catch (e: HttpException) {
                if (e.code == 400 || e.code == 401) unlink()
                throw e
            }
        }
        return prefs.getString("access", "")!!
    }

    private suspend fun rpc(name: String): JSONArray {
        val body = JSONObject().put("p_profile_id", profileIndex)
        return JSONArray(Net.postJson("$api/rest/v1/rpc/$name", body, headers(accessToken())))
    }

    suspend fun continueWatching(): List<MediaItem> = coroutineScope {
        val rows = rpc("sync_pull_watch_progress")
        val entries = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter {
                val d = it.optDouble("duration", 0.0)
                d > 0 && it.optDouble("position", 0.0) / d in 0.02..0.95
            }
            .sortedByDescending { it.optLong("last_watched") }
            .distinctBy { contentId(it) }
            .take(15)
        // series with a new episode first
        entries.map { e -> async { runCatching { progressItem(e) }.getOrNull() } }.awaitAll().filterNotNull()
            .sortedByDescending { it.newEpisode }
    }

    suspend fun library(): List<MediaItem> = coroutineScope {
        val rows = rpc("sync_pull_library")
        val entries = (0 until rows.length()).map { rows.getJSONObject(it) }
            .sortedByDescending { it.optString("added_at") }
            .take(30)
        entries.map { e -> async { runCatching { libraryItem(e) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** "tt0903747" for a series episode stored as video_id "tt0903747:2:1". */
    private fun contentId(o: JSONObject): String =
        o.str("content_id") ?: o.str("video_id")?.substringBefore(':') ?: o.optString("progress_key")

    private fun nuvioType(o: JSONObject) =
        if (o.optString("content_type") in listOf("series", "tv", "show")) "series" else "movie"

    private suspend fun progressItem(e: JSONObject): MediaItem? {
        val id = contentId(e)
        val type = nuvioType(e)
        val tmdb = Tmdb.findByImdb(id, type)
        val season = e.optInt("season", 0)
        val episode = e.optInt("episode", 0)
        // a newer episode than the one you're on came out in the last 14 days
        val last = tmdb?.optJSONObject("last_episode_to_air")
        val lastS = last?.optInt("season_number") ?: 0
        val lastE = last?.optInt("episode_number") ?: 0
        val aired = last?.str("air_date")?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it)!!.time }.getOrNull() }
        val isNew = type == "series" && season > 0 && aired != null &&
            System.currentTimeMillis() - aired < 14 * 86_400_000L &&
            (lastS > season || (lastS == season && lastE > episode))
        val badge = when {
            isNew -> "Nuovo · S$lastS E$lastE"
            type == "series" && season > 0 -> "S$season E$episode"
            else -> null
        }
        val left = ((e.optDouble("duration") - e.optDouble("position")) / 60_000).toInt()
        return MediaItem(
            title = tmdb?.let { it.str("title") ?: it.str("name") } ?: e.str("name") ?: id,
            type = type,
            imdbId = id.takeIf { it.startsWith("tt") },
            tmdbId = tmdb?.optInt("id"),
            image = tmdb?.let { Tmdb.backdrop(it, "w780") },
            poster = tmdb?.let { Tmdb.poster(it) },
            backdrop = tmdb?.let { Tmdb.backdrop(it) },
            logo = tmdb?.let { Tmdb.logo(it) },
            overview = tmdb?.str("overview"),
            year = (tmdb?.str("release_date") ?: tmdb?.str("first_air_date"))?.take(4),
            badge = badge ?: if (left > 0) "${left}m rimanenti" else null,
            progress = (e.optDouble("position") / e.optDouble("duration")).toFloat(),
            videoId = e.str("video_id") ?: id,
            season = season.takeIf { it > 0 },
            episode = episode.takeIf { it > 0 },
            newEpisode = isNew,
        )
    }

    private suspend fun libraryItem(e: JSONObject): MediaItem {
        val id = e.str("content_id") ?: e.str("id") ?: ""
        val type = nuvioType(e)
        val tmdb = if (id.startsWith("tt")) Tmdb.findByImdb(id, type) else null
        return MediaItem(
            title = tmdb?.let { it.str("title") ?: it.str("name") } ?: e.str("name") ?: e.str("title") ?: id,
            type = type,
            imdbId = id.takeIf { it.startsWith("tt") },
            tmdbId = tmdb?.optInt("id"),
            contentId = id.takeIf { !it.startsWith("tt") && it.isNotBlank() },
            image = tmdb?.let { Tmdb.backdrop(it, "w780") } ?: e.str("background") ?: e.str("poster"),
            backdrop = tmdb?.let { Tmdb.backdrop(it) } ?: e.str("background"),
            logo = tmdb?.let { Tmdb.logo(it) },
            overview = tmdb?.str("overview") ?: e.str("description"),
            year = e.str("release_info"),
            rating = e.str("imdb_rating"),
        )
    }
}
