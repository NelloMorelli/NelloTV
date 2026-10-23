package com.nello.tvhome

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Fixtures from ESPN's public scoreboard: Serie A, plus Italian clubs in the three UEFA cups. */
object Espn {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private val rome = TimeZone.getTimeZone("Europe/Rome")
    private var italianTeams: Set<String>? = null

    private suspend fun italianTeamIds(): Set<String> = italianTeams ?: run {
        val o = JSONObject(Net.get("$BASE/ita.1/teams"))
        val teams = o.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
        (0 until teams.length()).map { teams.getJSONObject(it).getJSONObject("team").getString("id") }.toSet()
            .also { italianTeams = it }
    }

    private fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = rome }.format(millis)

    /** Matches whose kickoff falls on [dayOffset] days from today, Rome time. */
    suspend fun matchesOn(dayOffset: Int): List<Match> = coroutineScope {
        val cal = Calendar.getInstance(rome).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val target = dayKey(cal.timeInMillis)
        // ESPN groups by US date, so evening kickoffs in Italy can sit on a neighbouring day: ask for both sides
        val queryDays = listOf(-1, 0, 1).map { d ->
            dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis)
        }
        val italian = italianTeamIds()
        listOf(League.SERIE_A, League.UCL, League.UEL, League.UECL).flatMap { league -> queryDays.map { day -> league to day } }
            .map { (league, day) ->
                async { runCatching { scoreboard(league, day) }.getOrElse { Log.w("Espn", "${league.espn} $day", it); emptyList() } }
            }
            .awaitAll().flatten()
            .distinctBy { it.first.id }
            .filter { (m, teamIds) ->
                dayKey(m.startMillis) == target && (m.league == League.SERIE_A || teamIds.any { it in italian })
            }
            .map { it.first }
            .sortedWith(compareBy({ stateOrder(it.state) }, { it.startMillis }))
    }

    private const val NAPOLI = "114"

    /** Napoli's matches today (Rome time), in every competition. */
    suspend fun napoliToday(): List<Match> = coroutineScope {
        val cal = Calendar.getInstance(rome)
        val target = dayKey(cal.timeInMillis)
        val queryDays = listOf(-1, 0, 1).map { d ->
            dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis)
        }
        League.entries.flatMap { league -> queryDays.map { day -> league to day } }
            .map { (league, day) -> async { runCatching { scoreboard(league, day) }.getOrElse { emptyList() } } }
            .awaitAll().flatten()
            .distinctBy { it.first.id }
            .filter { (m, teamIds) -> dayKey(m.startMillis) == target && NAPOLI in teamIds }
            .map { it.first }
            .sortedBy { it.startMillis }
    }

    /** Today's F1 race, qualifying and sprint sessions (Rome time). */
    /** Today's F1 race, qualifying and sprint sessions (Rome time) — for "Per te". */
    suspend fun f1Today(): List<RaceSession> = f1Sessions(0..0, practice = false)

    /**
     * F1 sessions whose start falls [days] days from today (Rome time).
     * With [practice] also free practice 1-2-3.
     */
    suspend fun f1Sessions(days: IntRange, practice: Boolean): List<RaceSession> {
        val cal = Calendar.getInstance(rome)
        val wanted = days.map { d -> dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis) }.toSet()
        val from = dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days.first - 1) }.timeInMillis)
        val to = dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days.last + 1) }.timeInMillis)
        val o = JSONObject(Net.get("https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard?dates=$from-$to"))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val events = o.optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<RaceSession>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            // "MSC Cruises United States Grand Prix" → "United States Grand Prix" (drop the sponsor words before the country)
            val full = e.optString("name")
            val gp = Regex("((?:[A-Z][a-zà-ü]+ ){1,2}Grand Prix)").find(full)?.value ?: full
            val comps = e.optJSONArray("competitions") ?: continue
            for (j in 0 until comps.length()) {
                val c = comps.getJSONObject(j)
                val abbr = c.optJSONObject("type")?.optString("abbreviation").orEmpty()
                val (it, en) = when (abbr.lowercase()) {
                    "race" -> "Gara" to "Race"
                    "qual" -> "Qualifiche" to "Qualifying"
                    "sprint", "sr" -> "Sprint" to "Sprint"
                    "sq", "ss", "sprint qual" -> "Qualifiche Sprint" to "Sprint Qualifying"
                    "fp1", "fp2", "fp3" -> if (practice) "Libere ${abbr.last()}" to "Practice ${abbr.last()}" else continue
                    else -> continue
                }
                val start = runCatching { iso.parse(c.getString("date"))!!.time }.getOrNull() ?: continue
                if (dayKey(start) !in wanted) continue
                val state = c.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
                out += RaceSession(gp, it, en, start, state)
            }
        }
        return out.sortedBy { it.startMillis }
    }

    fun kickoff(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = rome }.format(millis)

    private fun stateOrder(state: String) = when (state) {
        "in" -> 0
        "pre" -> 1
        else -> 2
    }

    private suspend fun scoreboard(league: League, day: String): List<Pair<Match, List<String>>> {
        val o = JSONObject(Net.get("$BASE/${league.espn}/scoreboard?dates=$day"))
        val events = o.optJSONArray("events") ?: return emptyList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return (0 until events.length()).mapNotNull { i ->
            val e = events.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val cs = comp.getJSONArray("competitors")
            val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
            val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: sides.getOrNull(0) ?: return@mapNotNull null
            val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: sides.getOrNull(1) ?: return@mapNotNull null
            val status = e.getJSONObject("status").getJSONObject("type")
            val start = runCatching { iso.parse(e.getString("date"))!!.time }.getOrNull() ?: return@mapNotNull null
            val match = Match(
                id = e.getString("id"),
                league = league,
                home = home.getJSONObject("team").let { it.str("shortDisplayName") ?: it.optString("displayName") },
                away = away.getJSONObject("team").let { it.str("shortDisplayName") ?: it.optString("displayName") },
                homeFull = home.getJSONObject("team").optString("displayName"),
                awayFull = away.getJSONObject("team").optString("displayName"),
                homeLogo = home.getJSONObject("team").str("logo"),
                awayLogo = away.getJSONObject("team").str("logo"),
                homeScore = home.str("score"),
                awayScore = away.str("score"),
                state = status.optString("state", "pre"),
                detail = status.optString("shortDetail"),
                startMillis = start,
                venue = comp.optJSONObject("venue")?.str("fullName"),
            )
            match to sides.map { it.getJSONObject("team").optString("id") }
        }
    }

    fun kickoff(m: Match): String =
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = rome }.format(m.startMillis)

    fun dayLabel(m: Match): String =
        SimpleDateFormat("EEE d MMM", Locale.ITALY).apply { timeZone = rome }.format(m.startMillis)
}

/**
 * Champions League matches shown in Italy by Prime Video (its Wednesday pick), as announced by Prime.
 * Rounds 7–8 and the knockouts are announced later: add them here.
 */
object PrimeVideo {
    const val PKG = "com.amazon.firebat"

    private val schedule = listOf(
        "20260909" to ("Napoli" to "Arsenal"),
        "20261014" to ("Roma" to "Real Madrid"),
        "20261021" to ("Inter" to "Shakhtar"),
        "20261104" to ("Porto" to "Napoli"),
        "20261125" to ("Paris Saint-Germain" to "Roma"),
        "20261209" to ("Borussia Dortmund" to "Inter"),
    )

    fun shows(m: Match): Boolean {
        if (m.league != League.UCL) return false
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Rome") }.format(m.startMillis)
        return schedule.any { (d, teams) ->
            d == day && TeamNames.same(teams.first, m.home + " " + m.homeFull) && TeamNames.same(teams.second, m.away + " " + m.awayFull)
        }
    }
}

/** Loose team-name comparison across sources ("Inter" / "Internazionale", "Stade Rennais" / "Rennes"). */
object TeamNames {
    private val noise = setOf("fc", "ac", "as", "ss", "sc", "cf", "afc", "us", "ssc", "calcio", "club", "de", "the", "and")

    fun tokens(s: String): Set<String> =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.length >= 3 && it !in noise }.toSet()

    // equal words, one a prefix of the other, or the same first 5 letters
    fun overlaps(a: Set<String>, b: Set<String>) = a.any { x ->
        b.any { y -> x == y || (minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x) || x.take(5) == y.take(5))) }
    }

    fun same(a: String, b: String) = overlaps(tokens(a), tokens(b))
}

/**
 * The "Sports Streams" addon installed in Nuvio. Only used to find the addon's id for a match,
 * so the launcher can open that page in Nuvio; streams are left to Nuvio.
 */
object SportsAddon {
    private var cache: Pair<Long, List<String>>? = null

    private suspend fun liveIds(): List<String> {
        cache?.let { (at, ids) -> if (System.currentTimeMillis() - at < 60_000) return ids }
        val metas = JSONObject(Net.get("${BuildConfig.SPORTS_BASE}/catalog/sport/sports_live.json")).optJSONArray("metas")
        val ids = (0 until (metas?.length() ?: 0)).map { metas!!.getJSONObject(it).optString("id") }
            .filter { it.startsWith("streamed:") }
        cache = System.currentTimeMillis() to ids
        return ids
    }

    private const val ALL_SPORTS = "https://sports.highfly.to/eyJvbmx5TGl2ZSI6dHJ1ZX0"
    private var broad: Pair<Long, List<Pair<String, String>>>? = null

    /** Every live item of the addon, any sport: (id, name). */
    private suspend fun liveAll(): List<Pair<String, String>> {
        broad?.let { (at, l) -> if (System.currentTimeMillis() - at < 60_000) return l }
        val metas = JSONObject(Net.get("$ALL_SPORTS/catalog/sport/sports_live.json")).optJSONArray("metas")
        val l = (0 until (metas?.length() ?: 0)).map { metas!!.getJSONObject(it) }
            .filter { it.optString("id").startsWith("streamed:") }
            .map { it.optString("id") to it.optString("name") }
        broad = System.currentTimeMillis() to l
        return l
    }

    /** The addon page of a live F1 session, e.g. "United States Grand Prix - Qualifying". */
    suspend fun findRace(r: RaceSession): String? {
        val country = TeamNames.tokens(r.grandPrix.replace("Grand Prix", ""))
        val session = r.sessionEn.lowercase()
        return liveAll().firstOrNull { (_, name) ->
            val n = name.lowercase()
            val sameKind = if (session.startsWith("practice")) {
                // "Practice 1" also matches "Free Practice 1" / "FP1"
                val num = session.last()
                (n.contains("practice") && n.contains(num)) || n.contains("fp$num")
            } else {
                n.contains(session.substringBefore(' ')) && (session.contains("sprint") == n.contains("sprint")) && !n.contains("practice")
            }
            TeamNames.overlaps(TeamNames.tokens(name), country) && sameKind
        }?.first
    }

    /** Same as [findId] but also outside the user's leagues (Champions, Coppa Italia…). */
    suspend fun findAnyMatch(m: Match): String? = findId(m) ?: run {
        val home = TeamNames.tokens(m.home + " " + m.homeFull)
        val away = TeamNames.tokens(m.away + " " + m.awayFull)
        liveAll().firstOrNull { (id, _) ->
            val sides = id.substringAfter(':').removePrefix("ppv-").replace(Regex("-\\d+$"), "").split("-vs-")
            sides.size == 2 && run {
                val a = TeamNames.tokens(sides[0].replace('-', ' '))
                val b = TeamNames.tokens(sides[1].replace('-', ' '))
                (TeamNames.overlaps(a, home) && TeamNames.overlaps(b, away)) || (TeamNames.overlaps(a, away) && TeamNames.overlaps(b, home))
            }
        }?.first
    }

    /** e.g. "streamed:ppv-juventus-vs-stade-rennais" for Juventus – Rennes, or null if the addon doesn't list it (yet). */
    suspend fun findId(m: Match): String? {
        if (BuildConfig.SPORTS_BASE.isBlank()) return null
        val home = TeamNames.tokens(m.home + " " + m.homeFull)
        val away = TeamNames.tokens(m.away + " " + m.awayFull)
        return liveIds().firstOrNull { id ->
            val sides = id.substringAfter(':').removePrefix("ppv-").replace(Regex("-\\d+$"), "").split("-vs-")
            if (sides.size != 2) return@firstOrNull false
            val a = TeamNames.tokens(sides[0].replace('-', ' '))
            val b = TeamNames.tokens(sides[1].replace('-', ' '))
            (TeamNames.overlaps(a, home) && TeamNames.overlaps(b, away)) || (TeamNames.overlaps(a, away) && TeamNames.overlaps(b, home))
        }
    }
}
