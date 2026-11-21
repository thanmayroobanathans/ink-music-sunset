package app.ink.music

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What you've made here kept apart from the app (the editions', 2026-11-21: "the music listening history, all search history is
 * gone if you uninstall cause it's local"): what you've heard and searched, loved and made, your places and settings,
 * zipped to Downloads/<app>/<app> backup.zip every two days and whenever asked, and brought back from that file in
 * settings (listening > your data). What can be fetched again stays out: songs kept offline, podcasts' episodes, lyrics,
 * the song cache, the sign-in browser's own files. Android's own backup (Google's) keeps the same, by
 * res/xml/data_rules.xml, and puts it back by itself when the app's installed again.
 */
object Backup {
    private val TOPS = listOf("files", "shared_prefs", "databases")
    /** Fetched again, or this file's own note of where it wrote last (another install doesn't own that file). */
    private val SKIP = listOf("files/offline", "files/offline-mp3", "files/podcasts", "files/lyrics", "shared_prefs/backup.xml", "databases/exoplayer")
    private const val BIG = 20L shl 20
    private const val EVERY = 2 * 86_400_000L
    /** What the last ask said (backing up, saved, why not), for settings. */
    val said = MutableStateFlow<String?>(null)

    private fun label(c: Context) = c.applicationInfo.loadLabel(c.packageManager).toString()
    private fun name(c: Context) = "${label(c)} backup.zip"
    private fun prefs(c: Context) = c.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** When it last backed up, in words ("today", "2 days ago"), or "not yet". */
    fun last(c: Context): String {
        val at = prefs(c).getLong("at", 0L)
        if (at == 0L) return "not yet"
        val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
        return when (days) { 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" }
    }

    private fun kept(f: File, rel: String) = SKIP.none { rel == it || rel.startsWith("$it/") || rel.startsWith(it) && it.startsWith("databases/") } && f.length() <= BIG

    /** All that's kept, zipped into [out]: how many files. */
    private fun write(c: Context, out: java.io.OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write(JSONObject().put("app", c.packageName).put("version", BuildConfig.VERSION_NAME).put("at", System.currentTimeMillis()).toString().toByteArray())
            z.closeEntry()
            for (top in TOPS) {
                val dir = File(c.dataDir, top)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = top + "/" + f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    if (!kept(f, rel)) return@forEach
                    // read whole first: one being written as it's read is passed over, never cut short in the zip
                    val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                    z.putNextEntry(ZipEntry(rel)); z.write(bytes); z.closeEntry(); n++
                }
            }
        }
        return n
    }

    /** Into Downloads/<app>/, over the one this install wrote there last (else a new one): where, or null. */
    suspend fun toDownloads(c: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = c.contentResolver
            val p = prefs(c)
            var n = p.getString("uri", null)?.let { u -> runCatching { r.openOutputStream(Uri.parse(u), "wt")!!.use { write(c, it) } }.getOrNull() }
            if (n == null) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name(c))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/${label(c)}")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("no room in downloads")
                n = r.openOutputStream(uri, "w")!!.use { write(c, it) }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                p.edit().putString("uri", uri.toString()).apply()
            }
            p.edit().putLong("at", System.currentTimeMillis()).apply()
            Log.i("ink-backup", "backed up: $n files")
            "downloads/${label(c).lowercase()}"
        }.onFailure { Log.w("ink-backup", "couldn't back up: $it") }.getOrNull()
    }

    /** By itself, every two days (the app opened, once there's something to keep). */
    suspend fun auto(c: Context) {
        if (System.currentTimeMillis() - prefs(c).getLong("at", 0L) < EVERY) return
        if (!File(c.filesDir, "heard.jsonl").exists() && !File(c.filesDir, "searches.json").exists()) return
        toDownloads(c)
    }

    /**
     * The app as the backup at [uri] had it: its files put back (the player stopped first, so nothing it holds is
     * written over them), then the app started again. Null when done, else why not.
     */
    suspend fun restore(c: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val all = LinkedHashMap<String, ByteArray>()
        val read = runCatching {
            ZipInputStream(c.contentResolver.openInputStream(uri)!!.buffered()).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) all[e.name] = z.readBytes() }
            }
        }
        if (read.isFailure) return@withContext "that file couldn't be read"
        val head = all["backup.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return@withContext "that isn't a backup from this app"
        if (!head.optString("app").startsWith("app.ink.music")) return@withContext "that backup is another app's"
        runCatching { c.stopService(Intent(c, PlayerService::class.java)) }
        c.getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses
            ?.filter { it.processName.endsWith(":player") }?.forEach { android.os.Process.killProcess(it.pid) }
        Thread.sleep(400)
        val root = c.dataDir.canonicalFile
        var n = 0
        for ((name, bytes) in all) {
            if (name == "backup.json" || TOPS.none { name.startsWith("$it/") }) continue
            val f = File(root, name).canonicalFile
            if (!f.path.startsWith(root.path + File.separator)) continue   // nothing written outside the app's own
            f.parentFile?.mkdirs()
            f.writeBytes(bytes); n++
        }
        Log.i("ink-backup", "brought back: $n files, from ${head.optString("version")}")
        withContext(Dispatchers.Main) {
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            Runtime.getRuntime().exit(0)
        }
        null
    }
}
