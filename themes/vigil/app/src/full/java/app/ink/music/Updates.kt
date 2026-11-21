package app.ink.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ink music keeping itself up to date (the full build only; the play build's store does this for it). Looks at the
 * latest release at most every six hours: inkhome.app's (tools/site_release.py; GitHub suspended ink's account
 * 2026-11-01, and a site of our own can't be) and GitHub's, the newer of the two that answer; one newer than this is offered on listen, its changelog under it. Taken, the APK comes
 * down to the cache, is checked against the release's SHA256SUMS, and goes to the phone's own
 * installer, which asks, and refuses anything not signed with ink music's key.
 * Most people never came back to GitHub: 619 took 1.4, a few dozen each release after it.
 */
object Updates {
    // (the editions', 2026-11-21) this edition's own: ink music's would offer ink music itself, which this isn't (another id, another
    // key). update.json sits beside the newest apk, shaped as a GitHub release (build.yml writes it, signed builds only);
    // a release download, not GitHub's API, so a hostel's worth of phones behind one address never hits its limit
    private val LATEST = listOf("https://github.com/thanmayroobanathans/ink-music-sunset/releases/download/vigil-latest/update.json")
    // (the editions', 2026-11-21: "push it harder") an hour, not six: update.json is a release file, no limit to how often it's read
    private const val EVERY = 3_600_000L
    /** The app on screen now (Main's start and stop): the installer's question shown at once, else as a notification. */
    @Volatile var foreground = false
    /** Updates put in by themselves (settings), when one's down and nothing's playing: on unless turned off. */
    val autoOn = MutableStateFlow(true)
    val offer = MutableStateFlow<Update?>(null)
    val step = MutableStateFlow<Updating>(Updating.Offered)
    /** What a check asked for in settings found: "looking", "up to date", or why it couldn't look (null: nothing to say). */
    val said = MutableStateFlow<String?>(null)
    /** Whether this build looks for its own updates (settings' "check for an update" shown). */
    val manual: Boolean get() = !BuildConfig.DEBUG
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // who asked for it, "(#12, #15)": on GitHub, not on the card
    private val asked = Regex("""\s*\(#\d+(,\s*#\d+)*\)""")

    // when it last looked, and the version put aside with "not now" (the next one's offered again)
    private fun file(c: Context) = File(c.filesDir, "update.json")
    private fun kept(c: Context) = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())

    /**
     * Looks for a newer release: by itself (listen, at most every six hours, a version put aside left aside), or `now`,
     * asked in settings (2026-11-07): looked for at once, whatever was put aside, and said what it found.
     */
    fun check(c: Context, now: Boolean = false) {
        if (BuildConfig.DEBUG) return  // a debug build has its own key: the installer would refuse it
        if (!BuildConfig.UPDATER) return  // F-Droid's build: F-Droid brings the next one
        scope.launch {
            if (now) said.value = "looking"
            val k = kept(c)
            val seen = k.optJSONObject("seen")
            val fresh = !now && System.currentTimeMillis() - k.optLong("at") < EVERY
            val r = (if (fresh) seen else null) ?: latest() ?: run { if (now) said.value = "couldn't reach the updates. try again in a moment"; return@launch }
            if (!fresh) file(c).writeText(k.put("at", System.currentTimeMillis()).put("seen", r).toString())
            val version = r.optString("tag_name").removePrefix("v")
            if (!newer(version, BuildConfig.VERSION_NAME) || (!now && version == k.optString("aside"))) { if (now) said.value = "up to date"; return@launch }
            if (now) said.value = null
            val assets = r.optJSONArray("assets") ?: return@launch
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            // the full build's APK (a play build on GitHub would silence YouTube: release.sh keeps it off)
            val apk = all.firstOrNull { it.optString("name").let { n -> n.endsWith(".apk") && "play" !in n } } ?: return@launch
            val notes = r.optString("body").lines().map { it.trim().removePrefix("-").trim().replace(asked, "") }.filter { it.isNotEmpty() }
            offer.value = Update(version, notes, apk.getString("browser_download_url"), apk.getString("name"),
                all.firstOrNull { it.optString("name") == "SHA256SUMS" }?.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    /**
     * Looked for in the background too (the editions', 2026-11-21): every four hours, with a network; a newer one's APK comes down
     * ahead on wifi (so UPDATE is at once), then it goes in by itself if that's allowed, nothing's playing and the
     * phone lets the app update itself without asking (Android 12 on, once it's put one in); else a notification
     * says it's ready.
     */
    fun schedule(c: Context) {
        if (BuildConfig.DEBUG || !BuildConfig.UPDATER) return
        autoOn.value = kept(c).optBoolean("auto", true)
        val js = c.getSystemService(android.app.job.JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB) != null) return
        js.schedule(android.app.job.JobInfo.Builder(JOB, android.content.ComponentName(c, UpdateJob::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(4 * 3_600_000L, 3_600_000L)
            .build())
    }
    private const val JOB = 4711

    fun setAuto(c: Context, on: Boolean) {
        autoOn.value = on
        scope.launch { runCatching { file(c).writeText(kept(c).put("auto", on).toString()) } }
    }

    /** What a release offers: newer than this, its APK (null: nothing to offer). */
    private fun offerOf(r: JSONObject): Update? {
        val version = r.optString("tag_name").removePrefix("v")
        if (!newer(version, BuildConfig.VERSION_NAME)) return null
        val assets = r.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val apk = all.firstOrNull { it.optString("name").let { n -> n.endsWith(".apk") && "play" !in n } } ?: return null
        val notes = r.optString("body").lines().map { it.trim().removePrefix("-").trim().replace(asked, "") }.filter { it.isNotEmpty() }
        return Update(version, notes, apk.getString("browser_download_url"), apk.getString("name"),
            all.firstOrNull { it.optString("name") == "SHA256SUMS" }?.getString("browser_download_url"), apk.optLong("size"))
    }

    /** The APK for [u], down and checked already (the background's), or null. */
    private fun ready(c: Context, u: Update): File? {
        val apk = File(c.cacheDir, "update.apk")
        if (!apk.exists() || kept(c).optString("ready") != u.version) return null
        val want = u.sums?.let { s -> runCatching { get(s) }.getOrNull()?.lines()?.firstOrNull { it.trim().endsWith(u.name) }?.substringBefore(' ')?.trim() }
        return apk.takeIf { want == null || want.equals(sha256(it), ignoreCase = true) }
    }

    /** The background's look (UpdateJob): blocking. */
    fun background(c: Context) {
        if (BuildConfig.DEBUG || !BuildConfig.UPDATER) return
        val r = latest() ?: return
        val k = kept(c)
        file(c).writeText(k.put("at", System.currentTimeMillis()).put("seen", r).toString())
        val u = offerOf(r) ?: return
        if (u.version == k.optString("aside")) return
        offer.value = u
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = cm?.isActiveNetworkMetered == false
        var apk = ready(c, u)
        if (apk == null && wifi) runCatching {
            val to = File(c.cacheDir, "update.apk")
            download(u, to)
            u.sums?.let { s ->
                val want = get(s).lines().firstOrNull { it.trim().endsWith(u.name) }?.substringBefore(' ')?.trim()
                if (want == null || !want.equals(sha256(to), ignoreCase = true)) error("the download doesn't match")
            }
            file(c).writeText(kept(c).put("ready", u.version).toString())
            apk = to
        }.onFailure { Log.w("ink-update", "background download: $it") }
        val playing = c.getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true
        val own = runCatching { c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName == c.packageName }.getOrDefault(false)
        val down = apk
        if (down != null && autoOn.value && kept(c).optBoolean("auto", true) && !playing && !foreground && own && android.os.Build.VERSION.SDK_INT >= 31) {
            Log.i("ink-update", "putting ${u.version} in by itself")
            runCatching { install(c, down) }.onFailure { notify(c, u, null) }
        } else if (u.version != k.optString("told")) {
            notify(c, u, null)
            file(c).writeText(kept(c).put("told", u.version).toString())
        }
    }

    /** "<app> <build> is ready": tapped, the app (its update over the mini player); [ask], the installer's own question. */
    private fun notify(c: Context, u: Update, ask: Intent?) {
        val nm = c.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(android.app.NotificationChannel("updates", "Updates", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val app = c.applicationInfo.loadLabel(c.packageManager).toString()
        val open = ask ?: (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val build = u.version.substringAfterLast('.')
        nm.notify(7, android.app.Notification.Builder(c, "updates")
            .setSmallIcon(R.drawable.icon_sunset_mono)
            .setContentTitle(if (ask != null) "Tap to finish updating $app" else "$app build $build is ready")
            .setContentText(u.notes.firstOrNull() ?: "Tap to update")
            .setStyle(android.app.Notification.BigTextStyle().bigText(u.notes.take(3).joinToString("\n") { "· $it" }))
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    fun notNow(c: Context) {
        val u = offer.value ?: return
        offer.value = null
        scope.launch { runCatching { file(c).writeText(kept(c).put("aside", u.version).toString()) } }
    }

    fun take(c: Context) {
        val u = offer.value ?: return
        if (step.value is Updating.Down || step.value == Updating.Asking) return
        step.value = Updating.Down(0f)
        scope.launch {
            runCatching {
                val apk = ready(c, u) ?: File(c.cacheDir, "update.apk").also { download(u, it) }
                // the sum the release says, or no install: a cut-short download is caught here, not by the installer
                u.sums?.let { s ->
                    val want = get(s).lines().firstOrNull { it.trim().endsWith(u.name) }?.substringBefore(' ')?.trim()
                    if (want == null || !want.equals(sha256(apk), ignoreCase = true)) error("the download doesn't match")
                }
                step.value = Updating.Asking
                install(c, apk)
            }.onFailure {
                Log.w("ink-update", "update failed: $it")
                step.value = Updating.Failed(if (it is IllegalStateException) it.message.orEmpty() else "couldn't download it")
            }
        }
    }

    private fun download(u: Update, to: File) {
        val conn = URL(u.apk).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: u.size
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0) step.value = Updating.Down((got.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            // once ink music is the phone's installer of record for itself, later updates can go in without asking
            // (from Android 12; 11 asks each time)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("ink-music.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val told = PendingIntent.getBroadcast(c, id, Intent(c, Installed::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(told.intentSender)
        }
    }

    /** What the installer says: it wants asking (its screen shown), or it's done or refused. */
    class Installed : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val ask = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    // the app away (the editions', 2026-11-21: put in from the background): the question waits in a notification
                    if (!foreground) offer.value?.let { notify(c, it, ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: Unit
                    else c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> { offer.value = null; step.value = Updating.Offered }
                // turned down on the installer's screen: offered again, to install when it suits
                PackageInstaller.STATUS_FAILURE_ABORTED -> step.value = Updating.Offered
                else -> {
                    Log.w("ink-update", "installer: $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    step.value = Updating.Failed("the phone wouldn't install it")
                }
            }
        }
    }

    /** The newest release of those that answer (the same shape from each: GitHub's). */
    private fun latest(): JSONObject? = LATEST.mapNotNull { u ->
        runCatching { JSONObject(get(u)) }.onFailure { Log.w("ink-update", "couldn't look at $u: $it") }.getOrNull()
    }.reduceOrNull { a, b -> if (newer(b.optString("tag_name").removePrefix("v"), a.optString("tag_name").removePrefix("v"))) b else a }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** 1.4.10 is newer than 1.4.9: each part as a number. */
    internal fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
