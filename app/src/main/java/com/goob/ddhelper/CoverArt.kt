package com.goob.ddhelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Portrait cover art (600x900, 2:3) for a Steam game, cached in the app's files.
 *
 * Source order: the cached copy, Steam's public image CDN (no API key, the same library capsule the
 * Steam client shows), then the copy in DroidDeck's own Steam cache, for offline use.
 */
object CoverArt {
    private val CDN = listOf(
        "https://shared.steamstatic.com/store_item_assets/steam/apps/%d/library_600x900.jpg",
        "https://cdn.cloudflare.steamstatic.com/steam/apps/%d/library_600x900.jpg",
    )

    private fun cacheFile(ctx: Context, appId: Long) = File(ctx.filesDir, "art/$appId.jpg")

    private fun decode(f: File): Bitmap? =
        if (f.isFile && f.length() > 0) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null

    /** Cached art only: instant, no network or root. */
    fun cached(ctx: Context, appId: Long): Bitmap? = decode(cacheFile(ctx, appId))

    /** Downloads from Steam's CDN. Returns null when offline or the game has no portrait capsule. */
    fun download(ctx: Context, appId: Long): Bitmap? {
        val dest = cacheFile(ctx, appId)
        dest.parentFile?.mkdirs()
        for (pattern in CDN) {
            val tmp = File(dest.parentFile, "$appId.part")
            try {
                val conn = URL(pattern.format(appId)).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "DDThorHelper")
                try {
                    if (conn.responseCode != 200 || conn.contentType?.startsWith("image/") != true) continue
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                // Only keep it if it really is an image.
                if (decode(tmp) != null && tmp.renameTo(dest)) return decode(dest)
            } catch (_: Exception) {
                // Try the next mirror.
            } finally {
                tmp.delete()
            }
        }
        return null
    }

    /** Copies the art out of DroidDeck's Steam library cache, through the granted DroidDeck folder. */
    fun fromDroidDeck(ctx: Context, dd: DroidDeck, appId: Long): Bitmap? {
        val src = dd.findArt(appId) ?: return null
        val bytes = Storage.readBytes(src) ?: return null
        val dest = cacheFile(ctx, appId)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        return decode(dest)
    }
}
