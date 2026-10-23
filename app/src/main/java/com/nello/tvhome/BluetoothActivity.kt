package com.nello.tvhome

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "Cerca nuove cuffie": scans for nearby headphones/speakers; OK on one pairs it and, once
 * paired, connects its audio. Put the headphones in pairing mode before opening.
 */
@SuppressLint("MissingPermission")
class BluetoothActivity : GlassPage() {

    private val adapter = BluetoothAudio.adapter
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val rows = HashMap<String, View>()
    private lateinit var status: TextView
    private var pairing: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> d?.let { add(it) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    status.text = if (found.isEmpty()) "Nessuna cuffia trovata · metti le cuffie in modalità associazione e riprova"
                    else "Ricerca terminata · premi OK sulle cuffie da collegare"
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (d == null || d != pairing) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDING -> status.text = "Associo ${BluetoothAudio.pretty(d)}…"
                        BluetoothDevice.BOND_BONDED -> {
                            status.text = "Associate · collego l'audio…"
                            BluetoothAudio.withA2dp(this@BluetoothActivity) { BluetoothAudio.connect(d) }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            status.text = "Associazione non riuscita · riprova con le cuffie in modalità associazione"
                            pairing = null
                        }
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (d == null || d != pairing) return
                    if (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        Toast.makeText(this@BluetoothActivity, "${BluetoothAudio.pretty(d)} collegate", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Cerca nuove cuffie", "Metti le cuffie in modalità associazione (di solito: tieni premuto il tasto di accensione finché la luce lampeggia)")
        status = TextView(this).apply {
            text = "Ricerca in corso…"
            textSize = 14f
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(16), dp(4), dp(16), dp(8))
            isFocusable = true
        }
        list.addView(status)
        BluetoothAudio.withA2dp(this) {}
        registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        })
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Bluetooth non disponibile"
            return
        }
        adapter.cancelDiscovery()
        adapter.startDiscovery()
        status.requestFocus()
    }

    /** Show audio devices only (headphones, speakers), with a name, once each. */
    private fun add(d: BluetoothDevice) {
        if (!BluetoothAudio.isAudio(d) || d.name.isNullOrBlank()) return
        if (d.bondState == BluetoothDevice.BOND_BONDED) return
        val first = found.isEmpty()
        found[d.address] = d
        rows[d.address]?.let { list.removeView(it) }
        val row = item(BluetoothAudio.pretty(d), "Associa ›") { pair(d) }
        rows[d.address] = row
        if (first) {
            status.text = "Premi OK sulle cuffie da collegare"
            row.requestFocus()
        }
    }

    private fun pair(d: BluetoothDevice) {
        adapter?.cancelDiscovery()
        pairing = d
        status.text = "Associo ${BluetoothAudio.pretty(d)}…"
        if (!d.createBond()) status.text = "Associazione non riuscita · riprova"
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { adapter?.cancelDiscovery() }
        super.onDestroy()
    }
}
