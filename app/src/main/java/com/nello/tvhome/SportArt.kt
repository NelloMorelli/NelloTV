package com.nello.tvhome

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Event artwork from TheSportsDB (free key): the 16:9 matchup graphic for football,
 * the round/circuit graphic for F1 sessions. Found once per event, then remembered on disk.
 */
object SportArt {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123"
    private const val F1 = "4370"
    private val LEAGUES = mapOf(
        League.SERIE_A to "4332",
        League.UCL to "4480",
        League.UEL to "4481",
        League.UECL to "5071",
        League.COPPA to "4506",
        League.SUPERCOPPA to "4507",
    )

    private var prefs: android.content.SharedPreferences? = null
    private val day = HashMap<String, JSONArray>()
    // the free key allows ~30 requests a minute: one at a time
    private val lock = Mutex()

    fun init(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences("sport_art_v3", Context.MODE_PRIVATE)
    }

    private fun utcDay(millis: Long) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    /** set when a request failed (rate limit, offline): then nothing is remembered and it's retried later */
    private var failed = false
    private var pausedUntil = 0L

    private suspend fun get(path: String): JSONObject? {
        if (System.currentTimeMillis() < pausedUntil) { failed = true; return null }
        return runCatching { JSONObject(Net.get("$API/$path", mapOf("User-Agent" to "Mozilla/5.0"))) }
            .onFailure {
                failed = true
                // rate limited: leave the API alone for a couple of minutes
                if (it is HttpException && (it.code == 429 || it.code == 403)) pausedUntil = System.currentTimeMillis() + 120_000
            }
            .getOrNull()
    }

    private suspend fun events(date: String, league: String): JSONArray {
        val key = "$date/$league"
        day[key]?.let { return it }
        val o = get("eventsday.php?d=$date&l=$league") ?: return JSONArray()
        val list = o.optJSONArray("events") ?: JSONArray()
        day[key] = list
        return list
    }

    private suspend fun search(name: String): JSONArray {
        val q = URLEncoder.encode(name.replace(' ', '_'), "UTF-8")
        return get("searchevents.php?e=$q")?.optJSONArray("event") ?: JSONArray()
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    /**
     * Remembered result: url, "" for "looked recently, nothing there", null for "look it up".
     * "Nothing" expires after 6 hours, since artwork is often added close to the event.
     */
    private fun cached(key: String): String? {
        val v = prefs?.getString(key, null) ?: return null
        if (!v.startsWith("-")) return v
        val at = v.drop(1).toLongOrNull() ?: 0
        return if (System.currentTimeMillis() - at < 6 * 3600_000L) "" else null
    }

    private fun remember(key: String, url: String?) =
        prefs?.edit()?.putString(key, url ?: "-${System.currentTimeMillis()}")?.apply()

    suspend fun forMatch(m: Match): String? {
        val key = "m:${m.id}"
        cached(key)?.let { return it.ifEmpty { null } }
        return lock.withLock {
            cached(key)?.let { return@withLock it.ifEmpty { null } }
            failed = false
            val date = utcDay(m.startMillis)
            val home = TeamNames.tokens(m.home + " " + m.homeFull)
            val away = TeamNames.tokens(m.away + " " + m.awayFull)
            fun pick(list: JSONArray) = list.objects().firstOrNull { e ->
                e.optString("dateEvent") == date &&
                    TeamNames.overlaps(TeamNames.tokens(e.optString("strHomeTeam")), home) &&
                    TeamNames.overlaps(TeamNames.tokens(e.optString("strAwayTeam")), away)
            }?.str("strThumb")
            val url = LEAGUES[m.league]?.let { pick(events(date, it)) }
                ?: pick(search("${m.homeFull} vs ${m.awayFull}"))
                ?: pick(search("${m.home} vs ${m.away}"))
            if (url != null || !failed) remember(key, url)
            url
        }
    }

    suspend fun forRace(r: RaceSession): String? {
        val key = "f1:${r.grandPrix}:${r.sessionEn}:${utcDay(r.startMillis)}"
        cached(key)?.let { return it.ifEmpty { null } }
        return lock.withLock {
            cached(key)?.let { return@withLock it.ifEmpty { null } }
            failed = false
            val date = utcDay(r.startMillis)
            val wanted = "${r.grandPrix} ${r.sessionEn}".lowercase()
            fun pick(list: JSONArray) = list.objects().firstOrNull { e ->
                val n = e.optString("strEvent").lowercase()
                // "United States Grand Prix Practice 1", "… Qualifying", "… Race"
                n == wanted || (r.sessionEn == "Race" && n == r.grandPrix.lowercase())
            }?.str("strThumb")
            val url = pick(events(date, F1)) ?: pick(search("${r.grandPrix} ${r.sessionEn}"))
            if (url != null || !failed) remember(key, url)
            url
        }
    }
}
