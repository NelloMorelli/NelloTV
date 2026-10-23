package com.nello.tvhome

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

interface Host {
    fun onMediaFocused(item: MediaItem)
    fun onAppFocused(app: AppInfo)
    fun onMediaClicked(item: MediaItem)
    fun onAppClicked(app: AppInfo)
    fun onAppMenu(app: AppInfo)
    fun onMatchFocused(match: Match)
    fun onMatchClicked(match: Match)
    fun onRaceFocused(race: RaceSession) {}
    fun onRaceClicked(race: RaceSession) {}
}

/** Keeps the focused child aligned to the start of the list, like the Fire TV / Apple TV home rows. */
class StartAlignedLayoutManager(context: Context, orientation: Int) : LinearLayoutManager(context, orientation, false) {

    private val ease = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)

    /**
     * Vertical list: keep about one screen of rows laid out above and below. Otherwise, with a row
     * aligned at the top, the row above isn't there yet and pressing up skipped to the tab bar.
     */
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        if (orientation == VERTICAL) {
            extraLayoutSpace[0] = height
            extraLayoutSpace[1] = height
        } else {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
        }
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val horizontal = orientation == HORIZONTAL
        val delta = if (horizontal) child.left - parent.paddingLeft else child.top - parent.paddingTop
        if (delta == 0) return false
        val dx = if (horizontal) delta else 0
        val dy = if (horizontal) 0 else delta
        if (immediate) parent.scrollBy(dx, dy)
        else if (orientation == VERTICAL) parent.smoothScrollBy(dx, dy, ease, (600 + Math.abs(dy) / 3).coerceAtMost(840))
        else parent.smoothScrollBy(dx, dy)
        return true
    }
}

private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

class RowsAdapter(private val host: Host) : RecyclerView.Adapter<RowsAdapter.VH>() {
    private val items = ArrayList<Row>()
    val rows: List<Row> get() = items

    private val pool = RecyclerView.RecycledViewPool()
    private val scrollState = HashMap<String, android.os.Parcelable?>()

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<Row>) {
        items.clear()
        items.addAll(newRows)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun insert(index: Int, row: Row) {
        items.add(index, row)
        notifyItemInserted(index)
    }

    fun remove(row: Row) {
        val i = items.indexOf(row)
        if (i >= 0) {
            items.removeAt(i)
            notifyItemRemoved(i)
        }
    }

    fun rowChanged(row: Row) {
        val i = items.indexOf(row)
        if (i >= 0) notifyItemChanged(i)
    }

    /** Rebind the cards of a row whose item count didn't change, keeping focus on the same card (live scores). */
    fun rowContentChanged(row: Row, list: RecyclerView) {
        for (i in 0 until list.childCount) {
            val vh = list.getChildViewHolder(list.getChildAt(i)) as? VH ?: continue
            if (vh.row === row) {
                vh.list.adapter?.notifyItemRangeChanged(0, row.size)
                return
            }
        }
    }

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.row = row
        holder.title.text = row.title
        holder.title.visibility = if (row.title.isEmpty()) View.GONE else View.VISIBLE
        holder.list.adapter = CardsAdapter(row, host)
        val saved = scrollState[row.key]
        if (saved != null) holder.list.layoutManager?.onRestoreInstanceState(saved) else holder.list.scrollToPosition(0)
    }

    override fun onViewRecycled(holder: VH) {
        holder.row?.let { scrollState[it.key] = holder.list.layoutManager?.onSaveInstanceState() }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        var row: Row? = null
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById<RecyclerView>(R.id.rowList).apply {
            layoutManager = StartAlignedLayoutManager(context, RecyclerView.HORIZONTAL)
            setRecycledViewPool(pool)
            itemAnimator = null
        }
    }
}

class CardsAdapter(private val row: Row, private val host: Host) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = row.size
    override fun getItemViewType(position: Int) = row.kind.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val kind = RowKind.entries[viewType]
        if (kind == RowKind.MATCH) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_match, parent, false)
            v.clipToOutline = true
            return MatchVH(v)
        }
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        val (w, h) = when (kind) {
            RowKind.APPS -> 160 to 90
            RowKind.DOCK -> 156 to 88
            RowKind.POSTER -> 112 to 168
            else -> 208 to 117
        }
        v.layoutParams = v.layoutParams.apply { width = v.dp(w); height = v.dp(h) }
        v.clipToOutline = true
        return VH(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is MatchVH -> bindMatch(holder, row.matches[position])
            is VH -> if (row.kind == RowKind.APPS || row.kind == RowKind.DOCK) bindApp(holder, row.apps[position]) else bindMedia(holder, row.media[position])
        }
    }

    private fun bindMatch(h: MatchVH, m: Match) {
        h.league.text = m.league.label
        h.homeLogo.load(m.homeLogo)
        h.awayLogo.load(m.awayLogo)
        h.teams.text = "${m.home}  –  ${m.away}"
        h.live.visibility = if (m.state == "in") View.VISIBLE else View.GONE
        when (m.state) {
            "pre" -> {
                h.center.text = Espn.kickoff(m)
                h.status.text = ""
            }
            else -> {
                h.center.text = "${m.homeScore ?: "0"} - ${m.awayScore ?: "0"}"
                h.status.text = if (m.state == "post") "Finale" else m.detail
            }
        }
        h.itemView.contentDescription = "${m.home} contro ${m.away}"
        h.itemView.setOnClickListener { host.onMatchClicked(m) }
        h.itemView.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(440).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            if (has) host.onMatchFocused(m)
        }
    }

    private fun bindApp(h: VH, app: AppInfo) {
        h.logo.visibility = View.GONE
        h.progressTrack.visibility = View.GONE
        h.badge.visibility = View.GONE
        h.image.dispose()
        if (app.banner != null) {
            h.image.scaleType = ImageView.ScaleType.CENTER_CROP
            h.image.setPadding(0, 0, 0, 0)
            h.image.setImageDrawable(app.banner)
            h.label.visibility = View.GONE
            h.shade.visibility = View.GONE
        } else {
            // icon-only app: icon in the upper part, name underneath
            h.image.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = h.itemView.dp(14)
            h.image.setPadding(p, p / 2, p, p * 2)
            h.image.setImageDrawable(app.icon)
            h.label.visibility = View.VISIBLE
            h.label.text = app.label
            h.label.maxLines = 1
            h.label.textAlignment = View.TEXT_ALIGNMENT_CENTER
            h.label.textSize = 11f
            setLabelBottom(h, 6)
            h.shade.visibility = View.GONE
        }
        h.itemView.contentDescription = app.label
        h.itemView.setOnClickListener { host.onAppClicked(app) }
        h.itemView.setOnLongClickListener { host.onAppMenu(app); true }
        // ☰ on an app opens its menu; consume the key-down too, or the activity also opens the settings pop-up
        h.itemView.setOnKeyListener { _, code, ev ->
            if (code != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_UP) host.onAppMenu(app)
            true
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(440).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            if (has) host.onAppFocused(app)
        }
    }

    private fun bindMedia(h: VH, item: MediaItem) {
        h.image.scaleType = ImageView.ScaleType.CENTER_CROP
        h.image.setPadding(0, 0, 0, 0)
        h.label.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        h.label.maxLines = 2
        h.label.textSize = 13f
        h.itemView.setOnKeyListener(null)
        h.itemView.setOnLongClickListener(null)

        if (item.action == MediaItem.Action.LINK_NUVIO) {
            h.image.dispose()
            h.image.setImageResource(android.R.drawable.ic_menu_rotate)
            h.image.scaleType = ImageView.ScaleType.CENTER
        } else {
            h.image.load(item.image) { crossfade(true) }
        }

        // the title is already shown big at the top when a card is selected: no name on the poster
        // (only the "link Nuvio" card keeps its text)
        val showLabel = item.action == MediaItem.Action.LINK_NUVIO
        h.label.visibility = if (showLabel) View.VISIBLE else View.GONE
        h.shade.visibility = if (showLabel) View.VISIBLE else View.GONE
        h.label.text = item.title

        // title treatment from TMDB on every landscape image (unless the artwork already has its title)
        h.logo.dispose()
        h.logo.setImageDrawable(null)
        h.logoJob?.cancel()
        val wantsLogo = !item.artworkHasTitle && item.action == MediaItem.Action.OPEN && row.kind == RowKind.LANDSCAPE
        h.logo.visibility = if (wantsLogo) View.VISIBLE else View.GONE
        h.shade.visibility = if (showLabel || wantsLogo) View.VISIBLE else View.GONE
        if (wantsLogo) {
            if (item.logo != null) h.logo.load(item.logo) { crossfade(true) }
            // the card may not be attached yet: use the activity's scope (its context)
            else h.logoJob = (h.itemView.context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                val url = runCatching { Tmdb.logoFor(item) }.getOrNull()
                if (url != null) h.logo.load(url) { crossfade(true) } else h.shade.visibility = View.GONE
            }
        }

        h.badge.visibility = if (item.badge != null) View.VISIBLE else View.GONE
        h.badge.text = item.badge
        // "Nuovo" in red, like a notification
        h.badge.setBackgroundResource(if (item.newEpisode) R.drawable.live_bg else R.drawable.badge_bg)

        val p = item.progress
        if (p != null) {
            h.progressTrack.visibility = View.VISIBLE
            h.progressTrack.post {
                h.progressFill.layoutParams = h.progressFill.layoutParams.apply { width = (h.progressTrack.width * p).toInt() }
            }
            setLabelBottom(h, 16)
        } else {
            h.progressTrack.visibility = View.GONE
            setLabelBottom(h, 10)
        }

        h.itemView.contentDescription = item.title
        h.itemView.setOnClickListener { host.onMediaClicked(item) }
        h.itemView.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(440).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            if (has) host.onMediaFocused(item)
        }
    }

    private fun setLabelBottom(h: VH, dp: Int) {
        h.label.layoutParams = (h.label.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = h.itemView.dp(dp) }
    }

    class MatchVH(v: View) : RecyclerView.ViewHolder(v) {
        val league: TextView = v.findViewById(R.id.league)
        val live: View = v.findViewById(R.id.live)
        val homeLogo: ImageView = v.findViewById(R.id.homeLogo)
        val awayLogo: ImageView = v.findViewById(R.id.awayLogo)
        val center: TextView = v.findViewById(R.id.center)
        val status: TextView = v.findViewById(R.id.status)
        val teams: TextView = v.findViewById(R.id.teams)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val shade: View = v.findViewById(R.id.shade)
        val label: TextView = v.findViewById(R.id.label)
        val badge: TextView = v.findViewById(R.id.badge)
        val logo: ImageView = v.findViewById(R.id.logo)
        var logoJob: kotlinx.coroutines.Job? = null
        val progressTrack: View = v.findViewById(R.id.progressTrack)
        val progressFill: View = v.findViewById(R.id.progressFill)
    }
}
