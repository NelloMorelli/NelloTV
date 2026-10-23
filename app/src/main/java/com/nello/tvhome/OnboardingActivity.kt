package com.nello.tvhome

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * First start on a new Fire TV: a few full-screen steps in the launcher's style — what Nello TV
 * needs from the system (each with its state and one OK to fix it), your team, your F1 team,
 * Sportio's playlist (with a test), Nuvio. Everything stays editable in Impostazioni; it can be
 * run again from there.
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var list: LiquidList
    private lateinit var dots: LinearLayout
    private val rows = mutableListOf<View>()
    private var step = 0
    private var focusRow = 0

    private val steps by lazy {
        listOfNotNull<() -> Unit>(::welcome, ::permissions, ::team, ::f1,
            (::sportio as () -> Unit).takeIf { Sportio.installed(this) }, ::nuvio, ::done)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        val back = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            GlassView.blurred?.let { setImageBitmap(it) } ?: setImageResource(R.drawable.wallpaper)
        }
        title = TextView(this).apply {
            textSize = 34f
            typeface = Fonts.medium
            setTextColor(0xFFFFFFFF.toInt())
        }
        subtitle = TextView(this).apply {
            textSize = 15f
            typeface = Fonts.light
            setTextColor(0xB3FFFFFF.toInt())
            setLineSpacing(0f, 1.2f)
        }
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list = LiquidList(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = PageScroller(this).apply { addView(list) }
        val panel = LiquidCard(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dots, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(28) })
            addView(title)
            addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(back, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { setBackgroundColor(0xA6000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
            addView(left, FrameLayout.LayoutParams(dp(400), -2, Gravity.CENTER_VERTICAL).apply { marginStart = dp(64) })
            // as tall as its rows, centred; long lists scroll inside
            addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dp(520), dp(72), dp(64), dp(72)) })
        })
        // unfinished (e.g. Home pressed while testing Sportio): continue from the same step
        show(prefs.onboardingStep.coerceIn(0, steps.size - 1))
    }

    override fun onResume() {
        super.onResume()
        // back from a system page (accessibility, unknown sources) or Sportio: refresh the ticks
        if (rows.isNotEmpty()) show(step, animate = false)
    }

    // ---------------------------------------------------------------- steps

    private fun show(i: Int, animate: Boolean = true) {
        if (i != step) focusRow = 0
        step = i
        prefs.onboardingStep = i
        list.removeAllViews()
        rows.clear()
        steps[i]()
        dots.removeAllViews()
        steps.indices.forEach { k ->
            dots.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (k == i) 0xFFFFFFFF.toInt() else 0x4DFFFFFF)
                }
            }, LinearLayout.LayoutParams(dp(if (k == i) 9 else 7), dp(if (k == i) 9 else 7)).apply {
                marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL
            })
        }
        rows.getOrNull(focusRow)?.let { r -> r.post { r.requestFocus() } }
        if (animate) {
            val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
            listOf(title, subtitle, list).forEachIndexed { k, v ->
                v.alpha = 0f
                v.translationY = dp(14).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(k * 60L).setDuration(620).setInterpolator(ease).start()
            }
        }
    }

    private fun next() = if (step < steps.size - 1) show(step + 1) else finishSetup()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && step > 0) show(step - 1)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun heading(t: String, s: String) {
        title.text = t
        subtitle.text = s
    }

    private fun row(text: String, value: String? = null, chosen: Boolean = false, onClick: () -> Unit) {
        val index = rows.size
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { focusRow = index; onClick() }
        }
        r.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xF2FFFFFF.toInt())
            if (chosen) typeface = Fonts.medium
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
        })
        if (chosen) r.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(12) })
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
        rows += r
    }

    private fun note(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0x99FFFFFF.toInt())
        setLineSpacing(0f, 1.2f)
        setPadding(dp(16), dp(12), dp(16), dp(4))
    })

    private fun welcome() {
        heading("Benvenuto in Nello TV", "Configuriamo tutto in un minuto: permessi, la tua squadra, i canali. Puoi cambiare tutto quando vuoi in Impostazioni.")
        row("Iniziamo") { next() }
    }

    private fun permissions() {
        heading("Permessi", "Nello TV ha bisogno di poche cose dal sistema. Premi OK su quello che manca: si apre la pagina giusta, attivalo e torna indietro.")
        val service = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        row("Servizio di Nello TV", if (service) "✓ Attivo" else "Da attivare ›") {
            if (!service) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        note("Serve per il tasto Home, il ritorno dopo lo standby, il salvaschermo e le notifiche. In Accessibilità: Nello TV → Attiva.")
        val updates = packageManager.canRequestPackageInstalls()
        row("Aggiornamenti automatici", if (updates) "✓ Consentiti" else "Da consentire ›") {
            if (!updates) runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
        }
        val saver = runCatching { Settings.Secure.getInt(contentResolver, "screensaver_enabled") == 1 }.getOrDefault(false)
        row("Salvaschermo della Fire TV", if (saver) "✓ Attivo" else "Da attivare ›") {
            if (!saver) runCatching { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        }
        note("Il salvaschermo di Nello TV (sfondo e orologio) prende il posto di quello di Amazon: serve che sia attivo.")
        row("Continua") { next() }
    }

    private fun team() {
        heading("La tua squadra", "Le sue partite compaiono in Per te e ti avviso 10 minuti prima del calcio d'inizio.")
        row("Nessuna", chosen = prefs.teamId == null) { prefs.teamId = null; prefs.teamName = null; next() }
        note("Carico le squadre di Serie A…")
        lifecycleScope.launch {
            val teams = runCatching { Espn.serieATeams() }.getOrElse { emptyList() }
            // moved on meanwhile
            if (title.text != "La tua squadra") return@launch
            list.removeViewAt(list.childCount - 1)
            if (teams.isEmpty()) note("Squadre non disponibili ora: la sceglierai in Impostazioni.")
            teams.forEach { (id, name) ->
                row(name, chosen = id == prefs.teamId) { prefs.teamId = id; prefs.teamName = name; next() }
            }
            // start on your current team
            val at = teams.indexOfFirst { it.first == prefs.teamId }.let { if (it < 0) 0 else it + 1 }
            rows.getOrNull(at)?.let { r -> r.post { r.requestFocus() } }
        }
    }

    private fun f1() {
        heading("Formula 1", "La scuderia che segui: qualifiche, sprint e gare compaiono in Per te, con un avviso prima della partenza.")
        row("Nessuna", chosen = prefs.f1Team == null) { prefs.f1Team = null; next() }
        listOf("Ferrari", "McLaren", "Red Bull", "Mercedes", "Aston Martin", "Williams", "Alpine", "Racing Bulls", "Haas", "Audi", "Cadillac")
            .forEach { t -> row(t, chosen = t == prefs.f1Team) { prefs.f1Team = t; next() } }
        rows.indexOfFirst { r -> ((r as LinearLayout).getChildAt(0) as TextView).text == prefs.f1Team }
            .takeIf { it >= 0 }?.let { focusRow = it }
    }

    private fun sportio() {
        heading("Canali in Sportio", "Partite e canali Sky si aprono in Sportio. Scegli il numero della tua playlist: Sportio dovrebbe aprire RAI 1. Se invece apre la lista dei canali, premi Home per tornare qui e prova il numero successivo.")
        (1..10).forEach { n ->
            row("Playlist $n", if (n == prefs.sportioSource) "Prova di nuovo" else "Prova", chosen = n == prefs.sportioSource) {
                prefs.sportioSource = n
                Sportio.play(this, "RAI 1")
            }
        }
        row("RAI 1 è partito: continua") { next() }
        // start on the playlist set now (after a test: on "continua")
        if (focusRow == 0) focusRow = prefs.sportioSource - 1
    }

    private fun nuvio() {
        val linked = NuvioSync(this).isLinked
        heading("Nuvio", if (linked) "Nuvio è già collegato: Continua a guardare e Per te usano la tua cronologia."
            else "Collega Nuvio Sync per vedere Continua a guardare e consigli basati su quello che guardi.")
        if (linked) row("Continua") { next() }
        else {
            row("Collega con il QR") {
                finishSetup()
                startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LINK_NUVIO))
            }
            row("Più tardi") { next() }
        }
    }

    private fun done() {
        heading("Tutto pronto", "Premi Home in qualsiasi momento per tornare qui. Impostazioni → Generale → Configurazione iniziale per rifarla.")
        row("Apri Nello TV") { finishSetup() }
    }

    private fun finishSetup() {
        prefs.onboarded = true
        prefs.onboardingStep = 0
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
