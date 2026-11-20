package app.ink.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache

/**
 * The songs YouTube Music streams, kept on the phone as they play (the editions', 2026-11-21: "the android app doesn't have good
 * cache memory": every play, and every seek back, came down from YouTube again). Kept by the song ("ytm://<id>", never
 * the stream's address, which changes every few hours), the oldest let go first, up to a tenth of the phone's free
 * room (256 MB at the least, 1 GB at the most). Played again, a song starts and seeks at once, costs no data, and plays
 * with no signal. In the app's cache folder: Android's "clear cache" empties it. Songs kept for offline aren't here
 * (they're Offline's); nor the server's, the radio or episodes (they pass by).
 */
object StreamCache {
    @Volatile private var cache: SimpleCache? = null

    @Synchronized private fun cache(c: Context): SimpleCache? = cache ?: runCatching {
        val dir = java.io.File(c.cacheDir, "streams")
        val free = runCatching { c.cacheDir.usableSpace }.getOrDefault(0L)
        val most = (free / 10).coerceIn(256L shl 20, 1L shl 30)
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(most), androidx.media3.database.StandaloneDatabaseProvider(c)).also {
            android.util.Log.i("ink-cache", "songs kept up to ${most shr 20} MB, ${it.cacheSpace shr 20} MB kept now")
        }
    }.onFailure { android.util.Log.w("ink-cache", "no keeping: $it") }.getOrNull()?.also { cache = it }

    /** [upstream] (the player's own: the stream found as it starts), with YouTube Music's songs kept as they come. */
    fun factory(c: Context, upstream: DataSource.Factory): DataSource.Factory {
        val kept = cache(c) ?: return upstream
        val cached = CacheDataSource.Factory().setCache(kept).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    android.util.Log.d("ink-cache", "played from the phone: ${cachedBytesRead shr 10} kB")
                }
                override fun onCacheIgnored(reason: Int) { android.util.Log.d("ink-cache", "passed by: $reason") }
            })
        return DataSource.Factory { Picking(cached.createDataSource(), upstream.createDataSource()) }
    }

    /** Kept or not, a song at a time: YouTube Music's through the keeping, everything else straight. */
    private class Picking(private val kept: DataSource, private val straight: DataSource) : DataSource {
        private var now: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            kept.addTransferListener(transferListener); straight.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val d = if (dataSpec.uri.scheme == "ytm") kept else straight
            now = d
            return d.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = now?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = now?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = now?.responseHeaders ?: emptyMap()
        override fun close() { try { now?.close() } finally { now = null } }
    }
}
