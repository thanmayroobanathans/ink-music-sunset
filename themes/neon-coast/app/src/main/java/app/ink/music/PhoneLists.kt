package app.ink.music

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your playlists, on the phone (the editions', 2026-11-21: "the playlist feature is not so good", beside Spotify's).
 * Until now a playlist could only be made on your server or on YouTube Music signed in; most have neither, and had
 * none. These need nothing: any song (yours, YouTube's, a video's, an episode), made from the library, a song's hold,
 * the player, a Spotify playlist brought in. Kept in files/my-lists.json (so the backup and Android's own keep them),
 * their own pictures in files/list-covers/.
 */
object PhoneLists {
    /** A song in a playlist, and when it went in; [k] its own (a song can be in one twice, moved apart). */
    data class Entry(val k: String, val song: Song, val at: Long)

    data class Mine(
        val id: String, val name: String, val about: String, val entries: List<Entry>,
        val made: Long, val changed: Long, val played: Long = 0L, val pinned: Boolean = false, val cover: Boolean = false,
    ) {
        val songs get() = entries.map { it.song }
        val length get() = entries.sumOf { it.song.duration }
        /** When it was last of use: made, changed, played from (the library's "recent"). */
        val recent get() = maxOf(made, changed, played)
    }

    val lists = MutableStateFlow<List<Mine>>(emptyList())
    /** The library's order for them: "recent" (last of use first) or "name". Pinned ones first either way. */
    val order = MutableStateFlow("recent")
    /** Bumped when a playlist's picture changes, so it's read again. */
    val pictures = MutableStateFlow(0)
    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // one write at a time, in the order asked (the latest always last on disk)
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun load(c: Context) {
        dir = c.filesDir
        runCatching {
            val o = JSONObject(File(c.filesDir, "my-lists.json").readText())
            order.value = o.optString("order", "recent")
            val a = o.getJSONArray("lists")
            lists.value = (0 until a.length()).mapNotNull { runCatching { read(a.getJSONObject(it)) }.getOrNull() }
        }
    }

    private fun read(o: JSONObject): Mine {
        val e = o.getJSONArray("songs")
        return Mine(
            o.getString("id"), o.getString("name"), o.optString("about"),
            (0 until e.length()).mapNotNull { i ->
                runCatching { e.getJSONObject(i).let { x -> Entry(x.getString("k"), songOf(x.getJSONObject("song")), x.optLong("at")) } }.getOrNull()
            },
            o.optLong("made"), o.optLong("changed"), o.optLong("played"), o.optBoolean("pinned"), o.optBoolean("cover"),
        )
    }

    private fun write(m: Mine) = JSONObject().put("id", m.id).put("name", m.name).put("about", m.about)
        .put("made", m.made).put("changed", m.changed).put("played", m.played).put("pinned", m.pinned).put("cover", m.cover)
        .put("songs", JSONArray(m.entries.map { JSONObject().put("k", it.k).put("at", it.at).put("song", it.song.json()) }))

    private fun keep() {
        val d = dir ?: return
        val all = lists.value
        val o = order.value
        scope.launch(writer) {
            runCatching { Keep.write(File(d, "my-lists.json"), JSONObject().put("order", o).put("lists", JSONArray(all.map { write(it) })).toString()) }
        }
    }

    private fun entry(s: Song, at: Long) = Entry(java.util.UUID.randomUUID().toString(), s, at)

    @Synchronized private fun edit(id: String, touched: Boolean = true, f: (Mine) -> Mine) {
        lists.value = lists.value.map { if (it.id == id) f(it).let { m -> if (touched) m.copy(changed = System.currentTimeMillis()) else m } else it }
        keep()
    }

    fun get(id: String) = lists.value.firstOrNull { it.id == id }

    /** The library's order: pinned first, then by [order]. */
    fun sorted(all: List<Mine>, by: String) = all.sortedWith(
        compareByDescending<Mine> { it.pinned }.then(if (by == "name") compareBy { it.name.lowercase() } else compareByDescending { it.recent }))

    fun setOrder(by: String) { order.value = by; keep() }

    /** A new one, with [songs] in it (none is fine); named "my playlist #n" when no name's given. */
    @Synchronized fun make(name: String, songs: List<Song> = emptyList()): Mine {
        val now = System.currentTimeMillis()
        val m = Mine(java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { "my playlist #${lists.value.size + 1}" }, "",
            songs.distinctBy { it.id }.map { entry(it, now) }, now, now)
        lists.value = listOf(m) + lists.value
        keep()
        return m
    }

    /** [songs] at its end, those not in it already: how many went in. */
    fun add(id: String, songs: List<Song>): Int {
        var n = 0
        edit(id) { m ->
            val have = m.entries.mapTo(HashSet()) { it.song.id }
            val fresh = songs.distinctBy { it.id }.filter { it.id !in have }
            n = fresh.size
            val now = System.currentTimeMillis()
            m.copy(entries = m.entries + fresh.map { entry(it, now) })
        }
        return n
    }

    fun has(m: Mine, songs: List<Song>) = songs.isNotEmpty() && m.entries.mapTo(HashSet()) { it.song.id }.containsAll(songs.map { it.id })
    fun removeSongs(id: String, songs: List<Song>) { val out = songs.mapTo(HashSet()) { it.id }; edit(id) { m -> m.copy(entries = m.entries.filter { it.song.id !in out }) } }
    fun remove(id: String, k: String) = edit(id) { m -> m.copy(entries = m.entries.filter { it.k != k }) }
    /** Its songs in this order (their [Entry.k]s). */
    fun reorder(id: String, keys: List<String>) = edit(id) { m ->
        val by = m.entries.associateBy { it.k }
        m.copy(entries = keys.mapNotNull { by[it] } + m.entries.filter { it.k !in keys })
    }
    fun details(id: String, name: String, about: String) = edit(id) { it.copy(name = name.trim().ifEmpty { it.name }, about = about.trim()) }
    fun pin(id: String, on: Boolean) = edit(id, touched = false) { it.copy(pinned = on) }
    fun played(id: String) = edit(id, touched = false) { it.copy(played = System.currentTimeMillis()) }

    @Synchronized fun delete(c: Context, id: String) {
        lists.value = lists.value.filter { it.id != id }
        keep()
        picture(c, id).delete()
    }

    fun picture(c: Context, id: String) = File(File(c.filesDir, "list-covers").apply { mkdirs() }, "$id.jpg")

    /** A picture chosen for it (made square-ish small, 1080 at most), or taken away (null). */
    suspend fun setPicture(c: Context, id: String, uri: Uri?): Boolean = withContext(Dispatchers.IO) {
        val f = picture(c, id)
        if (uri == null) { f.delete(); edit(id) { it.copy(cover = false) }; pictures.value++; return@withContext true }
        val ok = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1080) sample *= 2
            val bmp = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
            val big = maxOf(bmp.width, bmp.height)
            val out = if (big > 1080) Bitmap.createScaledBitmap(bmp, bmp.width * 1080 / big, bmp.height * 1080 / big, true) else bmp
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.isSuccess
        if (ok) { edit(id) { it.copy(cover = true) }; pictures.value++ }
        ok
    }

    /**
     * The playlist as a file a friend opens with the app (Exportify's columns, so it opens as one brought in from
     * Spotify does), the YouTube id of each song that has one, so it's the same song there, not a search's.
     */
    fun sendFile(c: Context, m: Mine) {
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val text = buildString {
            append("\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\",\"YouTube ID\"\n")
            m.songs.forEach { s ->
                append(listOf(q(s.title), q(s.artist), q(s.album), s.duration.toString(), q(if (s.tube) s.id.removePrefix("y") else "")).joinToString(","))
                append('\n')
            }
        }
        val safe = m.name.replace(Regex("[^\\p{L}\\p{N} ._-]+"), " ").trim().ifEmpty { "playlist" }.take(60)
        val f = File(File(c.cacheDir, "shared").apply { mkdirs() }, "$safe.csv").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".shared", f)
        val app = c.applicationInfo.loadLabel(c.packageManager).toString()
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${m.name}: ${m.entries.size} songs. open the file with $app to have it too")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        c.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** "1 h 11 min", "38 min": a playlist's length as said. */
internal fun lengthSaid(ms: Long): String? {
    if (ms <= 0) return null
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** A playlist's picture: the one chosen for it, else four of its records' covers two by two, else its name set big. */
@Composable
fun MineCover(m: PhoneLists.Mine, modifier: Modifier) {
    val ctx = LocalContext.current
    val v by PhoneLists.pictures.collectAsState()
    val pic by produceState<ImageBitmap?>(null, m.id, m.cover, v) {
        value = if (!m.cover) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(PhoneLists.picture(ctx, m.id).path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.background(Ink.black)) {
        val p = pic
        if (p != null) Image(p, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Mosaic(m.songs, m.name)
    }
}

/** "new >" and a name: a playlist on the phone, made and opened (no name: "my playlist #n"). */
@Composable
fun MineNew(pages: Pages?, songs: List<Song> = emptyList(), focused: Boolean = false, onMade: (PhoneLists.Mine) -> Unit = {}) {
    var name by rememberSaveable { mutableStateOf("") }
    var typing by remember { mutableStateOf(focused) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(typing) { if (typing) { delay(60); runCatching { focus.requestFocus() } } }
    fun make() {
        val m = PhoneLists.make(name, songs)
        name = ""; typing = false; keyboard?.hide()
        onMade(m)
        pages?.open(Page.Mine(m.id))
    }
    if (!typing) Word("+ new playlist", pixel(14.sp), Modifier.padding(vertical = 6.dp), touch = 8.dp) { typing = true }
    else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        BasicText("new > ", style = pixel(14.sp, Ink.dim))
        BasicTextField(
            name, { name = it }, singleLine = true,
            textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { make() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner -> Box { if (name.isEmpty()) BasicText("its name", style = pixel(14.sp, Ink.dim)); inner() } },
        )
        Word("make", pixel(14.sp), touch = 8.dp) { make() }
    }
}

/** One of yours on the phone as a row: its picture small, its name, what it holds. */
@Composable
fun MineRow(m: PhoneLists.Mine, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MineCover(m, Modifier.size(58.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(m.name.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(listOfNotNull(if (m.pinned) "pinned" else null, "playlist", "${m.entries.size} ${if (m.entries.size == 1) "song" else "songs"}")
                .joinToString("   "), style = pixel(12.sp, Ink.mid), maxLines = 1)
        }
    }
}

/** Lists' own part for yours on the phone: a new one, the order (recent, a to z), and each. */
@Composable
fun MineRows(pages: Pages) {
    val all by PhoneLists.lists.collectAsState()
    val ord by PhoneLists.order.collectAsState()
    val shown = remember(all, ord) { PhoneLists.sorted(all, ord) }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("YOUR PLAYLISTS", style = headline(15.sp, Ink.mid), modifier = Modifier.weight(1f))
        if (all.size > 1) {
            Word("recent", pixel(12.sp, if (ord != "name") Ink.ink else Ink.dim), Modifier.padding(end = 18.dp), touch = 8.dp) { PhoneLists.setOrder("recent") }
            Word("a to z", pixel(12.sp, if (ord == "name") Ink.ink else Ink.dim), touch = 8.dp) { PhoneLists.setOrder("name") }
        }
    }
    MineNew(pages)
    shown.forEach { m -> MineRow(m) { pages.open(Page.Mine(m.id)) } }
    if (all.isEmpty()) BasicText("made here, kept on the phone: no sign-in, any song", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(bottom = 6.dp))
    Spacer(Modifier.height(10.dp))
}

/** The filled word a playlist's page leads with: play. */
@Composable
private fun PlayBlock(word: String, onClick: () -> Unit) {
    Box(Modifier.background(Ink.mid).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))
        .padding(horizontal = 22.dp, vertical = 12.dp)) {
        BasicText(word, style = headline(18.sp, Ink.black))
    }
}

/** A square box, filled when it's ticked: in it / not. */
@Composable
private fun Tick(on: Boolean) {
    Box(Modifier.size(22.dp).then(if (on) Modifier.background(Ink.mid) else Modifier.border(1.5.dp, Ink.dim)), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(8.dp).background(Ink.black))
    }
}

/** Three short lines: hold and drag to move it. */
@Composable
private fun Handle(modifier: Modifier) {
    Column(modifier.size(44.dp).padding(horizontal = 12.dp, vertical = 15.dp), verticalArrangement = Arrangement.SpaceBetween) {
        repeat(3) { Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.mid)) }
    }
}

/**
 * One of yours on the phone, as Spotify's is (the editions', 2026-11-21): its picture big, its name, what it's about,
 * how many and how long; play (filled) and shuffle; then add songs, edit (drag to reorder, take out), find in it, its
 * order, more (play next, the queue, its details and picture, pin, into another, send it, delete); its songs; and
 * songs like these to add, under them.
 */
@Composable
fun MinePage(id: String, pages: Pages) {
    val ctx = LocalContext.current
    val all by PhoneLists.lists.collectAsState()
    val m = all.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    if (m == null) {
        FrostedPage(onClose = { pages.close() }) { _, top -> BasicText("that playlist is gone", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 20.dp, top = top + 14.dp)) }
        return
    }
    var editing by remember(id) { mutableStateOf(false) }
    var finding by remember(id) { mutableStateOf(false) }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var sortBy by rememberSaveable(id) { mutableStateOf("yours") }
    var more by remember(id) { mutableStateOf(false) }
    var details by remember(id) { mutableStateOf(false) }
    var sure by remember(id) { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
    var said by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(2_000); said = null } }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { said = if (PhoneLists.setPicture(ctx, id, uri)) "its picture changed" else "that picture didn't open" }
    }
    // what's shown: found by what's typed, in the order chosen (dragging is only in its own order)
    val q = query.trim().lowercase()
    val shown = remember(m.entries, q, sortBy) {
        val found = if (q.isEmpty()) m.entries else m.entries.filter { it.song.title.lowercase().contains(q) || it.song.artist.lowercase().contains(q) || it.song.album.lowercase().contains(q) }
        when (sortBy) {
            "title" -> found.sortedBy { Library.sortName(it.song.title) }
            "artist" -> found.sortedBy { it.song.artist.lowercase() }
            "added" -> found.sortedByDescending { it.at }
            else -> found
        }
    }
    val songs = remember(shown) { shown.map { it.song } }
    // editing: a copy moved as the finger goes, kept once it's let go
    val order = remember(m.entries, editing) { m.entries.toMutableStateList() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    // songs like these (YouTube Music's, the full build), a few at a time, "others" for another few
    var round by remember(id) { mutableIntStateOf(0) }
    val seedKey = m.entries.take(3).joinToString { it.song.id } + round
    val ideas by produceState<List<Song>?>(null, id, seedKey) {
        if (!YouTubeMusic.available || m.entries.isEmpty()) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) {
            val seeds = m.songs.shuffled(kotlin.random.Random(round)).take(4)
            seeds.flatMap { s ->
                runCatching {
                    val tube = if (s.tube) s else YouTubeMusic.find(s.title, s.artist)
                    tube?.let { YouTubeMusic.related(it.id).take(12) }.orEmpty()
                }.getOrDefault(emptyList())
            }.distinctBy { it.id }.shuffled(kotlin.random.Random(round + 7)).take(14)
        }
    }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item(key = "head") { Column(Modifier.cascade(0)) {
            // its picture, the page's width but not more than a hand's; tapped, its details (the picture changed there)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth * .68f, 320.dp)
                Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { details = !details; more = false })) {
                    MineCover(m, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(20.dp))
            BasicText(m.name.uppercase(), style = headline(38.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (m.about.isNotBlank()) BasicText(m.about, style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 19.sp), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
            BasicText(listOfNotNull("your playlist", "${m.entries.size} ${if (m.entries.size == 1) "song" else "songs"}", lengthSaid(m.length))
                .joinToString("   "), style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(18.dp))
            if (m.entries.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                PlayBlock("PLAY") { Playback.play(songs, 0); PhoneLists.played(id) }
                Spacer(Modifier.width(24.dp))
                Word("shuffle", pixel(14.sp), touch = 8.dp) { Playback.play(songs.shuffled(), 0); PhoneLists.played(id) }
                Spacer(Modifier.weight(1f))
                Word("share", pixel(14.sp, Ink.mid), touch = 8.dp) { scope.launch { runCatching { ShareCard.sharePlaylist(ctx, m.name, m.songs) } } }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Word("add songs", pixel(14.sp), touch = 8.dp) { pages.open(Page.MineAdd(id)) }
                if (m.entries.size > 1 || editing) Word(if (editing) "done" else "edit", pixel(14.sp, if (editing) Ink.ink else Ink.mid), touch = 8.dp) {
                    editing = !editing; finding = false; query = ""; sortBy = "yours"; more = false
                }
                if (!editing && m.entries.size > 4) Word("find", pixel(14.sp, if (finding) Ink.ink else Ink.mid), touch = 8.dp) { finding = !finding; if (!finding) query = "" }
                if (!editing && m.entries.size > 1) Word(when (sortBy) { "title" -> "by title"; "artist" -> "by artist"; "added" -> "latest added"; else -> "your order" },
                    pixel(14.sp, Ink.mid), touch = 8.dp) {
                    sortBy = when (sortBy) { "yours" -> "title"; "title" -> "artist"; "artist" -> "added"; else -> "yours" }
                }
                Word(if (more) "less" else "more", pixel(14.sp, Ink.mid), touch = 8.dp) { more = !more; details = false }
            }
            KeepOffline(m.songs)
            if (more) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.entries.isNotEmpty()) {
                    Word("play next", pixel(13.sp), touch = 8.dp) { m.songs.reversed().forEach { Playback.playNext(it) }; said = "playing next" }
                    Word("add to queue", pixel(13.sp), touch = 8.dp) { m.songs.forEach { Playback.add(it) }; said = "in the queue" }
                    Word("into another playlist", pixel(13.sp), touch = 8.dp) { pages.open(Page.AddToMany(m.songs)) }
                    Word("send it", pixel(13.sp), touch = 8.dp) { runCatching { PhoneLists.sendFile(ctx, m) } }
                }
                Word("details", pixel(13.sp), touch = 8.dp) { details = true; more = false }
                Word(if (m.pinned) "unpin" else "pin to the top", pixel(13.sp), touch = 8.dp) { PhoneLists.pin(id, !m.pinned); said = if (m.pinned) "unpinned" else "pinned to the top" }
                Word(if (sure) "delete it" else "delete", pixel(13.sp, if (sure) Ink.ink else Ink.mid), touch = 8.dp) {
                    if (!sure) sure = true else { pages.back(); PhoneLists.delete(ctx, id) }
                }
            }
            if (details) Details(m, onPicture = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onNoPicture = { scope.launch { PhoneLists.setPicture(ctx, id, null) } }) { details = false }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp)) }
            if (finding) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("find > ", style = pixel(14.sp, Ink.dim))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                        modifier = Modifier.weight(1f).focusRequester(focus))
                }
            }
            if (editing) BasicText("hold the lines at the right and drag to move a song", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(14.dp))
        }}
        if (m.entries.isEmpty()) item(key = "empty") {
            Column(Modifier.padding(vertical = 18.dp)) {
                BasicText("NOTHING IN IT YET", style = headline(22.sp, Ink.mid))
                Spacer(Modifier.height(6.dp))
                BasicText("add songs here, or hold any song and pick add to playlist", style = pixel(12.sp, Ink.dim))
                Spacer(Modifier.height(10.dp))
                Word("add songs", pixel(14.sp), touch = 8.dp) { pages.open(Page.MineAdd(id)) }
            }
        }
        if (editing) itemsIndexed(order, key = { _, e -> e.k }) { _, e ->
            val me = dragging == e.k
            Row(
                Modifier.fillMaxWidth()
                    .then(if (me) Modifier.zIndex(1f).graphicsLayer { translationY = dy }.background(Ink.plate) else Modifier.animateItem())
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverOf(e.song, e.song.album.ifEmpty { e.song.title }, Modifier.size(44.dp), px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(e.song.title.uppercase(), style = headline(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(e.song.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Word("take out", pixel(12.sp, Ink.mid), Modifier.padding(start = 10.dp), touch = 8.dp) { order.remove(e); PhoneLists.remove(id, e.k) }
                Handle(Modifier.pointerInput(e.k) {
                    detectDragGestures(
                        onDragStart = { _ -> dragging = e.k; dy = 0f },
                        onDragEnd = { dragging = null; dy = 0f; PhoneLists.reorder(id, order.map { it.k }) },
                        onDragCancel = { dragging = null; dy = 0f; PhoneLists.reorder(id, order.map { it.k }) },
                    ) { change, amount ->
                        change.consume()
                        dy += amount.y
                        val seen = list.layoutInfo.visibleItemsInfo
                        val cur = seen.firstOrNull { it.key == e.k } ?: return@detectDragGestures
                        val mid = cur.offset + dy + cur.size / 2f
                        val over = seen.firstOrNull { o -> o.key != e.k && mid > o.offset && mid < o.offset + o.size && order.any { it.k == o.key } }
                            ?: return@detectDragGestures
                        val from = order.indexOfFirst { it.k == e.k }
                        val to = order.indexOfFirst { it.k == over.key }
                        if (from < 0 || to < 0) return@detectDragGestures
                        order.add(to, order.removeAt(from))
                        // where it's drawn stays under the finger: what it passed is now on its other side
                        dy += if (to > from) -over.size.toFloat() else over.size.toFloat()
                    }
                })
            }
        } else itemsIndexed(shown, key = { _, e -> e.k }) { i, e -> Box(Modifier.cascade(i + 1).animateItem()) {
            SongRow(e.song, pages = pages, cover = true, onRemove = { PhoneLists.remove(id, e.k) }) { Playback.play(songs, i); PhoneLists.played(id) }
        }}
        // songs like these, to add (not on a TV, not while editing)
        if (!editing && q.isEmpty()) ideas?.let { found -> Suggestions(found.filter { s -> m.entries.none { it.song.id == s.id } }.take(8), id, pages) { round++ } }
    } }
}

private fun LazyListScope.Suggestions(ideas: List<Song>, id: String, pages: Pages, other: () -> Unit) {
    if (ideas.isEmpty()) return
    item(key = "ideas") {
        Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText("SONGS LIKE THESE", style = headline(22.sp))
                BasicText("to add, from youtube music", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 3.dp))
            }
            Word("others", pixel(13.sp, Ink.mid), touch = 8.dp, onClick = other)
        }
    }
    itemsIndexed(ideas, key = { _, s -> "idea" + s.id }) { i, s ->
        Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SongRow(s, pages = pages, cover = true, yours = false) { Playback.play(ideas, i) } }
            Word("add", pixel(14.sp), Modifier.padding(start = 12.dp), touch = 10.dp) { PhoneLists.add(id, listOf(s)) }
        }
    }
}

/** Its name, what it's about, its picture: changed here. */
@Composable
private fun Details(m: PhoneLists.Mine, onPicture: () -> Unit, onNoPicture: () -> Unit, onDone: () -> Unit) {
    var name by remember(m.id) { mutableStateOf(m.name) }
    var about by remember(m.id) { mutableStateOf(m.about) }
    fun save() { PhoneLists.details(m.id, name, about); onDone() }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).border(1.dp, Ink.line).padding(16.dp)) {
        BasicText("name", style = pixel(12.sp, Ink.mid))
        BasicTextField(name, { name = it }, singleLine = true, textStyle = headline(22.sp), cursorBrush = SolidColor(Ink.ink),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
        BasicText("what it's about", style = pixel(12.sp, Ink.mid))
        BasicTextField(about, { about = it }, textStyle = pixel(13.sp), cursorBrush = SolidColor(Ink.ink),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
            decorationBox = { inner -> Box { if (about.isEmpty()) BasicText("a line, if you like", style = pixel(13.sp, Ink.dim)); inner() } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Word("save", pixel(14.sp), touch = 8.dp) { save() }
            Word("choose a picture", pixel(14.sp, Ink.mid), touch = 8.dp, onClick = onPicture)
            if (m.cover) Word("its covers again", pixel(14.sp, Ink.mid), touch = 8.dp, onClick = onNoPicture)
            Word("cancel", pixel(14.sp, Ink.dim), touch = 8.dp, onClick = onDone)
        }
    }
}

/**
 * Songs into one of yours (its "add songs"): find any (yours, YouTube Music's), else the ones you love and songs like
 * its own; each "add" once, "added" lit after.
 */
@Composable
fun MineAddPage(id: String, pages: Pages) {
    val all by PhoneLists.lists.collectAsState()
    val m = all.firstOrNull { it.id == id } ?: run { FrostedPage(onClose = { pages.close() }) { _, _ -> }; return }
    val library by Library.songs.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val have = remember(m.entries) { m.entries.mapTo(HashSet()) { it.song.id } }
    val mine = remember(q, library) {
        if (q.isEmpty()) emptyList() else library.asSequence().filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
            .sortedBy { if (it.title.startsWith(q, true)) 0 else 1 }.take(30).toList()
    }
    val tube by produceState<List<Song>>(emptyList(), q) {
        if (q.length < 2 || !YouTubeMusic.available) { value = emptyList(); return@produceState }
        delay(350)
        value = runCatching { YouTubeMusic.songs(q) }.getOrDefault(emptyList())
    }
    val loved = remember(library, lovedIds, lovedTube, liked) { (liked + lovedTube + library.filter { it.id in lovedIds }).distinctBy { it.id }.take(60) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item(key = "head") {
            BasicText("ADD TO ${m.name.uppercase()}", style = headline(30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            BasicText("${m.entries.size} ${if (m.entries.size == 1) "song" else "songs"} in it", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("find > ", style = pixel(14.sp, Ink.dim))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) BasicText("a song, an artist", style = pixel(14.sp, Ink.dim)); inner() } })
                Word("done", pixel(14.sp), touch = 8.dp) { pages.back() }
            }
            Spacer(Modifier.height(12.dp))
        }
        fun section(key: String, label: String, songs: List<Song>) {
            if (songs.isEmpty()) return
            item(key = "l$key") { BasicText(label, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            itemsIndexed(songs, key = { _, s -> key + s.id }) { _, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SongRow(s, pages = null, cover = true, swipe = false) { Playback.play(listOf(s), 0) } }
                    val inIt = s.id in have
                    Word(if (inIt) "added" else "add", pixel(14.sp, if (inIt) Ink.dim else Ink.ink), Modifier.padding(start = 12.dp), touch = 10.dp) {
                        if (inIt) PhoneLists.removeSongs(id, listOf(s)) else PhoneLists.add(id, listOf(s))
                    }
                }
            }
        }
        if (q.isEmpty()) section("loved", "songs you love", loved)
        else {
            section("mine", "yours", mine)
            section("tube", "youtube music", tube.filter { t -> mine.none { it.id == t.id } })
            if (mine.isEmpty() && tube.isEmpty()) item(key = "none") { BasicText(if (q.length < 2) "" else "looking", style = pixel(12.sp, Ink.dim)) }
        }
    } }
}

/**
 * The top of "add to a playlist" (Spotify's "saved in"): loved, and each of yours on the phone with a box, filled where
 * it's in already; a tap puts it in or takes it out, any number of them; a new one made here has it at once. [onDone]
 * with the last one it went into (null: none), as the rest of the page's are.
 */
@Composable
fun SavedIn(songs: List<Song>, onDone: (String?) -> Unit) {
    val all by PhoneLists.lists.collectAsState()
    val ord by PhoneLists.order.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    var find by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<String?>(null) }
    val shown = remember(all, ord, find) {
        PhoneLists.sorted(all, ord).filter { find.isBlank() || it.name.contains(find.trim(), true) }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("SAVED IN", style = headline(15.sp, Ink.mid), modifier = Modifier.weight(1f))
            Word("done", headline(20.sp), touch = 10.dp) { onDone(last) }
        }
        Spacer(Modifier.height(8.dp))
        // loved (one song): the heart's box
        val one = songs.singleOrNull()
        if (one != null && !one.station) Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
            onClick = felt { Loved.toggle(one) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(Ink.black).border(1.dp, Ink.line), contentAlignment = Alignment.Center) { Sprite(HEART, HeartRed, 3.dp) }
            Spacer(Modifier.width(14.dp))
            BasicText("LOVED", style = headline(19.sp), modifier = Modifier.weight(1f))
            Tick(one.id in lovedIds)
        }
        MineNew(null, songs) { last = it.name }
        if (all.size > 6) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("find > ", style = pixel(14.sp, Ink.dim))
            BasicTextField(find, { find = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink), modifier = Modifier.weight(1f))
        }
        shown.forEach { m ->
            val inIt = PhoneLists.has(m, songs)
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt {
                if (inIt) PhoneLists.removeSongs(m.id, songs) else { PhoneLists.add(m.id, songs); last = m.name }
            }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MineCover(m, Modifier.size(46.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(m.name.uppercase(), style = headline(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("${m.entries.size} ${if (m.entries.size == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
                }
                Tick(inIt)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
