package com.nello.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * "Cambia immagine → online": alternative pictures for an app, from its Google Play page (found by
 * package, or by name for apps that are only on Amazon's store). Every one comes back already
 * shaped as a 640×360 tile: the HD icon themed or on its own colour, the trailer's cover, the
 * landscape screenshots.
 */
object AppArt {
    class Candidate(val label: String, val image: Bitmap)

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to UA, "Accept" to "text/html")

    private suspend fun page(pkg: String): String? =
        runCatching { Net.get("https://play.google.com/store/apps/details?id=$pkg&hl=it", headers) }.getOrNull()

    /** The Play page of the app, or of the first app found searching its name. */
    private suspend fun findPage(pkg: String, label: String): String? {
        page(pkg)?.let { return it }
        val search = runCatching {
            Net.get("https://play.google.com/store/search?q=" + java.net.URLEncoder.encode(label, "UTF-8") + "&c=apps&hl=it", headers)
        }.getOrNull() ?: return null
        val other = Regex("/store/apps/details\\?id=([A-Za-z0-9_.]+)").find(search)?.groupValues?.get(1) ?: return null
        return page(other)
    }

    private fun imgs(html: String, alt: String) =
        Regex("<img[^>]+>").findAll(html).map { it.value }.filter { "alt=\"$alt\"" in it }
            .mapNotNull { Regex("src=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.distinct().toList()

    /** Google's image addresses take the size after "=". */
    private fun sized(url: String, spec: String) = url.substringBefore('=') + "=" + spec

    private fun download(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection().apply { connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("User-Agent", UA) }
        conn.getInputStream().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    suspend fun find(pkg: String, label: String): List<Candidate> = withContext(Dispatchers.IO) {
        val html = findPage(pkg, label) ?: return@withContext emptyList()
        val icon = imgs(html, "Immagine dell&#39;icona").firstOrNull()
        val shots = imgs(html, "Immagine screenshot").take(8)
        val video = Regex("i\\.ytimg\\.com/vi/([A-Za-z0-9_-]+)/").find(html)?.groupValues?.get(1)
        coroutineScope {
            val iconJob = async { icon?.let { download(sized(it, "s512")) } }
            val videoJob = async { video?.let { download("https://i.ytimg.com/vi/$it/maxresdefault.jpg") ?: download("https://i.ytimg.com/vi/$it/hqdefault.jpg") } }
            val shotJobs = shots.map { async { download(sized(it, "w1280-h720")) } }
            val out = mutableListOf<Candidate>()
            iconJob.await()?.let { ic ->
                out += Candidate("Icona HD a tema", ThemedTile.cover(label, ic))
                out += Candidate("Icona HD sul suo colore", ThemedTile.onEdgeColour(ic))
            }
            videoJob.await()?.let { out += Candidate("Copertina del trailer", ThemedTile.crop(it)) }
            // only the landscape screenshots fit a tile
            shotJobs.awaitAll().filterNotNull().filter { it.width > it.height }.forEachIndexed { i, b ->
                out += Candidate("Screenshot ${i + 1}", ThemedTile.crop(b))
            }
            out
        }
    }
}
