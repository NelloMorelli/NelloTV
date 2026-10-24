package com.nello.tvhome

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** One headline from a feed. */
data class Headline(val title: String, val source: String, val time: Long)

/**
 * RSS/Atom headlines for the screensaver. A few Italian feeds to choose from, plus any address
 * you add; downloaded at most every 20 minutes (Net's cache).
 */
object News {
    val PRESETS = listOf(
        "ANSA" to "https://www.ansa.it/sito/ansait_rss.xml",
        "ANSA Sport" to "https://www.ansa.it/sito/notizie/sport/sport_rss.xml",
        "Sky TG24" to "https://tg24.sky.it/rss/tg24.xml",
        "Gazzetta" to "https://www.gazzetta.it/rss/home.xml",
        "Corriere" to "https://xml2.corriereobjects.it/rss/homepage.xml",
        "Repubblica" to "https://www.repubblica.it/rss/homepage/rss2.0.xml",
        "TuttoNapoli" to "https://www.tuttonapoli.net/rss/",
    )
    private val DEFAULT = setOf("ANSA", "TuttoNapoli")

    private fun prefs(c: Context) = c.getSharedPreferences("theme", Context.MODE_PRIVATE)

    /** Names of the chosen feeds (presets) and custom addresses. */
    fun chosen(c: Context): Set<String> = prefs(c).getStringSet("newsFeeds", DEFAULT)!!
    fun setChosen(c: Context, v: Set<String>) = prefs(c).edit().putStringSet("newsFeeds", v).apply()

    private fun urlOf(entry: String) = PRESETS.firstOrNull { it.first == entry }?.second ?: entry
    private fun nameOf(entry: String) = PRESETS.firstOrNull { it.first == entry }?.first
        ?: entry.substringAfter("://").substringBefore('/').removePrefix("www.")

    /** The latest headlines from all chosen feeds, newest first. */
    suspend fun headlines(c: Context, max: Int = 12): List<Headline> = withContext(Dispatchers.IO) {
        chosen(c).flatMap { e -> runCatching { parse(Net.get(urlOf(e)), nameOf(e)) }.getOrElse { emptyList() } }
            .sortedByDescending { it.time }
            .distinctBy { it.title.lowercase() }
            .take(max)
    }

    private val dates = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .map { java.text.SimpleDateFormat(it, java.util.Locale.ENGLISH) }

    private fun date(s: String?): Long {
        val t = s?.trim() ?: return 0
        for (f in dates) runCatching { return f.parse(t)!!.time }
        return 0
    }

    /** RSS <item> or Atom <entry>: title and date. */
    fun parse(xml: String, source: String): List<Headline> {
        val out = ArrayList<Headline>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var inItem = false
        var title: String? = null
        var time: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "item", "entry" -> { inItem = true; title = null; time = null }
                    "title" -> if (inItem) title = p.nextText()
                    "pubDate", "published", "updated", "dc:date" -> if (inItem && time == null) time = p.nextText()
                }
                XmlPullParser.END_TAG -> if (p.name == "item" || p.name == "entry") {
                    inItem = false
                    val t = title?.replace(Regex("<[^>]+>"), "")?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#039;", "'")?.trim()
                    if (!t.isNullOrBlank()) out += Headline(t, source, date(time))
                }
            }
        }
        return out
    }
}
