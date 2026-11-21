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
import androidx.compose.ui.text.font.Font
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

// The look: black, warm white ink, brightness for selection, album art the only colour.
// VIGIL theme (an unofficial build): a rain-soaked city after midnight. Cold black, bone-white headlines with a
// steel sheen and a hard shadow, typewritten small print, sodium-amber streetlight for what's secondary; rain over it all.
object Ink {
    // (the editions', 2026-11-21: darker, harder) near-total black, bone white, and a red light for all that's secondary
    val black = Color(0xFF050506)                 // after midnight (was Color.Black)
    /** [black] as an ARGB int, for pictures drawn pixel by pixel (the dither's off dots). */
    const val blackArgb: Int = 0xFF050506.toInt()
    val ink = Color(0xFFE9E4D8)                   // bone white (was 0xFFF1F0EC, warm white)
    /** The red light: what's secondary burns red, not the ink dimmed. */
    val red = Color(0xFFE04538)
    /** The streetlight's sodium amber, and wet steel for the hairlines. */
    val amber = Color(0xFFE3A23B)
    val steel = Color(0xFF8E9BAC)
    /** Bright light (the light sensor: sunlight): the dim words a step brighter, so they read outdoors. */
    var sun by androidx.compose.runtime.mutableStateOf(false)
    val mid get() = red.copy(alpha = if (sun) 1f else .95f)     // hover / secondary: the red light
    val dim get() = ink.copy(alpha = if (sun) .6f else .4f)     // unselected
    val line = steel.copy(alpha = .22f)  // hairlines, steel
    val plate = Color(0xFF110C0E)                 // blood-dark asphalt (was 0xFF141414)
}

/** The sheen on every headline in the ink: bone white going to wet steel, left to right. */
val Sheen: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
    listOf(Color(0xFFF4F0E8), Color(0xFFD3D6DB), Color(0xFF8E9BAC)),
)
/** A streetlight's line: amber in the middle, gone into the dark at either end. */
val Streetlight: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
    listOf(Color.Transparent, Color(0xE6D7372E), Color(0xE6D7372E), Color.Transparent),
)

// Two fonts, never a third: Bebas Neue UPPERCASE (in archivo_black.ttf's place), Courier Prime lowercase (in
// silkscreen.ttf's). Both SIL Open Font License; the names kept so every use of them (the widget, the share cards) follows.
val Archivo = FontFamily(Font(R.font.archivo_black))
val Pixel = FontFamily(Font(R.font.silkscreen))

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * A headline. In the ink it has the steel [Sheen]; in any other colour it's that colour. Either way a hard shadow
 * under it, as if lit from above by a streetlamp (none when the phone moves less: a shadow is a blur, each draw).
 */
fun headline(size: TextUnit, color: Color = Ink.ink): TextStyle {
    val lit = color == Ink.ink
    val drop = if (Moves.less.value) null else androidx.compose.ui.graphics.Shadow(
        color = Color(0xFF3B0605).copy(alpha = .92f),   // a red-black shadow under the words
        offset = Offset(0f, (size.value * .08f).coerceIn(1.5f, 8f)),
        blurRadius = (size.value * .12f).coerceIn(2f, 12f),
    )
    return if (lit) TextStyle(
        brush = Sheen, fontFamily = Archivo, fontSize = size, shadow = drop,
        letterSpacing = 0.03.em, lineHeight = 1.0.em, lineHeightStyle = tight,
    ) else TextStyle(
        color = color, fontFamily = Archivo, fontSize = size, shadow = drop,
        letterSpacing = 0.03.em, lineHeight = 1.0.em, lineHeightStyle = tight,
    )
}

fun pixel(size: TextUnit, color: Color = Ink.ink) = TextStyle(
    fontFamily = Pixel, fontSize = readable(size), color = color, lineHeight = 1.4.em, letterSpacing = 0.em,
)

/** VIGIL's names for ink music's places: only the words shown change, never the places' own keys. */
fun placeName(key: String): String = when (key) {
    "listen" -> "the beat"
    "ink" -> "the city"
    "albums" -> "case files"
    "artists" -> "faces"
    "songs" -> "tracks"
    "radio" -> "night radio"
    "podcasts" -> "talk"
    "lists" -> "dossiers"
    else -> key
}

/**
 * VIGIL over everything (the editions', 2026-11-21: darker, harder): a red light low on the street, bleeding up from the foot and in
 * at a corner; the dark closing in at the edges; heavy rain past the glass; fine grain over it all. It only draws
 * (nothing under it loses a touch), and never moves, in a layer of its own: drawn again only when the screen's size changes.
 */
@Composable
fun VigilOverlay(modifier: Modifier) {
    Canvas(modifier.graphicsLayer { }) {
        val h = size.height
        val w = size.width
        val red = Color(0xFFE04538)
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, .74f to Color.Transparent, 1f to red.copy(alpha = .17f)))
        drawRect(androidx.compose.ui.graphics.Brush.radialGradient(0f to red.copy(alpha = .09f), 1f to Color.Transparent,
            center = Offset(w, 0f), radius = maxOf(w, h) * .45f))
        // the dark at the edges
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .64f),
                center = Offset(w / 2f, h * .45f), radius = maxOf(w, h) * .7f,
            ),
        )
        // the rain: the same drops every time (a fixed seed), slanting in the wind
        val rnd = kotlin.random.Random(1939)
        val drops = (w * h / 5200f).toInt().coerceIn(160, 1200)
        repeat(drops) {
            val x = rnd.nextFloat() * (w + h * .25f) - h * .12f
            val y = rnd.nextFloat() * h
            val len = (12f + rnd.nextFloat() * 30f) * density
            val a = .045f + rnd.nextFloat() * .09f
            drawLine(
                Color(0xFFB8C6D8).copy(alpha = a), Offset(x, y), Offset(x - len * .2f, y + len),
                strokeWidth = (if (rnd.nextFloat() < .2f) 1.6f else 1f) * density * .7f,
            )
        }
        // the grain: fine dust, the same every time
        val g = kotlin.random.Random(1947)
        val dot = androidx.compose.ui.geometry.Size(density, density)
        repeat((w * h / 700f).toInt().coerceIn(800, 9000)) {
            drawRect(Color.White.copy(alpha = .02f + g.nextFloat() * .035f), Offset(g.nextFloat() * w, g.nextFloat() * h), dot)
        }
    }
}

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
    const val ENTER = 320   // (the editions', 2026-11-21: 260, 140; smoother, still quick)
    const val EXIT = 220
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
    val lit = style.brush   // a headline in the ink: its gradient, faded in as itself
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
 * How big a header's picture may be (a record's cover, an artist's photo, ink weekly's covers), given the width it has:
 * never past 420 dp nor 55% of the screen's height (2026-11-10: ink weekly's covers filled a TV's whole width, taller
 * than the screen). An upright phone's width is the smaller, so it's as it was there.
 */
@androidx.compose.runtime.Composable
fun headerSide(width: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val h = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    return minOf(width, 420.dp, h * .55f)
}
