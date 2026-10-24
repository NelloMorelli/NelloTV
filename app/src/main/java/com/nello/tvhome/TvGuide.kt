package com.nello.tvhome

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** An Italian TV channel and the app that plays it. */
data class TvChannel(
    val id: String,
    val name: String,
    val app: String,
    val logo: String? = null,
    /** exact channel name in Sportio, when it plays there */
    val sportio: String? = null,
)

/** One programme in the guide. */
data class TvShow(
    val channel: TvChannel,
    val title: String,
    val start: Long,
    val stop: Long,
    val category: String?,
    /** the programme's own image from the guide (RaiPlay, Mediaset, Sky), if any */
    val image: String? = null,
) {
    val isOn get() = System.currentTimeMillis() in start until stop
    val progress get() = ((System.currentTimeMillis() - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * Italian TV guide from a public XMLTV feed (epgshare01), downloaded at most every 6 hours and
 * kept on disk; only the main free-to-air channels are read.
 */
object TvGuide {
    /** main source (kept up to date, ~10 days ahead) and a fallback for anything it lacks */
    private val FEEDS = listOf(
        "https://iptv-epg.org/files/epg-it.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
    )

    /** "Sky.Sport..251.it", "SkySport251.it", "LA7.HD.it" → "skysport251", "la7": the same channel in any feed */
    private fun norm(id: String): String {
        val n = id.lowercase().replace(Regex("(\\.it)+$"), "").replace("+", "plus")
            .replace(Regex("[^a-z0-9]"), "").replace(Regex("hd$"), "")
        return ALIASES[n] ?: n
    }
    private val ALIASES = mapOf("mediaset20" to "20")
    private const val RAI = "it.rainet.androidcr"
    private const val MEDIASET = "it.mediaset.infinitytv1"
    /** channels without their own app installed: NeroZone (IPTV) */
    private const val IPTV = "iptv.apupl"

    val channels = listOf(
        TvChannel("Rai1.it", "Rai 1", RAI),
        TvChannel("Rai2.it", "Rai 2", RAI),
        TvChannel("Rai3.it", "Rai 3", RAI),
        TvChannel("Rete.4.it", "Rete 4", MEDIASET),
        TvChannel("Canale.5.it", "Canale 5", MEDIASET),
        TvChannel("Italia.1.it", "Italia 1", MEDIASET),
        TvChannel("LA7.HD.it", "La7", IPTV),
        TvChannel("TV8.HD.it", "TV8", IPTV),
        TvChannel("Nove.it", "Nove", IPTV),
        TvChannel("20.it", "20", MEDIASET),
        TvChannel("Rai4.it", "Rai 4", RAI),
        TvChannel("Iris.it", "Iris", MEDIASET),
        TvChannel("RaiMovie.it", "Rai Movie", RAI),
        TvChannel("Focus.it", "Focus", MEDIASET),
        TvChannel("Real.Time.it", "Real Time", IPTV),
        TvChannel("DMAX.it", "DMAX", IPTV),
        TvChannel("cielo.it", "Cielo", IPTV),
    )

    private fun sky(id: String, name: String, sportio: String = name) = TvChannel(id, name, Sportio.PKG, sportio = sportio)

    /** Sky, played in Sportio */
    val skyCinema = listOf(
        sky("Sky.Cinema.Uno.it", "Sky Cinema Uno"),
        sky("Sky.Cinema.Uno.+24.it", "Sky Cinema Uno +24"),
        sky("Sky.Cinema.Action.it", "Sky Cinema Action"),
        sky("Sky.Cinema.Comedy.it", "Sky Cinema Comedy"),
        sky("Sky.Cinema.Drama.it", "Sky Cinema Drama"),
        sky("Sky.Cinema.Suspense.it", "Sky Cinema Suspense"),
        sky("Sky.Cinema.Romance.it", "Sky Cinema Romance"),
        sky("Sky.Cinema.Family.it", "Sky Cinema Family"),
        sky("Sky.Cinema.Collection.it", "Sky Cinema Collection"),
    )
    val skyEntertainment = listOf(
        sky("Sky.Uno.it", "Sky Uno"),
        sky("Sky.Atlantic.it", "Sky Atlantic"),
        sky("Sky.Serie.it", "Sky Serie"),
        sky("Sky.Investigation.it", "Sky Investigation"),
        sky("Sky.Crime.it", "Sky Crime"),
        sky("Sky.Collection.it", "Sky Collection"),
        sky("Sky.Documentaries.it", "Sky Documentaries"),
        sky("Sky.Nature.it", "Sky Nature"),
        sky("Sky.Adventure.it", "Sky Adventure"),
        sky("History.it", "History", "Sky History"),
        sky("Sky.Arte.it", "Sky Arte"),
        sky("Comedy.Central.it", "Comedy Central", "Sky Comedy Central"),
        sky("MTV.HD.it", "MTV", "Sky MTV"),
    )

    /**
     * The guide's state, for the TV tab: true when nothing is on air right now for our channels
     * (the source stopped updating, or it couldn't be downloaded).
     */
    @Volatile var stale = false; private set
    /** the newest download we have (0 = none) */
    @Volatile var downloadedAt = 0L; private set

    private val lock = Mutex()
    private var cache: List<TvShow>? = null
    private var cacheAt = 0L

    private suspend fun file(context: Context, index: Int): File = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "epg_it_$index.xml.gz")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L) {
            val tmp = File(context.cacheDir, "epg_it_$index.tmp")
            runCatching {
                val c = URL(FEEDS[index]).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(f)
            }
        }
        f
    }

    /** Every programme of the chosen channels from now to tomorrow morning. */
    suspend fun shows(context: Context): List<TvShow> = lock.withLock {
        cache?.let { if (System.currentTimeMillis() - cacheAt < 15 * 60_000L) return@withLock it }
        // the main feed first; the fallback only fills channels/hours the main one doesn't cover
        val list = withContext(Dispatchers.Default) {
            val all = ArrayList<TvShow>()
            FEEDS.indices.forEach { k ->
                val f = file(context, k)
                if (!f.exists()) return@forEach
                val part = runCatching { parse(f) }.getOrElse { emptyList() }
                all += part.filter { p -> all.none { it.channel.id == p.channel.id && p.start < it.stop && p.stop > it.start } }
            }
            all.sortedBy { it.start }
        }
        cache = list; cacheAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        stale = list.none { now in it.start until it.stop }
        downloadedAt = FEEDS.indices.maxOfOrNull { File(context.cacheDir, "epg_it_$it.xml.gz").let { f -> if (f.exists()) f.lastModified() else 0L } } ?: 0L
        list
    }

    private fun parse(f: File): List<TvShow> {
        // the TV tab's channels plus the sports ones (to find which channel shows a match)
        val byId = (Sportio.channels.associateBy { norm(it.id) } + (skyCinema + skyEntertainment + channels).associateBy { norm(it.id) })
        val logos = HashMap<String, String>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val now = System.currentTimeMillis()
        val until = now + 20 * 3600_000L
        val out = ArrayList<TvShow>()
        val p = Xml.newPullParser()
        GZIPInputStream(f.inputStream()).use { input ->
            p.setInput(input, "UTF-8")
            var ch: TvChannel? = null
            var start = 0L; var stop = 0L
            var title: String? = null; var cat: String? = null; var image: String? = null
            var channelId: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                    "channel" -> channelId = p.getAttributeValue(null, "id")?.let { norm(it) }
                    "icon" -> when {
                        ch != null -> if (image == null) image = p.getAttributeValue(null, "src")
                        channelId != null && channelId in byId && channelId !in logos ->
                            p.getAttributeValue(null, "src")?.let { logos[channelId!!] = it }
                    }
                    "programme" -> {
                        channelId = null
                        ch = byId[norm(p.getAttributeValue(null, "channel") ?: "")]
                        if (ch != null) {
                            start = runCatching { fmt.parse(p.getAttributeValue(null, "start"))!!.time }.getOrDefault(0)
                            stop = runCatching { fmt.parse(p.getAttributeValue(null, "stop"))!!.time }.getOrDefault(0)
                            title = null; cat = null; image = null
                        }
                    }
                    "title" -> if (ch != null && title == null) title = p.nextText()
                    "category" -> if (ch != null && cat == null) cat = p.nextText()
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "programme") {
                    val c = ch
                    if (c != null && title != null && stop > now && start < until) out += TvShow(c, title!!, start, stop, cat, image)
                    ch = null
                }
            }
        }
        return out.map { it.copy(channel = it.channel.copy(logo = logos[norm(it.channel.id)])) }.sortedBy { it.start }
    }

    /** What's on now, one per channel, in channel order. */
    fun onNow(all: List<TvShow>, list: List<TvChannel> = channels): List<TvShow> =
        list.mapNotNull { c -> all.firstOrNull { it.channel.id == c.id && it.isOn } }

    /** Tonight's prime time: the first programme starting 20:55–21:45 on each channel. */
    fun primeTime(all: List<TvShow>, list: List<TvChannel> = channels): List<TvShow> {
        val rome = TimeZone.getTimeZone("Europe/Rome")
        fun at(h: Int, m: Int) = Calendar.getInstance(rome).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        }.timeInMillis
        val from = at(20, 55); val to = at(21, 45)
        return list.mapNotNull { c -> all.firstOrNull { it.channel.id == c.id && it.start in from..to } }
    }

    fun time(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = TimeZone.getTimeZone("Europe/Rome") }.format(ms)
}
