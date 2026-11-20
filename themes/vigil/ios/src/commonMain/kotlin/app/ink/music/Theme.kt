package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// The phone's Theme.kt, as it is (2026-11-07: the iPhone the same as the phone, word for word).
// The look: black, warm white ink, brightness for selection, album art the only colour.
object Ink {
    val black = Color(0xFF08090C)                 // VIGIL: cold black (was Color.Black)
    /** [black] as an ARGB int, for pictures drawn dot by dot (the dither's off dots). */
    const val blackArgb: Int = 0xFF08090C.toInt()
    val ink = Color(0xFFE9E4D8)                   // bone white (was 0xFFF1F0EC, warm white)
    /** The streetlight: what's secondary is sodium amber, not the ink dimmed. */
    val amber = Color(0xFFE3A23B)
    /** Wet steel, for the hairlines. */
    val steel = Color(0xFF8E9BAC)
    /** Bright light (the light sensor: sunlight): the dim words a step brighter, so they read outdoors. */
    var sun by androidx.compose.runtime.mutableStateOf(false)
    val mid get() = amber.copy(alpha = if (sun) 1f else .9f)    // hover / secondary: amber
    val dim get() = ink.copy(alpha = if (sun) .62f else .42f)   // unselected
    val line = steel.copy(alpha = .26f)  // hairlines, steel
    val plate = Color(0xFF151820)                 // wet asphalt (was 0xFF141414)
}

/** The sheen on every headline in the ink: bone white going to wet steel, left to right. */
val Sheen: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
    listOf(Color(0xFFF4F0E8), Color(0xFFD3D6DB), Color(0xFF8E9BAC)),
)

// Two fonts, never a third: Archivo Black UPPERCASE, Silkscreen lowercase.
// the app's own files, in its bundle (the phone's R.font)
val Archivo by lazy { FontFamily(androidx.compose.ui.text.platform.Font("archivo_black", fontBytes("archivo_black"))) }
val Pixel by lazy { FontFamily(androidx.compose.ui.text.platform.Font("silkscreen", fontBytes("silkscreen"))) }

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** A headline. In the ink it has the steel [Sheen]; in any other colour, that colour. A hard shadow under it either way. */
fun headline(size: TextUnit, color: Color = Ink.ink): TextStyle {
    val lit = color == Ink.ink
    val s = size
    val drop = androidx.compose.ui.graphics.Shadow(
        color = Color.Black.copy(alpha = .85f),
        offset = Offset(0f, (s.value * .08f).coerceIn(1.5f, 8f)),
        blurRadius = (s.value * .12f).coerceIn(2f, 12f),
    )
    return if (lit) TextStyle(
        brush = Sheen, fontFamily = Archivo, fontSize = s, shadow = drop,
        letterSpacing = 0.03.em, lineHeight = 1.0.em, lineHeightStyle = tight,
    ) else TextStyle(
        color = color, fontFamily = Archivo, fontSize = s, shadow = drop,
        letterSpacing = 0.03.em, lineHeight = 1.0.em, lineHeightStyle = tight,
    )
}

fun pixel(size: TextUnit, color: Color = Ink.ink) = TextStyle(
    fontFamily = Pixel, fontSize = readable(size), color = color, lineHeight = 1.4.em, letterSpacing = 0.em,
)

/**
 * Silkscreen's pixels blur together below 12 sp (2026-10-25: the small words were hard to
 * read): 12 at the least, the sizes just over it a step up, 14 and above as asked.
 */
private fun readable(size: TextUnit): TextUnit = when {
    size.value < 12f -> 12.sp
    size.value < 14f -> (size.value + 1f).sp
    else -> size
}

// MOTION.md: one curve (ease-out-expo), no overshoot, nothing over ~300 ms.
object Motion {
    val expo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val FADE = 150
    const val ENTER = 260
    const val EXIT = 140
    const val STAGGER = 50
    val drift = 20.dp
}

// Arrive: rise 20 px and fade in, `order` widgets apart, every time `key` changes, and every
// time `again` ticks (read as it runs, not while composing: a list kept composed arrives again
// each time it's shown without recomposing a word).
/**
 * A press that's felt as well as seen: every word ink lets you tap gives the lightest click
 * (Android's context click; [kind] for a toggle, a hold, a key). Follows the phone's own touch
 * vibration setting, as every haptic does.
 */
@Composable
fun felt(
    kind: androidx.compose.ui.hapticfeedback.HapticFeedbackType = androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick,
    then: () -> Unit,
): () -> Unit {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return { haptic.performHapticFeedback(kind); then() }
}

fun Modifier.arrive(order: Int, key: Any?, again: androidx.compose.runtime.State<Int>? = null): Modifier = composed {
    val shift = with(LocalDensity.current) { Motion.drift.toPx() }
    val t = remember { Animatable(0f) }
    LaunchedEffect(key, again) {
        suspend fun go() {
            t.snapTo(0f)
            delay(order * Motion.STAGGER.toLong())
            t.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo))
        }
        if (again == null) go()
        else androidx.compose.runtime.snapshotFlow { again.value }.collectLatest { go() }
    }
    graphicsLayer {
        alpha = t.value
        translationY = (1f - t.value) * shift
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha   // no offscreen buffer
    }
}

/**
 * Text that arrives fades in word by word (MOTION.md 9): each word 150 ms, 50 ms apart, a whole
 * line within 0.7 s. Only words the text gains fade: what was already up stays (the date stays
 * when the weather joins it). With `from`, words light from that colour instead of from nothing
 * (the sung lyric line, dim to ink). One text, so wrapping and ellipsis behave as BasicText's.
 */
@Composable
fun FadeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    from: Color? = null,
) {
    val words = remember(text) { Regex("""\S+""").findAll(text).map { it.range }.toList() }
    val last = remember { arrayOf<String?>(null) }            // the text fully up before this one
    val kept = remember(text) { sharedWords(last[0], text) }  // its words that stay put
    val fading = (words.size - kept).coerceAtLeast(0)
    val gap = if (fading > 1) minOf(Motion.STAGGER.toFloat(), (700f - WORD) / (fading - 1)) else 0f
    val total = WORD + gap * (fading - 1).coerceAtLeast(0)
    val t = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        t.animateTo(total, tween(total.toInt(), easing = LinearEasing))
        last[0] = text
    }
    // Laid out once; while it fades only the drawing changes: each word drawn in its own colour
    // through a clip around it (rebuilding styled text every frame cost the onn a frame a frame)
    val laid = remember { arrayOfNulls<TextLayoutResult>(1) }
    val lit = style.brush   // a lit headline: its brush, faded in as itself
    val base = if (style.color != Color.Unspecified) style.color else Ink.ink
    val reach = with(LocalDensity.current) { (if (style.fontSize.isSp) style.fontSize.toPx() else 16f) * .25f }
    BasicText(
        text,
        modifier.drawWithContent {
            val now = t.value
            if (now >= total) { drawContent(); return@drawWithContent }   // all up: drawn as plain text
            val l = laid[0] ?: return@drawWithContent
            words.forEachIndexed { i, r ->
                val k = i - kept
                val a = if (k < 0) 1f else Motion.expo.transform(((now - k * gap) / WORD).coerceIn(0f, 1f))
                if (a <= 0f && from == null) return@forEachIndexed
                val c = if (from != null) lerp(from, base, a) else base.copy(alpha = base.alpha * a)
                val top = l.getLineForOffset(r.first)
                val bottom = l.getLineForOffset(r.last)
                for (line in top..bottom) {
                    val s0 = if (line == top) r.first else l.getLineStart(line)
                    val e0 = if (line == bottom) r.last + 1 else l.getLineEnd(line, visibleEnd = true)
                    if (e0 <= s0) continue
                    val x0 = l.getHorizontalPosition(s0, true)
                    val x1 = l.getHorizontalPosition(e0, true)
                    clipRect(minOf(x0, x1) - reach, l.getLineTop(line), maxOf(x0, x1) + reach, l.getLineBottom(line)) {
                        if (lit != null) {
                            if (from != null) drawText(l, color = from)
                            drawText(l, brush = lit, alpha = a)
                        } else drawText(l, color = c)
                    }
                }
            }
        },
        style,
        onTextLayout = { laid[0] = it },
        maxLines = maxLines, overflow = overflow, softWrap = softWrap,
    )
}

private const val WORD = 150f   // ms each word takes to come up

/** How many leading words `new` shares with `old` (those don't fade again). */
private fun sharedWords(old: String?, new: String): Int {
    if (old == null) return 0
    val a = old.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val b = new.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    var n = 0
    while (n < a.size && n < b.size && a[n] == b[n]) n++
    return n
}


/**
 * A word that changes place slides there (260 ms, the one curve) instead of jumping: where it was
 * laid out last in its parent is kept, and the difference eased away as it's drawn (the TV's
 * lists as a word is carried, home's notification words as a new one comes in ahead of them).
 */
fun Modifier.slideToPlace(still: () -> Boolean = { false }): Modifier = composed {
    val scope = rememberCoroutineScope()
    val last = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val from = remember { floatArrayOf(0f, 0f) }
    val left = remember { mutableFloatStateOf(0f) }   // 1 at the start of a slide, 0 at rest
    val held = androidx.compose.runtime.rememberUpdatedState(still)
    this.onPlaced { c ->
        val at = c.positionInParent()
        val wasX = last[0]; val wasY = last[1]
        last[0] = at.x; last[1] = at.y
        // held still (a word being carried by a finger, which places it itself): no slide
        if (held.value()) { left.floatValue = 0f; return@onPlaced }
        if (!wasX.isNaN() && (wasX != at.x || wasY != at.y)) {
            from[0] = wasX - at.x + from[0] * left.floatValue
            from[1] = wasY - at.y + from[1] * left.floatValue
            left.floatValue = 1f
            scope.launch {
                androidx.compose.animation.core.animate(1f, 0f, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) { v, _ -> left.floatValue = v }
            }
        }
    }.graphicsLayer { translationX = from[0] * left.floatValue; translationY = from[1] * left.floatValue }
}

/** A word that arrives on its own (a new notification): it fades up once, 150 ms. */
fun Modifier.fadeUp(): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(WORD.toInt(), easing = Motion.expo)) }
    graphicsLayer { alpha = a.value }
}

/**
 * A pixel sprite on Silkscreen's grid ('#' or 'X' is a pixel), so a glyph reads as more of the
 * type, not an icon: the cog, the padlock (the desktop bar's, waybar/lock/make-lock.py).
 */
@Composable
fun Sprite(rows: List<String>, color: Color, cell: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(cell * rows[0].length, cell * rows.size)) {
        val px = cell.toPx()
        // each pixel snapped to whole device pixels, edge to edge, so no seams show between them
        fun at(i: Int) = kotlin.math.round(i * px)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                if (ch == '#' || ch == 'X') {
                    drawRect(color, Offset(at(x), at(y)), Size(at(x + 1) - at(x), at(y + 1) - at(y)))
                }
            }
        }
    }
}

// the padlock, shut, with a blank row on top so it lines up with the raised one
val LOCK = listOf(
    ".......",
    "..###..",
    ".#...#.",
    ".#...#.",
    "#######",
    "#######",
    "###.###",
    "###.###",
    "#######",
)

// the padlock, open: the shackle raised, its left leg out
val UNLOCK = listOf(
    "..###..",
    ".#...#.",
    ".#...#.",
    ".....#.",
    "#######",
    "#######",
    "###.###",
    "###.###",
    "#######",
)

/**
 * VIGIL over everything: rain falling past the glass, and the dark closing in at the edges. It only draws (nothing
 * under it loses a touch), in a layer of its own, so it's drawn again only when the screen's size changes.
 */
@Composable
fun VigilOverlay(modifier: Modifier) {
    Canvas(modifier.graphicsLayer { }) {
        val h = size.height
        val w = size.width
        // the dark at the edges
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                0f to Color.Transparent, .62f to Color.Transparent, 1f to Color.Black.copy(alpha = .5f),
                center = Offset(w / 2f, h * .45f), radius = maxOf(w, h) * .72f,
            ),
        )
        // the rain: the same drops every time (a fixed seed), slanting a little in the wind
        val rnd = kotlin.random.Random(1939)
        val drops = (w * h / 7000f).toInt().coerceIn(120, 900)
        repeat(drops) {
            val x = rnd.nextFloat() * (w + h * .2f) - h * .1f
            val y = rnd.nextFloat() * h
            val len = (10f + rnd.nextFloat() * 26f) * density
            val a = .04f + rnd.nextFloat() * .08f
            drawLine(
                Color(0xFFB8C6D8).copy(alpha = a), Offset(x, y), Offset(x - len * .16f, y + len),
                strokeWidth = (if (rnd.nextFloat() < .2f) 1.6f else 1f) * density * .7f,
            )
        }
    }
}
