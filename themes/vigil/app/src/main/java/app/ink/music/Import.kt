package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bringing playlists in from Spotify, by a file the listener already has the right to (Spotify's own API is
 * closed to small apps, and its pages aren't ours to read): a CSV from Exportify (exportify.app: one playlist a
 * file, or all of them zipped as CSVs), or the playlists file of Spotify's own data download ("Playlist1.json",
 * every playlist in one). Each song matched to one that plays: your own (the phone's, your server's) first, else
 * YouTube Music's (the full build). Then made where you keep playlists, or played as it is.
 */
object Import {
    /** A song as the file says it. */
    data class Said(val title: String, val artist: String, val album: String)

    /** A playlist as the file has it. */
    data class Listed(val name: String, val songs: List<Said>)

    /** One of your playlists on Spotify, signed in (the full build's [Spotify]): its id, name and whose it is. */
    data class Theirs(val id: String, val name: String, val by: String)

    /**
     * The playlists in a file: one for an Exportify CSV, one a CSV for Exportify's "export all" (a zip; the
     * editions', 2026-11-21: Spotify's page turned the app's sign-in away on a tablet, Exportify it is), every one
     * for Spotify's own download. [named]: the name the file came with, where [uri] no longer says it.
     */
    suspend fun read(c: Context, uri: Uri, named: String? = null): List<Listed> = withContext(Dispatchers.IO) {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext emptyList()
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return@withContext zip(bytes)
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val name = listName(named ?: shownName(c, uri).orEmpty())
        if (text.trimStart().startsWith("{")) spotify(text) else listOfNotNull(csv(text, name))
    }

    /** A file's name as a playlist's: no extension, spaces for underscores, no " (1)" the browser added to a second. */
    private fun listName(file: String) = file.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
        .replace(Regex(" \\(\\d+\\)$"), "").trim().ifEmpty { "from spotify" }

    /** The name a picked or opened file shows (its provider's), or null. */
    private fun shownName(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Exportify's "export all": a zip of CSVs, a playlist each (its saved albums, no songs, left out). */
    private fun zip(bytes: ByteArray): List<Listed> {
        val out = mutableListOf<Listed>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/')
                if (e.isDirectory) continue
                val text = runCatching { z.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF") }.getOrNull() ?: continue
                when {
                    n.endsWith(".csv", true) -> csv(text, listName(n))?.let { out += it }
                    n.endsWith(".json", true) && text.trimStart().startsWith("{") -> out += runCatching { spotify(text) }.getOrDefault(emptyList())
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** A playlist file opened with the app (Exportify's download, "open with", or shared to it), copied in. */
    data class Incoming(val uri: Uri, val name: String)

    /** The file the import page is to take (the app opens it there), or null. */
    val incoming = kotlinx.coroutines.flow.MutableStateFlow<Incoming?>(null)

    /**
     * An intent with a file in it (VIEW's data, SEND's stream): copied in at once (the leave to read it can end with
     * the screen it came to), and the import page told. True if it held one.
     */
    fun take(c: Context, intent: android.content.Intent?): Boolean {
        val uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> stream(intent)
            else -> null
        } ?: return false
        if (uri.scheme != "content") return false
        return runCatching {
            val name = shownName(c, uri) ?: uri.lastPathSegment.orEmpty()
            val f = java.io.File(c.cacheDir, "incoming-${System.currentTimeMillis()}")
            c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            incoming.value = Incoming(Uri.fromFile(f), name)
            true
        }.onFailure { android.util.Log.w("ink-import", "an opened file: $it") }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun stream(i: android.content.Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(android.content.Intent.EXTRA_STREAM)

    /** Spotify's own download: {"playlists": [{"name", "items": [{"track": {"trackName", "artistName", "albumName"}}]}]}. */
    private fun spotify(text: String): List<Listed> {
        val lists = JSONObject(text).optJSONArray("playlists") ?: return emptyList()
        return (0 until lists.length()).mapNotNull { i ->
            val l = lists.optJSONObject(i) ?: return@mapNotNull null
            val items = l.optJSONArray("items") ?: JSONArray()
            val songs = (0 until items.length()).mapNotNull { j ->
                val t = items.optJSONObject(j)?.optJSONObject("track") ?: return@mapNotNull null
                Said(t.optString("trackName"), t.optString("artistName"), t.optString("albumName")).takeIf { it.title.isNotBlank() }
            }
            Listed(l.optString("name").ifEmpty { "playlist ${i + 1}" }, songs).takeIf { songs.isNotEmpty() }
        }
    }

    /** The same artist, near enough (YouTube's first answer can be someone else's song of the same name). */
    private fun same(a: String, b: String): Boolean {
        fun n(x: String) = x.lowercase().substringBefore(",").substringBefore(" & ").filter { it.isLetterOrDigit() }
        val x = n(a); val y = n(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /** Exportify's CSV: a header row naming its columns ("Track Name", "Artist Name(s)", "Album Name", ...). */
    private fun csv(text: String, name: String): Listed? {
        val rows = rows(text)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        fun col(vararg names: String) = head.indexOfFirst { h -> names.any { h == it } }
        val title = col("track name", "title", "name").takeIf { it >= 0 } ?: return null
        val artist = col("artist name(s)", "artist name", "artist", "artists")
        val album = col("album name", "album")
        val songs = rows.drop(1).mapNotNull { r ->
            val by = r.getOrNull(artist).orEmpty().substringBefore(';').substringBefore(", ")
                .let { a -> if (Regex(",\\S").containsMatchIn(a)) a.substringBefore(',') else a }
            Said(r.getOrNull(title).orEmpty(), by.trim(), r.getOrNull(album).orEmpty())
                .takeIf { it.title.isNotBlank() }
        }
        return Listed(name, songs).takeIf { songs.isNotEmpty() }
    }

    /** CSV rows, quotes and all ("a, b" and "say ""hi""" kept whole; a quoted field may hold a line break). */
    private fun rows(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>(); val field = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row.add(field.toString()); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotEmpty() }) out.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString()); if (row.any { it.isNotEmpty() }) out.add(row)
        return out
    }

    /**
     * Each song as one that plays, in order (null where none was found): your own first (by title and artist,
     * the way the indie chart matches), else YouTube Music's, eight asked at a time; [found] told as it goes.
     */
    suspend fun match(songs: List<Said>, found: (Int) -> Unit = {}): List<Song?> = coroutineScope {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        var done = 0
        songs.chunked(8).flatMap { batch ->
            batch.map { s ->
                async(Dispatchers.IO) {
                    (mine[Radio.key(s.title, s.artist)] ?: if (YouTubeMusic.available) tube(s) else null)
                        .also { synchronized(this@Import) { done++; found(done) } }
                }
            }.awaitAll()
        }.also { all -> android.util.Log.i("ink-import", "found ${all.count { it != null }} of ${songs.size}") }
    }

    /**
     * YouTube Music's song of it (by the same artist), else a video of it on youtube.com (the editions', 2026-11-21): a Spotify local
     * file (a video's sound, saved: no artist) is found by its title, and a song only ever up as a video by its words.
     */
    private fun tube(s: Said): Song? =
        (if (s.artist.isNotBlank()) runCatching { YouTubeMusic.find(s.title, s.artist)?.takeIf { same(it.artist, s.artist) } }.getOrNull() else null)
            ?: runCatching { YouTubeMusic.findVideo(s.title, s.artist) }.getOrNull()
                ?.also { android.util.Log.i("ink-import", "\"${s.title}\": its video, \"${it.title}\"") }
}
