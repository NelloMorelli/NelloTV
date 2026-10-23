package com.nello.tvhome

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlinx.coroutines.launch

/**
 * The single carousel of the "Per te" / Film / Serie TV screens.
 * Titles are vertical posters; the active one (last focused) widens into a landscape card with its logo.
 * Matches and apps are always landscape cards.
 */
class CarouselAdapter(
    private var items: List<Any>,
    private val host: Host,
    private val scope: LifecycleCoroutineScope,
) : RecyclerView.Adapter<CarouselAdapter.VH>() {

    companion object {
        const val HEIGHT = 210
        const val POSTER_W = 140
        const val WIDE_W = 373
        private const val MEDIA = 0
        private const val MATCH = 1
        private const val APP = 2
        private const val RACE = 3
    }

    /** the card shown wide; stays wide while focus is in the dock or the tabs */
    var active = 0
        private set

    /** Start with [i] as the wide card (restoring where you were). */
    fun startAt(i: Int) {
        if (i in items.indices) active = i
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Match -> MATCH
        is AppInfo -> APP
        is RaceSession -> RACE
        else -> MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val card = FrameLayout(ctx).apply {
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.card_bg)
            foreground = ctx.getDrawable(R.drawable.card_focus)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14 * dp)
                }
            }
            layoutParams = RecyclerView.LayoutParams(px(if (viewType == MEDIA) POSTER_W else WIDE_W), px(HEIGHT)).apply { marginEnd = px(16) }
        }
        return VH(card, viewType)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.itemView.setOnKeyListener(null)
        h.itemView.setOnLongClickListener(null)
        when (item) {
            is MediaItem -> h.bindMedia(item, position == active)
            is Match -> h.bindMatch(item)
            is AppInfo -> h.bindApp(item)
            is RaceSession -> h.bindRace(item)
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                .setDuration(440).setInterpolator(DecelerateInterpolator(1.4f)).start()
            if (!has) return@setOnFocusChangeListener
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos != active) {
                val old = active
                active = pos
                notifyItemChanged(old, "collapse")
                h.setExpanded(true, animate = true)
            }
            when (item) {
                is MediaItem -> host.onMediaFocused(item)
                is Match -> host.onMatchFocused(item)
                is AppInfo -> host.onAppFocused(item)
                is RaceSession -> host.onRaceFocused(item)
            }
        }
    }

    /** Same items, fresher data (live scores): rebind in place without touching focus or the wide card. */
    fun update(newItems: List<Any>) {
        items = newItems
        notifyItemRangeChanged(0, items.size, "refresh")
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("refresh")) {
            (items[position] as? Match)?.let { h.bindMatch(it) }
            return
        }
        if (payloads.contains("collapse")) {
            h.setExpanded(false, animate = true)
            return
        }
        super.onBindViewHolder(h, position, payloads)
    }

    inner class VH(private val card: FrameLayout, private val type: Int) : RecyclerView.ViewHolder(card) {
        private val ctx = card.context
        private val dp = ctx.resources.displayMetrics.density
        private fun px(v: Int) = (v * dp).toInt()

        private val wide = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val poster = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_END; adjustViewBounds = true }
        private val rank = TextView(ctx).apply {
            textSize = 40f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 2f, 0xCC000000.toInt())
            setPadding(px(10), px(2), 0, 0)
        }
        private val progressTrack = FrameLayout(ctx).apply { setBackgroundResource(R.drawable.progress_track) }
        private val progressFill = View(ctx).apply { setBackgroundResource(R.drawable.progress_fill) }
        private var widthAnim: ValueAnimator? = null
        private var posterJob: kotlinx.coroutines.Job? = null
        private var logoJob: kotlinx.coroutines.Job? = null

        // match card
        private val matchView by lazy { buildMatchView() }
        private lateinit var mLeague: TextView
        private lateinit var mCenter: TextView
        private lateinit var mStatus: TextView
        private lateinit var mHomeLogo: ImageView
        private lateinit var mAwayLogo: ImageView
        private lateinit var mHome: TextView
        private lateinit var mAway: TextView

        // sport cards: event artwork from TheSportsDB over the drawn card, with a status chip
        // INVISIBLE, not GONE: the image loader needs the view's size to start
        private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }
        private val chip = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(px(10), px(4), px(10), px(4))
            visibility = View.GONE
        }
        private var artJob: kotlinx.coroutines.Job? = null

        private fun chipBg(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 8 * dp }

        /** Load [load]'s artwork; when found it covers the drawn card and the chip shows the status. */
        private var artKey: String? = null

        private fun showArt(key: String, status: String, live: Boolean, load: suspend () -> String?) {
            chip.text = status
            chip.background = chipBg(if (live) 0xE6E5484D.toInt() else 0xCC000000.toInt())
            // same event (e.g. live score refresh): keep the artwork, only the chip changes
            if (key == artKey) return
            artKey = key
            artJob?.cancel()
            art.dispose()
            art.setImageDrawable(null)
            art.visibility = View.INVISIBLE
            chip.visibility = View.GONE
            artJob = scope.launch {
                val url = runCatching { load() }.getOrNull() ?: return@launch
                art.load(url) {
                    crossfade(true)
                    listener(onSuccess = { _, _ ->
                        art.visibility = View.VISIBLE
                        chip.visibility = if (status.isNotEmpty()) View.VISIBLE else View.GONE
                    })
                }
            }
        }

        private fun addArtLayers() {
            card.addView(art, FrameLayout.LayoutParams(-1, -1))
            // top-right: the free corner on both the football and the F1 artwork
            card.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(0, px(12), px(12), 0)
            })
        }

        // app card
        private val appImage = ImageView(ctx)
        private val appLabel = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        // F1 card: Ferrari red, "F1" mark, Grand Prix, session and time
        private lateinit var rTitle: TextView
        private lateinit var rSession: TextView
        private val raceView by lazy {
            FrameLayout(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFD40000.toInt(), 0xFF5A0000.toInt()))
                addView(TextView(ctx).apply {
                    text = "F1"
                    textSize = 34f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
                    setTextColor(Color.WHITE)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(px(18), px(12), 0, 0) })
                addView(TextView(ctx).apply {
                    text = "FORMULA 1"
                    textSize = 10f
                    letterSpacing = 0.15f
                    setTextColor(0xCCFFE600.toInt())
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, px(20), px(18), 0) })
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                rTitle = TextView(ctx).apply {
                    textSize = 22f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                rSession = TextView(ctx).apply {
                    textSize = 15f
                    setTextColor(0xE6FFFFFF.toInt())
                }
                col.addView(rTitle)
                col.addView(rSession)
                addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(px(18), 0, px(18), px(18)) })
            }
        }

        init {
            when (type) {
                MEDIA -> {
                    card.addView(wide, FrameLayout.LayoutParams(-1, -1))
                    card.addView(poster, FrameLayout.LayoutParams(-1, -1))
                    card.addView(View(ctx).apply { setBackgroundResource(R.drawable.card_gradient) },
                        FrameLayout.LayoutParams(-1, px(80), Gravity.BOTTOM))
                    card.addView(logo, FrameLayout.LayoutParams(px(150), px(56), Gravity.BOTTOM or Gravity.END).apply {
                        setMargins(0, 0, px(14), px(14))
                    })
                    card.addView(rank, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
                    progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, -1))
                    card.addView(progressTrack, FrameLayout.LayoutParams(-1, px(4), Gravity.BOTTOM).apply {
                        setMargins(px(10), 0, px(10), px(6))
                    })
                }
                MATCH -> { card.addView(matchView, FrameLayout.LayoutParams(-1, -1)); addArtLayers() }
                RACE -> { card.addView(raceView, FrameLayout.LayoutParams(-1, -1)); addArtLayers() }
                APP -> {
                    card.addView(appImage, FrameLayout.LayoutParams(-1, -1))
                    card.addView(appLabel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = px(14) })
                }
            }
        }

        fun bindMedia(item: MediaItem, expanded: Boolean) {
            wide.load(item.image) { crossfade(true) }
            posterJob?.cancel()
            poster.dispose()
            poster.setImageDrawable(null)
            val p = item.poster
            if (p != null) poster.load(p) { crossfade(true) }
            else if (item.imdbId != null) posterJob = scope.launch {
                val url = runCatching { Tmdb.posterByImdb(item.imdbId, item.type) }.getOrNull()
                if (url != null) poster.load(url) { crossfade(true) } else poster.load(item.image)
            } else poster.load(item.image)
            logo.dispose()
            logo.setImageDrawable(null)
            logoJob?.cancel()
            // title treatment on the wide card, fetched from TMDB when the list didn't include it
            if (!item.artworkHasTitle && item.action == MediaItem.Action.OPEN) {
                if (item.logo != null) logo.load(item.logo)
                else logoJob = scope.launch { runCatching { Tmdb.logoFor(item) }.getOrNull()?.let { logo.load(it) { crossfade(true) } } }
            }
            rank.text = item.rank?.toString().orEmpty()
            val prog = item.progress
            progressTrack.visibility = if (prog != null) View.VISIBLE else View.GONE
            if (prog != null) progressTrack.post {
                progressFill.layoutParams = progressFill.layoutParams.apply { width = (progressTrack.width * prog).toInt() }
            }
            card.contentDescription = item.title
            card.setOnClickListener { host.onMediaClicked(item) }
            setExpanded(expanded, animate = false)
        }

        /** Wide landscape card ⇄ vertical poster, animating width and crossfading the images. */
        fun setExpanded(expanded: Boolean, animate: Boolean) {
            if (type != MEDIA) return
            val target = px(if (expanded) WIDE_W else POSTER_W)
            widthAnim?.cancel()
            val posterAlpha = if (expanded) 0f else 1f
            val wideAlpha = if (expanded) 1f else 0f
            if (!animate) {
                card.layoutParams = card.layoutParams.apply { width = target }
                poster.alpha = posterAlpha
                rank.alpha = posterAlpha
                logo.alpha = wideAlpha
                return
            }
            val from = card.layoutParams.width
            widthAnim = ValueAnimator.ofInt(from, target).apply {
                duration = 560 // ≈280 ms at 0.5x animation scale
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    card.layoutParams = card.layoutParams.apply { width = it.animatedValue as Int }
                }
                start()
            }
            poster.animate().alpha(posterAlpha).setDuration(560).start()
            // the wide Top 10 artwork already shows its number
            rank.animate().alpha(posterAlpha).setDuration(560).start()
            logo.animate().alpha(wideAlpha).setDuration(560).start()
        }

        fun bindMatch(m: Match) {
            matchView
            mLeague.text = m.league.label.uppercase()
            mHomeLogo.load(m.homeLogo)
            mAwayLogo.load(m.awayLogo)
            mHome.text = m.home
            mAway.text = m.away
            when (m.state) {
                "pre" -> {
                    val isToday = Espn.dayLabel(m) == java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.ITALY).format(java.util.Date())
                    mCenter.text = Espn.kickoff(m)
                    mStatus.text = if (isToday) "Oggi" else Espn.dayLabel(m)
                }
                "post" -> { mCenter.text = "${m.homeScore ?: 0} - ${m.awayScore ?: 0}"; mStatus.text = "Finale" }
                else -> { mCenter.text = "${m.homeScore ?: 0} - ${m.awayScore ?: 0}"; mStatus.text = "LIVE · ${m.detail}" }
            }
            val chipText = when (m.state) {
                "in" -> "LIVE  ${m.homeScore ?: 0}-${m.awayScore ?: 0}  ·  ${m.detail}"
                "post" -> "Finale  ${m.homeScore ?: 0}-${m.awayScore ?: 0}"
                else -> "${mStatus.text}  ${Espn.kickoff(m)}".trim()
            }
            showArt("m:${m.id}", chipText, m.state == "in") { SportArt.forMatch(m) }
            card.contentDescription = "${m.home} contro ${m.away}"
            card.setOnClickListener { host.onMatchClicked(m) }
            // hold OK or ☰: lineups, goals and cards
            card.setOnLongClickListener { host.onMatchDetails(m); true }
            card.setOnKeyListener { _, code, ev ->
                if (code != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (ev.action == KeyEvent.ACTION_UP) host.onMatchDetails(m)
                true
            }
        }

        private fun buildMatchView(): View {
            val root = FrameLayout(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E3A9E.toInt(), 0xFF0B1D5E.toInt()))
            }
            mLeague = TextView(ctx).apply {
                textSize = 10f
                letterSpacing = 0.1f
                setTextColor(0xCCFFFFFF.toInt())
            }
            root.addView(mLeague, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(14) })
            fun side(): Pair<LinearLayout, Pair<ImageView, TextView>> {
                val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                val name = TextView(ctx).apply {
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    maxLines = 1
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(img, LinearLayout.LayoutParams(px(84), px(84)))
                    addView(name, LinearLayout.LayoutParams(px(110), -2).apply { topMargin = px(8) })
                }
                return col to (img to name)
            }
            val (homeCol, home) = side()
            val (awayCol, away) = side()
            mHomeLogo = home.first; mHome = home.second
            mAwayLogo = away.first; mAway = away.second
            mCenter = TextView(ctx).apply {
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            mStatus = TextView(ctx).apply {
                textSize = 11f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            val center = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(mCenter)
                addView(mStatus)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(homeCol)
                addView(center, LinearLayout.LayoutParams(0, -2, 1f))
                addView(awayCol)
            }
            root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(px(18), px(16), px(18), 0) })
            return root
        }

        fun bindRace(r: RaceSession) {
            raceView
            rTitle.text = r.grandPrix
            val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.ITALY).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Rome") }
            val isToday = day.format(r.startMillis) == day.format(System.currentTimeMillis())
            rSession.text = when (r.state) {
                "in" -> "${r.session} · LIVE"
                "post" -> "${r.session} · conclusa"
                else -> "${r.session} · ${if (isToday) "Oggi" else day.format(r.startMillis)} ${Espn.kickoff(r.startMillis)}"
            }
            showArt("f1:${r.grandPrix}:${r.sessionEn}", rSession.text.toString(), r.state == "in") { SportArt.forRace(r) }
            card.contentDescription = "${r.grandPrix} ${r.session}"
            card.setOnClickListener { host.onRaceClicked(r) }
        }

        fun bindApp(app: AppInfo) {
            if (app.banner != null) {
                appImage.scaleType = ImageView.ScaleType.CENTER_CROP
                appImage.setPadding(0, 0, 0, 0)
                appImage.setImageDrawable(app.banner)
                appLabel.visibility = View.GONE
            } else {
                appImage.scaleType = ImageView.ScaleType.FIT_CENTER
                appImage.setPadding(px(60), px(30), px(60), px(60))
                appImage.setImageDrawable(app.icon)
                appLabel.visibility = View.VISIBLE
                appLabel.text = app.label
            }
            card.contentDescription = app.label
            card.setOnClickListener { host.onAppClicked(app) }
            card.setOnLongClickListener { host.onAppMenu(app); true }
            card.setOnKeyListener { _, code, ev ->
                if (code != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (ev.action == KeyEvent.ACTION_UP) host.onAppMenu(app)
                true
            }
        }
    }
}
