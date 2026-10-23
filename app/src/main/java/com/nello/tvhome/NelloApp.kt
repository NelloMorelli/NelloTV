package com.nello.tvhome

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/**
 * Image loading tuned for a 1.6 GB Fire TV: posters in RGB_565 (half the memory, no visible
 * difference on a TV), a small memory cache, and everything released when another app is in use.
 */
class NelloApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        Net.init(this)
        Thread { Net.trim() }.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .allowRgb565(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        // posters, backdrops and logos stay on disk: the home opens without downloading them again
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        // images don't change at their address: use the disk copy without asking the server
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI hidden (you're in Nuvio, Netflix…) or the system is short on memory: give it back
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        }
    }
}
