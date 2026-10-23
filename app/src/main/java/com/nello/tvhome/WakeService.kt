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
        // make sure key filtering is on (needed for ☰ on the banners)
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        notifier = Notifier(this).also { it.start() }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
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

    /** While a banner is up, ☰ opens it (other keys pass through untouched). */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val n = notifier ?: return false
        if (!n.isShowing || event.keyCode != android.view.KeyEvent.KEYCODE_MENU) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) n.open()
        return true
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
        fun testBanner() = instance?.notifier?.show("Napoli – Inter", "Serie A · tra 10 minuti · 20:45", null) {}

        private const val AMAZON_HOME = "com.amazon.tv.launcher"
    }
}
