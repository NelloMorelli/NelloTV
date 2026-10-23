package com.nello.tvhome

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sport pages in the launcher's style (ESPN data):
 * Serie A table, F1 drivers/constructors, Napoli's calendar, and a match's detail.
 */
class SportPagesActivity : GlassPage() {

    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_EVENT = "event"
        const val EXTRA_LEAGUE = "league"
        const val SERIE_A = "seriea"
        const val F1_DRIVERS = "f1drivers"
        const val F1_TEAMS = "f1teams"
        const val NAPOLI = "napoli"
        const val MATCH = "match"
        private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val V2 = "https://site.api.espn.com/apis/v2/sports"
    }

    private val rome = TimeZone.getTimeZone("Europe/Rome")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loading = TextView(this).apply {
            text = "Caricamento…"
            textSize = 14f
            setTextColor(getColor(R.color.text_dim))
        }
        lifecycleScope.launch {
            when (intent.getStringExtra(EXTRA_PAGE)) {
                SERIE_A -> { title("Classifica Serie A"); list.addView(loading); serieA() }
                F1_DRIVERS -> { title("Formula 1 · Piloti"); list.addView(loading); f1(drivers = true) }
                F1_TEAMS -> { title("Formula 1 · Costruttori"); list.addView(loading); f1(drivers = false) }
                NAPOLI -> { title("Calendario ${LauncherPrefs(this@SportPagesActivity).teamName.orEmpty()}"); list.addView(loading); napoli() }
                MATCH -> { title("Partita"); list.addView(loading); match() }
            }
            list.removeView(loading)
            // something focusable so Back and the D-pad behave
            (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- rows

    /** A table row: fixed-width columns, focusable so the D-pad scrolls the page. */
    private fun tableRow(cols: List<String>, widths: List<Int>, highlight: Boolean = false, header: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            if (!header) {
                isFocusable = true
                // the page's liquid pill marks the focused row
            }
        }
        cols.forEachIndexed { i, c ->
            row.addView(TextView(this).apply {
                text = c
                textSize = if (header) 11f else 14f
                maxLines = 1
                isDuplicateParentStateEnabled = true
                // first two columns (position/date, name) left-aligned, numbers right-aligned
                gravity = if (i <= 1) Gravity.START else Gravity.END
                if (i == 0) setPadding(0, 0, dp(12), 0)
                if (header) setTextColor(getColor(R.color.text_faint))
                else setTextColor(0xF2FFFFFF.toInt())
                if (highlight) typeface = Fonts.semiBold
            }, if (widths[i] == 0) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(dp(widths[i]), -2))
        }
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
    }

    private fun note(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(getColor(R.color.text_dim))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isFocusable = true
    })

    private fun stat(o: JSONObject, name: String): String {
        val stats = o.optJSONArray("stats") ?: return ""
        for (i in 0 until stats.length()) {
            val s = stats.getJSONObject(i)
            if (s.optString("name") == name) return s.optString("displayValue")
        }
        return ""
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    // ---------------------------------------------------------------- pages

    private suspend fun serieA() {
        val d = runCatching { JSONObject(Net.get("$V2/soccer/ita.1/standings")) }.getOrNull()
            ?: return note("Classifica non disponibile")
        val entries = d.getJSONArray("children").getJSONObject(0).getJSONObject("standings").getJSONArray("entries").objects()
            .sortedBy { stat(it, "rank").toIntOrNull() ?: 99 }
        val w = listOf(36, 0, 44, 44, 44, 44, 56, 56)
        tableRow(listOf("#", "Squadra", "G", "V", "N", "P", "DR", "PT"), w, header = true)
        entries.forEach { e ->
            val team = e.getJSONObject("team").optString("displayName")
            tableRow(
                listOf(stat(e, "rank"), team, stat(e, "gamesPlayed"), stat(e, "wins"), stat(e, "ties"), stat(e, "losses"),
                    stat(e, "pointDifferential"), stat(e, "points")),
                w, highlight = myTeam?.let { team.contains(it) } == true,
            )
        }
    }

    private val myTeam get() = LauncherPrefs(this).teamName

    private suspend fun f1(drivers: Boolean) {
        val d = runCatching { JSONObject(Net.get("$V2/racing/f1/standings")) }.getOrNull()
            ?: return note("Classifica non disponibile")
        val group = d.getJSONArray("children").objects().firstOrNull {
            it.optString("name").contains(if (drivers) "Driver" else "Constructor")
        } ?: return note("Classifica non disponibile")
        val entries = group.getJSONObject("standings").getJSONArray("entries").objects()
            .sortedBy { stat(it, "rank").toIntOrNull() ?: 99 }
        val w = listOf(36, 0, 80)
        tableRow(listOf("#", if (drivers) "Pilota" else "Squadra", "Punti"), w, header = true)
        entries.forEach { e ->
            val name = (e.optJSONObject("athlete") ?: e.optJSONObject("team"))?.optString("displayName").orEmpty()
            val pts = stat(e, "championshipPts").ifEmpty { stat(e, "points") }
            val f1 = LauncherPrefs(this).f1Team
            val ferrari = f1 != null && (name.contains(f1) || (f1 == "Ferrari" && name in setOf("Charles Leclerc", "Lewis Hamilton")))
            tableRow(listOf(stat(e, "rank"), name, pts), w, highlight = ferrari)
        }
    }

    private suspend fun napoli() {
        val leagues = listOf("ita.1" to "Serie A", "uefa.champions" to "Champions", "uefa.europa" to "Europa League",
            "ita.coppa_italia" to "Coppa Italia", "ita.super_cup" to "Supercoppa")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY).apply { timeZone = rome }
        val all = kotlinx.coroutines.coroutineScope {
            leagues.map { (code, label) ->
                async {
                    runCatching {
                        JSONObject(Net.get("$SITE/soccer/$code/teams/${LauncherPrefs(this@SportPagesActivity).teamId}/schedule?fixture=true")).optJSONArray("events")?.objects().orEmpty()
                            .map { Triple(it, label, iso.parse(it.getString("date"))!!.time) }
                    }.getOrElse { emptyList() }
                }
            }.awaitAll().flatten()
        }.filter { it.third > System.currentTimeMillis() - 3 * 3600_000L }
            .distinctBy { it.first.optString("id") }
            .sortedBy { it.third }
            .take(20)
        if (all.isEmpty()) return note("Nessuna partita in programma")
        val w = listOf(170, 0, 120)
        tableRow(listOf("Data", "Partita", "Competizione"), w, header = true)
        all.forEach { (e, label, start) ->
            val name = e.optString("name").let { n ->
                // ESPN: "AS Roma at Napoli" → "Napoli – AS Roma"
                if (" at " in n) n.substringAfter(" at ") + " – " + n.substringBefore(" at ") else n
            }
            tableRow(listOf(day.format(start).replaceFirstChar { it.uppercase() }, name, label), w)
        }
    }

    private suspend fun match() {
        val id = intent.getStringExtra(EXTRA_EVENT) ?: return note("Partita non trovata")
        val league = intent.getStringExtra(EXTRA_LEAGUE) ?: "ita.1"
        val d = runCatching { JSONObject(Net.get("$SITE/soccer/$league/summary?event=$id")) }.getOrNull()
            ?: return note("Dettagli non disponibili")
        // header: teams and score
        val comp = d.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        val sides = comp?.optJSONArray("competitors")?.objects().orEmpty()
        val home = sides.firstOrNull { it.optString("homeAway") == "home" }
        val away = sides.firstOrNull { it.optString("homeAway") == "away" }
        val status = comp?.optJSONObject("status")?.optJSONObject("type")
        (list.getChildAt(0) as? TextView)?.text = listOfNotNull(
            home?.optJSONObject("team")?.optString("displayName"),
            away?.optJSONObject("team")?.optString("displayName"),
        ).joinToString(" – ")
        val state = status?.optString("state")
        info("Risultato", if (state == "pre") "Non iniziata" else "${home?.optString("score") ?: 0} - ${away?.optString("score") ?: 0}")
        val kickoff = comp?.optString("date")?.let {
            runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)!!.time }.getOrNull()
        }
        val when_ = kickoff?.let { SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.ITALY).apply { timeZone = rome }.format(it).replaceFirstChar { c -> c.uppercase() } }
        info("Stato", when (state) {
            "pre" -> "Calcio d'inizio ${when_ ?: ""}".trim()
            "post" -> "Finale"
            else -> "In corso · ${status?.optString("shortDetail").orEmpty()}"
        })
        d.optJSONObject("gameInfo")?.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotBlank() }?.let { info("Stadio", it) }

        // goals and cards
        val events = d.optJSONArray("keyEvents")?.objects().orEmpty().filter {
            val t = it.optJSONObject("type")?.optString("text").orEmpty()
            t.contains("Goal") || t.contains("Card") || t.contains("Penalty")
        }
        header("Eventi")
        if (events.isEmpty()) note(if (state == "pre") "La partita non è ancora iniziata" else "Nessun gol o cartellino")
        events.forEach { e ->
            val t = e.optJSONObject("type")?.optString("text").orEmpty()
            val icon = when {
                t.contains("Red") -> "🟥"
                t.contains("Yellow") -> "🟨"
                else -> "⚽"
            }
            val who = e.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")?.optString("displayName")
                ?: e.optString("text").substringBefore(" (").substringAfterLast(". ")
            val team = e.optJSONObject("team")?.optString("displayName").orEmpty()
            tableRow(listOf(e.optJSONObject("clock")?.optString("displayValue").orEmpty(), "$icon  $who", team), listOf(56, 0, 180))
        }

        // starting XIs
        var lineups = 0
        d.optJSONArray("rosters")?.objects().orEmpty().forEach { r ->
            val starters = r.optJSONArray("roster")?.objects().orEmpty().filter { it.optBoolean("starter") }
            if (starters.isEmpty()) return@forEach
            lineups++
            header("${r.optJSONObject("team")?.optString("displayName")} · ${r.optString("formation")}")
            starters.forEach { p ->
                tableRow(listOf(p.optString("jersey"), p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                    p.optJSONObject("position")?.optString("abbreviation").orEmpty()), listOf(44, 0, 60))
            }
        }
        if (lineups == 0) {
            header("Formazioni")
            note("Le formazioni escono circa un'ora prima del calcio d'inizio")
        }
    }
}
