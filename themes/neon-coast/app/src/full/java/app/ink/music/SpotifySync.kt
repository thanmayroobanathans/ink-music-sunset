package app.ink.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A Spotify playlist brought in and made on YouTube Music, kept in step (the editions', 2026-11-20): what's added to it
 * on Spotify is found and added there too, as the app opens (an hour apart at least). Nothing is taken out: a song gone
 * from Spotify stays on YouTube Music, yours to take out.
 */
object SpotifySync {
    private data class Kept(val spotify: String, val name: String, val tube: String, val known: Set<String>)
    private const val GAP = 60 * 60_000L
    @Volatile private var running = false

    private fun file(c: Context) = File(c.filesDir, "spotify-sync.json")

    private fun load(c: Context): List<Kept> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val k = o.optJSONArray("known") ?: JSONArray()
            Kept(o.getString("spotify"), o.optString("name"), o.getString("tube"), (0 until k.length()).map { k.getString(it) }.toSet())
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, all: List<Kept>) {
        val a = JSONArray()
        all.forEach { k -> a.put(JSONObject().put("spotify", k.spotify).put("name", k.name).put("tube", k.tube).put("known", JSONArray(k.known.toList()))) }
        runCatching { file(c).writeText(a.toString()) }
    }

    /** How many playlists are kept in step. */
    fun count(c: Context): Int = load(c).size

    /** This playlist from Spotify ([spotify] its id), made on YouTube Music as [tube], kept in step; [songs] what it had. */
    fun keep(c: Context, spotify: String, name: String, tube: TubeList, songs: List<Import.Said>) {
        save(c, load(c).filterNot { it.spotify == spotify } + Kept(spotify, name, tube.id, songs.map { Radio.key(it.title, it.artist) }.toSet()))
    }

    /** Each kept playlist read again from Spotify, and what's new there found and added on YouTube Music. */
    suspend fun run(c: Context, force: Boolean = false) {
        if (running) return
        val stamp = File(c.filesDir, "spotify-sync-at")
        if (!force && System.currentTimeMillis() - stamp.lastModified() < GAP) return
        val all = load(c).ifEmpty { return }
        if (!Spotify.signedIn() || YouTubeAccount.who.value == null) return
        running = true
        try {
            if (!Spotify.fresh(c)) return
            val out = all.map { k ->
                val now = Spotify.songs(k.spotify) ?: return@map k
                val new = now.filter { Radio.key(it.title, it.artist) !in k.known }
                if (new.isEmpty()) return@map k
                val found = Import.match(new).filterNotNull()
                val list = TubeList(k.tube, k.name, "", null)
                var added = 0
                found.forEach { s -> if (YouTubeAccount.addTo(list, s)) added++ }
                android.util.Log.i("ink-spotify", "kept in step, ${k.name}: ${new.size} new on spotify, $added added on youtube music")
                k.copy(known = k.known + new.map { Radio.key(it.title, it.artist) })
            }
            save(c, out)
            runCatching { stamp.writeText(System.currentTimeMillis().toString()) }
        } finally {
            running = false
        }
    }
}
