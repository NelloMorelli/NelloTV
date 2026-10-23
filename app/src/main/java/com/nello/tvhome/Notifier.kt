package com.nello.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Banners over any app, shown by the always-on accessibility service:
 * Napoli 10 minutes before kick-off, Ferrari's qualifying/sprint/race 10 minutes before,
 * and new episodes of series you're watching in Nuvio. Information only: no remote keys are
 * intercepted (filtering keys made every press on the TV wait for the launcher).
 */
class Notifier(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = service.getSharedPreferences("notified", Context.MODE_PRIVATE)
    private val wm = service.getSystemService(WindowManager::class.java)
    private var banner: View? = null
    private var hideJob: Job? = null
    private var onOpen: (() -> Unit)? = null
    private var lastEpisodeCheck = 0L

    val isShowing get() = banner != null

    fun start() {
        SportArt.init(service)
        scope.launch {
            delay(20_000)
            while (isActive) {
                runCatching { check() }
                delay(4 * 60_000L)
            }
        }
    }

    fun stop() {
        scope.coroutineContext[Job]?.cancel()
        hide()
    }

    /** ☰ pressed while a banner is visible. */
    fun open() {
        val action = onOpen
        hide()
        action?.invoke()
    }

    private fun once(key: String): Boolean {
        if (prefs.contains(key)) return false
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        return true
    }

    private suspend fun check() {
        val now = System.currentTimeMillis()
        // starting in 5–15 minutes (the check runs every 4)
        fun soon(start: Long) = start - now in 5 * 60_000L..15 * 60_000L

        Espn.napoliToday().filter { it.state == "pre" && soon(it.startMillis) }.forEach { m ->
            if (once("m:${m.id}")) show(
                "${m.home} – ${m.away}",
                "${m.league.label} · tra ${(m.startMillis - now) / 60_000} minuti · ${Espn.kickoff(m)}",
                SportArt.forMatch(m) ?: m.homeLogo,
            ) { openMatch(m) }
        }
        Espn.f1Today().filter { it.state == "pre" && soon(it.startMillis) }.forEach { r ->
            if (once("f1:${r.grandPrix}:${r.sessionEn}")) show(
                "Ferrari · ${r.grandPrix}",
                "${r.session} tra ${(r.startMillis - now) / 60_000} minuti · ${Espn.kickoff(r.startMillis)}",
                SportArt.forRace(r),
            ) { openRace(r) }
        }
        // new episodes: every 6 hours is plenty
        if (now - lastEpisodeCheck > 6 * 3600_000L) {
            lastEpisodeCheck = now
            val nuvio = NuvioSync(service)
            if (nuvio.isLinked) nuvio.continueWatching().filter { it.newEpisode }.forEach { item ->
                if (once("ep:${item.imdbId}:${item.badge}")) show(
                    item.title,
                    "${item.badge?.removePrefix("Nuovo · ") ?: "Nuovo episodio"} è disponibile",
                    item.image,
                ) { item.imdbId?.let { Apps(service).openInNuvio(item, it) } }
            }
        }
    }

    private suspend fun openMatch(m: Match) {
        val id = runCatching { SportsAddon.findAnyMatch(m) }.getOrNull()
        if (id != null) Apps(service).openNuvioContent(id, "sport") else openLauncherSport()
    }

    private suspend fun openRace(r: RaceSession) {
        val id = runCatching { SportsAddon.findRace(r) }.getOrNull()
        if (id != null) Apps(service).openNuvioContent(id, "sport") else openLauncherSport()
    }

    private fun openLauncherSport() {
        service.startActivity(
            Intent(service, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SPORT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    /** Glass-like card in the top-right corner for 12 seconds. */
    fun show(title: String, text: String, image: String?, open: suspend () -> Unit) {
        hide()
        onOpen = { scope.launch { open() } }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2303034.toInt(), 0xF21A1A1D.toInt())).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x40FFFFFF)
            }
        }
        if (image != null) card.addView(ImageView(service).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF000000.toInt()) }
            load(image)
        }, LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(14) })
        card.addView(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(service).apply {
                this.text = title
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
            })
            addView(TextView(service).apply {
                this.text = text
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
                maxLines = 2
            })
            addView(TextView(service).apply {
                this.text = "Lo trovi in Nello TV"
                textSize = 12f
                setTextColor(0x99FFFFFF.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(dp(330), -2))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(32); y = dp(28)
            windowAnimations = android.R.style.Animation_Toast
        }
        runCatching { wm.addView(card, lp); banner = card }
        hideJob = scope.launch { delay(12_000); hide() }
    }

    fun hide() {
        hideJob?.cancel()
        banner?.let { runCatching { wm.removeView(it) } }
        banner = null
    }
}
