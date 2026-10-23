package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast

/**
 * "Personalizza": look (accent, glass, dimming, roundness, card size), what's on screen (clock,
 * dock, info under the cards), tabs and catalogues on/off, app names, animation speed,
 * screensaver delay, reset. Changes apply as soon as you're back on the home screen.
 */
class CustomizeActivity : GlassPage() {

    private lateinit var apps: Apps
    private val rows = mutableListOf<View>()
    private var focusRow = 0

    companion object {
        /** catalogues that can be switched off, by tab */
        val CATALOGS = linkedMapOf(
            "Film" to listOf("movie_popular" to "Popolari ora", "movie_cinema" to "Al cinema", "movie_upcoming" to "In arrivo") +
                TopX.services.map { (id, n) -> "movie_tx_$id" to "Top 10 su $n" },
            "Serie TV" to listOf("series_trend" to "Di tendenza", "series_popular" to "Popolari ora", "series_onair" to "Nuovi episodi") +
                TopX.services.map { (id, n) -> "series_tx_$id" to "Top 10 su $n" },
            "Sport" to listOf("sport_today" to "Oggi", "sport_next" to "Prossimi eventi", "sport_f1" to "Formula 1", "sport_done" to "Risultati"),
            "TV" to listOf("tv_now" to "In onda ora", "tv_prime" to "Stasera"),
        )
        val TABS = listOf("SEARCH" to "Cerca", "MOVIES" to "Film", "SERIES" to "Serie TV", "TV" to "TV", "SPORT" to "Sport")

        fun hiddenCatalogs(c: Context): Set<String> = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getStringSet("hiddenCatalogs", emptySet())!!
        fun hiddenTabs(c: Context): Set<String> = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getStringSet("hiddenTabs", emptySet())!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        Theme.load(this)
        build()
    }

    private fun row(title: String, value: String?, onClick: (View) -> Unit) {
        val i = rows.size
        rows += item(title, value) { focusRow = i; onClick(it) }
    }

    private fun build() {
        list.removeAllViews()
        rows.clear()
        Theme.load(this)
        title("Personalizza", "Le modifiche si applicano appena torni alla home")

        header("Aspetto")
        row("Colore principale", Theme.ACCENTS.firstOrNull { it.second == Theme.accent }?.first ?: "Personalizzato") { v ->
            pick(v, "Colore principale", Theme.ACCENTS.map { it.first }, Theme.ACCENTS.indexOfFirst { it.second == Theme.accent }) { i ->
                Theme.edit(this).putInt("accent", Theme.ACCENTS[i].second).apply()
            }
        }
        val glass = listOf("Leggero", "Normale", "Intenso")
        row("Effetto vetro", glass[Theme.glass]) { v -> pick(v, "Effetto vetro", glass, Theme.glass) { Theme.edit(this).putInt("glass", it).apply() } }
        val dims = listOf(0f, 0.15f, 0.3f, 0.45f, 0.6f)
        val dimNames = listOf("Nessuno", "Leggero", "Medio", "Forte", "Molto forte")
        row("Oscura lo sfondo", dimNames[dims.indexOfFirst { it >= Theme.dim - 0.01f }.coerceAtLeast(0)]) { v ->
            pick(v, "Oscura lo sfondo", dimNames, dims.indexOfFirst { it >= Theme.dim - 0.01f }) { Theme.edit(this).putFloat("dim", dims[it]).apply() }
        }
        val rounds = listOf(0.4f, 1f, 1.6f)
        val roundNames = listOf("Squadrati", "Normali", "Molto arrotondati")
        row("Angoli", roundNames[rounds.indexOf(Theme.roundness).coerceAtLeast(0)]) { v ->
            pick(v, "Angoli", roundNames, rounds.indexOf(Theme.roundness)) { Theme.edit(this).putFloat("roundness", rounds[it]).apply() }
        }
        val sizes = listOf(0.85f, 1f, 1.12f)
        val sizeNames = listOf("Compatte", "Normali", "Grandi")
        row("Dimensione card", sizeNames[sizes.indexOf(Theme.cardScale).coerceAtLeast(0)]) { v ->
            pick(v, "Dimensione card", sizeNames, sizes.indexOf(Theme.cardScale)) { Theme.edit(this).putFloat("cardScale", sizes[it]).apply() }
        }

        header("Sullo schermo")
        toggle("Orologio in alto", "showClock", Theme.showClock)
        toggle("Dock", "showDock", Theme.showDock)
        toggle("Titolo e info sotto le card", "showCardInfo", Theme.showCardInfo)

        header("Schede")
        val hiddenT = hiddenTabs(this)
        TABS.forEach { (key, name) ->
            row(name, if (key in hiddenT) "Nascosta" else "Visibile") {
                val next = if (key in hiddenT) hiddenT - key else hiddenT + key
                Theme.edit(this).putStringSet("hiddenTabs", next).apply()
                build()
            }
        }

        header("Cataloghi")
        val hiddenC = hiddenCatalogs(this)
        CATALOGS.forEach { (tab, cats) ->
            val off = cats.count { it.first in hiddenC }
            row(tab, if (off == 0) "Tutti visibili" else "$off nascosti") { v -> catalogs(v, tab, cats) }
        }

        header("App")
        row("Rinomina un'app", "›") { v -> renamePick(v) }
        val names = getSharedPreferences("app_names", MODE_PRIVATE).all
        if (names.isNotEmpty()) row("Ripristina i nomi originali", "${names.size} app") {
            getSharedPreferences("app_names", MODE_PRIVATE).edit().clear().apply()
            toast("Nomi originali ripristinati")
            build()
        }

        header("Sistema")
        val speeds = listOf(0f, 0.35f, 0.5f, 0.8f)
        val speedNames = listOf("Disattivate", "Veloci", "Normali", "Lente")
        val cur = runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(0.5f)
        row("Animazioni", speedNames[speeds.indexOfFirst { Math.abs(it - cur) < 0.05f }.coerceAtLeast(2)]) { v ->
            pick(v, "Animazioni", speedNames, speeds.indexOfFirst { Math.abs(it - cur) < 0.05f }) { i ->
                runCatching {
                    listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
                        .forEach { k -> Settings.Global.putFloat(contentResolver, k, speeds[i]) }
                }.onFailure { toast("Permesso mancante per cambiare le animazioni") }
            }
        }
        val waits = listOf(2, 5, 10, 15, 30)
        val wait = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 60_000 }.getOrDefault(5)
        row("Salvaschermo dopo", "$wait minuti") { v ->
            pick(v, "Salvaschermo dopo", waits.map { "$it minuti" }, waits.indexOf(wait)) { i ->
                runCatching { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, waits[i] * 60_000) }
                    .onFailure { toast("Permesso mancante per cambiare il tempo") }
            }
        }
        row("Ripristina personalizzazione", "›") {
            val keepHidden = hiddenTabs(this)
            Theme.edit(this).clear().apply()
            if (keepHidden.isNotEmpty()) toast("Aspetto, schede e cataloghi ripristinati")
            build()
        }

        rows.getOrNull(focusRow)?.let { r -> r.post { r.requestFocus() } }
    }

    private fun toggle(title: String, key: String, on: Boolean) = row(title, if (on) "Sì" else "No") {
        Theme.edit(this).putBoolean(key, !on).apply()
        build()
    }

    private fun pick(anchor: View, title: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        val p = popup().title(title)
        options.forEachIndexed { i, o ->
            p.button(if (i == current) "✓  $o" else o) {
                set(i)
                build()
            }
        }
        p.showUnder(anchor)
    }

    private fun catalogs(anchor: View, tab: String, cats: List<Pair<String, String>>) {
        val hidden = hiddenCatalogs(this)
        val p = popup().title(tab, "OK per mostrare o nascondere")
        cats.forEach { (key, name) ->
            p.button(if (key in hidden) "     $name" else "✓  $name") {
                val next = if (key in hidden) hidden - key else hidden + key
                // keep at least one catalogue per tab
                if (cats.all { it.first in next }) toast("Almeno un catalogo deve restare") else Theme.edit(this).putStringSet("hiddenCatalogs", next).apply()
                build()
                catalogs(rows[focusRow], tab, cats)
            }
        }
        p.showUnder(anchor)
    }

    private fun renamePick(anchor: View) {
        val p = popup().title("Rinomina un'app")
        apps.all().filter { it.pkg != Apps.SETTINGS }.forEach { a -> p.button(a.label) { rename(anchor, a) } }
        p.showUnder(anchor)
    }

    private fun rename(anchor: View, app: AppInfo) {
        val input = EditText(this).apply {
            setText(app.label)
            selectAll()
            setTextColor(0xFFFFFFFF.toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
        }
        val p = popup().title("Nuovo nome", app.label).view(input)
        fun save() {
            val n = input.text.toString().trim()
            if (n.isNotEmpty()) getSharedPreferences("app_names", MODE_PRIVATE).edit().putString(app.pkg, n).apply()
            toast("Rinominata in \"$n\"")
            p.dismiss()
            build()
        }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        p.button("Salva", keepOpen = true) { save() }
        p.showUnder(anchor)
        input.post { input.requestFocus() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
