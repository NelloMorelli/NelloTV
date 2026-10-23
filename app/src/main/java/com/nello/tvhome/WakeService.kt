package com.nello.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS shows Amazon's home after standby, and Home on Fire only redirects the Home button.
 * Right after the screen turns on, if Amazon's launcher comes to the front, bring Nello TV back.
 */
class WakeService : AccessibilityService() {

    private var screenOnAt = 0L
    private var notifier: Notifier? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOnAt = System.currentTimeMillis()
        }
    }

    override fun onServiceConnected() {
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        notifier = Notifier(this).also { it.start() }
        // banner when headphones connect (e.g. switched on and reconnected by themselves)
        registerReceiver(object : BroadcastReceiver() {
            @android.annotation.SuppressLint("MissingPermission")
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1) != android.bluetooth.BluetoothProfile.STATE_CONNECTED) return
                val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                notifier?.show("${BluetoothAudio.pretty(d)} collegate", "L'audio della TV ora esce dalle cuffie", null) {}
            }
        }, IntentFilter(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Amazon's screensaver started (inactivity in another app): cover it with ours
        if (event.packageName == AMAZON_AMBIENT && LauncherPrefs(this).screensaver) {
            startActivity(Intent(this, ScreensaverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            return
        }
        if (event.packageName != AMAZON_HOME) return
        // only just after waking up: opening Amazon's home on purpose later still works
        if (System.currentTimeMillis() - screenOnAt > 8_000) return
        screenOnAt = 0
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    override fun onInterrupt() {}

    /** Only called while a banner with an action is visible: OK opens it, other keys pass. */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val n = notifier ?: return false
        if (!n.isShowing) return false
        val ok = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
        if (!ok) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) n.open()
        return true
    }

    /** Close Amazon's screensaver left under ours. */
    fun dismissAmbient() {
        android.os.Handler(mainLooper).postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 150)
    }

    /** Standby after the screensaver's time limit (TVs treat "lock screen" as sleep). */
    fun sleep() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        notifier?.stop()
        instance = null
        super.onDestroy()
    }

    companion object {
        /** for the "test notification" button in the settings */
        var instance: WakeService? = null
        fun testBanner() = instance?.notifier?.show("Napoli – Inter", "Serie A · tra 10 min su DAZN 1", null, hint = "Premi OK per guardarla") {
            instance?.let { Sportio.watch(it, "DAZN 1") }
        }

        private const val AMAZON_HOME = "com.amazon.tv.launcher"
        private const val AMAZON_AMBIENT = "com.amazon.tv.ftvambient"
    }
}
