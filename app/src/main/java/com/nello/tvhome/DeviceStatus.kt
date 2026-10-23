package com.nello.tvhome

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/** Free storage / RAM, and closing apps left open in the background. */
object DeviceStatus {

    fun gb(bytes: Long) = String.format(Locale.ITALY, "%.1f GB", bytes / 1_073_741_824.0)

    fun storage(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return st.availableBytes to st.totalBytes
    }

    fun memory(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem to mi.totalMem
    }

    /**
     * Closes every installed app's background processes except Nuvio, the launcher itself and
     * the system. Returns how much RAM came back.
     */
    fun freeMemory(context: Context, apps: Apps): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val before = memory(context).first
        val keep = setOf(Apps.NUVIO, context.packageName)
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { it.packageName !in keep && (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .forEach { runCatching { am.killBackgroundProcesses(it.packageName) } }
        // Amazon's own heavy helpers that are safe to close
        listOf("com.amazon.avod", "com.amazon.firebat", "com.amazon.venezia", "com.amazon.imp")
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
        Thread.sleep(800)
        return (memory(context).first - before).coerceAtLeast(0)
    }
}
