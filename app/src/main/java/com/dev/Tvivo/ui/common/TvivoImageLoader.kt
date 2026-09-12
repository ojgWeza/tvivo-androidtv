package com.dev.Tvivo.ui.common

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.EventListener
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.dev.Tvivo.diagnostics.DiagnosticLog
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Card sizes are inputs to this pipeline, not styling: posters are downsampled to the
 * card's pixel size *before* caching, so changing a card size means re-encoding the
 * whole cache.
 *
 * The numbers are numbers, not adjectives. At ~25–35 KB per downsampled poster,
 * browsing a third of a 48,751-title catalog reaches ~500 MB and the full catalog
 * ~1.5 GB, on a box that may have 8 GB of total flash.
 */
object TvivoImageLoader {

    /** Movies and series. Crop to fill — a poster is 2:3 and cropping is safe. */
    const val POSTER_WIDTH_PX = 220
    const val POSTER_HEIGHT_PX = 330

    /** Live channel art. Letterboxed on a neutral tile, never cropped: cropping a
     *  channel logo to 2:3 destroys it. */
    const val CHANNEL_WIDTH_PX = 220
    const val CHANNEL_HEIGHT_PX = 124

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .eventListener(object : EventListener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    // Request data is normally a provider URL, so log the failure class only.
                    DiagnosticLog.warn("Image", "load failed", "error=${result.throwable.javaClass.simpleName}")
                }
            })
            .bitmapConfig(Bitmap.Config.RGB_565)
            // Animated fades during D-pad scroll read as flicker at 3 m.
            .crossfade(false)
            .memoryCache {
                val maxMemory = Runtime.getRuntime().maxMemory()
                val eighth = (maxMemory / 8).coerceAtMost(32L * 1024 * 1024)
                MemoryCache.Builder(context).maxSizeBytes(eighth.toInt()).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()
}
