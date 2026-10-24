package com.nello.tvhome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.View
import java.io.File

/**
 * The soft tick when the focus moves (and a slightly lower one on OK). Generated once as a tiny
 * WAV — no sound files in the app — and played through a SoundPool, so it costs nothing.
 */
object NavSound {
    private var pool: SoundPool? = null
    private var move = 0
    private var select = 0
    private var last = 0L
    @Volatile var enabled = true

    fun init(c: Context) {
        enabled = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("navSounds", true)
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        move = p.load(wav(c, "tick_move.wav", 1900.0, 0.022), 1)
        select = p.load(wav(c, "tick_select.wav", 1250.0, 0.045), 1)
        pool = p
    }

    /** A short sine blip with a fast decay, as 16-bit mono PCM. */
    private fun wav(c: Context, name: String, freq: Double, seconds: Double): String {
        val f = File(c.cacheDir, name)
        if (f.exists()) return f.path
        val rate = 44100
        val n = (rate * seconds).toInt()
        val data = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = Math.exp(-t / (seconds / 4)) * Math.min(1.0, i / 40.0)
            data.putShort((Math.sin(2 * Math.PI * freq * t) * env * 9000).toInt().toShort())
        }
        f.writeBytes(data.array())
        return f.path
    }

    fun move() = play(move, 0.28f)
    fun select() = play(select, 0.32f)

    private fun play(id: Int, vol: Float) {
        if (!enabled || id == 0) return
        val now = System.currentTimeMillis()
        if (now - last < 45) return
        last = now
        pool?.play(id, vol, vol, 1, 0, 1f)
    }

    /** Tick on every focus move inside [root]'s window. */
    fun attach(root: View) {
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new -> if (old != null && new != null && old !== new) move() }
    }
}
