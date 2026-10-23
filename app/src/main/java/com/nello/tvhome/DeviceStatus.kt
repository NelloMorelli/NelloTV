package com.nello.tvhome

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/** Free storage and RAM (shown in the Wi-Fi pop-up). */
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
}
