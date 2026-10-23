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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOnAt = System.currentTimeMillis()
        }
    }

    override fun onServiceConnected() {
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
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

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val AMAZON_HOME = "com.amazon.tv.launcher"
    }
}
