package com.nello.tvhome

import android.content.Context

/** What the "Schermata" settings switch on and off: tabs and catalogues. */
object Customize {
    /** catalogues that can be switched off, by tab */
    val CATALOGS = linkedMapOf(
        "Film" to listOf("movie_popular" to "Popolari ora", "movie_cinema" to "Al cinema", "movie_upcoming" to "In arrivo") +
            TopX.services.map { (id, n) -> "movie_tx_$id" to "Top 10 su $n" },
        "Serie TV" to listOf("series_trend" to "Di tendenza", "series_popular" to "Popolari ora", "series_onair" to "Nuovi episodi") +
            TopX.services.map { (id, n) -> "series_tx_$id" to "Top 10 su $n" },
        "Sport" to listOf("sport_today" to "Oggi", "sport_next" to "Prossimi eventi", "sport_f1" to "Formula 1", "sport_done" to "Risultati"),
        "TV" to listOf("tv_now" to "In onda ora", "tv_prime" to "Stasera", "tv_sky_cinema" to "Sky Cinema", "tv_sky_ent" to "Sky Intrattenimento", "tv_sky_prime" to "Sky · stasera"),
    )
    val TABS = listOf("SEARCH" to "Cerca", "MOVIES" to "Film", "SERIES" to "Serie TV", "TV" to "TV", "SPORT" to "Sport")

    fun hiddenCatalogs(c: Context): Set<String> = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getStringSet("hiddenCatalogs", emptySet())!!
    fun hiddenTabs(c: Context): Set<String> = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getStringSet("hiddenTabs", emptySet())!!
}
