package com.nello.tvhome

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Bluetooth headphones on the Fire TV: paired audio devices, which one is playing, connect /
 * disconnect. Connecting goes through A2DP's connect(), hidden in the SDK but allowed for apps
 * on Fire OS 8 (tested with a Sony WH-1000XM4: paired, connected, LDAC 96 kHz).
 * The Fire TV plays to one pair at a time: connecting one disconnects the other.
 */
@SuppressLint("MissingPermission")
object BluetoothAudio {

    val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var a2dp: BluetoothA2dp? = null
    private val waiting = mutableListOf<(BluetoothA2dp?) -> Unit>()
    private var requested = false

    /** Run [block] with the A2DP proxy (connects to it the first time). */
    fun withA2dp(context: Context, block: (BluetoothA2dp?) -> Unit) {
        a2dp?.let { return block(it) }
        val a = adapter ?: return block(null)
        waiting += block
        if (requested) return
        requested = true
        a.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                waiting.toList().also { waiting.clear() }.forEach { it(a2dp) }
            }
            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
                requested = false
            }
        }, BluetoothProfile.A2DP)
    }

    /** Headphones, earbuds and speakers — not TVs, set-top boxes or cameras (also "audio/video"). */
    fun isAudio(d: BluetoothDevice): Boolean {
        val c = d.bluetoothClass ?: return false
        if (c.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) return false
        return c.deviceClass in setOf(
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED,
        ) || c.hasService(BluetoothClass.Service.RENDER) && c.deviceClass != BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER &&
            c.deviceClass != BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR
    }

    /** Paired headphones/speakers (not the remote, not game controllers). */
    fun pairedAudio(): List<BluetoothDevice> =
        adapter?.bondedDevices.orEmpty().filter { isAudio(it) }.sortedBy { it.name ?: "" }

    fun connected(): BluetoothDevice? = a2dp?.connectedDevices?.firstOrNull()

    fun name(d: BluetoothDevice) = d.name?.takeIf { it.isNotBlank() } ?: d.address

    private fun call(method: String, d: BluetoothDevice): Boolean = runCatching {
        BluetoothA2dp::class.java.getMethod(method, BluetoothDevice::class.java).invoke(a2dp, d) as Boolean
    }.getOrDefault(false)

    /** Connect [d], dropping whatever else is playing. */
    fun connect(d: BluetoothDevice): Boolean {
        a2dp?.connectedDevices?.filter { it != d }?.forEach { call("disconnect", it) }
        return call("connect", d)
    }

    fun disconnect(d: BluetoothDevice) = call("disconnect", d)

    /** "LDAC", "AAC", "SBC"… of the playing device, if Fire OS tells us. */
    fun codec(d: BluetoothDevice): String? = runCatching {
        val status = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, d)
        val config = status!!.javaClass.getMethod("getCodecConfig").invoke(status)
        config!!.javaClass.getMethod("getCodecName").invoke(config) as String
    }.getOrNull()

    /** Short, friendly name: "Sony WH-1000XM4", "Galaxy Buds Pro". */
    fun pretty(d: BluetoothDevice): String {
        val n = name(d)
        return when {
            n.startsWith("WH-") || n.startsWith("WF-") -> "Sony $n"
            n.contains("Buds", true) && !n.contains("Galaxy", true) -> "Galaxy $n"
            else -> n
        }.replace(Regex("\\s*\\([0-9A-F]{4}\\)$"), "")
    }
}
