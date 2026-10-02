package app.halo.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.halo.ui.theme.LocalHalo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Haptics that respect the user's setting. */
class Haptics(private val enabled: Boolean, private val feedback: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun tick() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun press() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val fb = LocalHapticFeedback.current
    return remember(enabled, fb) { Haptics(enabled, fb) }
}

/** The hero: a softly breathing orb in the bulb's colour. */
@Composable
fun GlowOrb(
    color: Color,
    on: Boolean,
    level: Float,
    glow: Float,
    animate: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f), label = "press")
    val power by animateFloatAsState(if (on) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "power")
    val shownLevel by animateFloatAsState(level.coerceIn(0f, 1f), tween(400), label = "level")
    val breathe = if (animate) {
        val t = rememberInfiniteTransition(label = "breathe")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "b").value
    } else 0.5f
    val halo = LocalHalo.current
    val intensity = power * (0.35f + 0.65f * shownLevel) * glow

    Canvas(
        modifier
            .aspectRatio(1f)
            .scale(pressScale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag("orb"),
    ) {
        val c = center
        val r = size.minDimension * 0.30f
        // outer glow
        val glowR = r * (2.15f + 0.08f * breathe)
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.55f * intensity), color.copy(alpha = 0.16f * intensity), Color.Transparent),
                center = c, radius = glowR,
            ),
            radius = glowR, center = c,
        )
        // body
        val off = halo.surfaceHigh
        val body = lerp(off, color, power * (0.45f + 0.55f * shownLevel))
        val core = lerp(off, lerp(color, Color.White, 0.65f), power * (0.5f + 0.5f * shownLevel))
        drawCircle(
            Brush.radialGradient(listOf(core, body, lerp(body, Color.Black, 0.35f)), center = c - Offset(r * 0.25f, r * 0.3f), radius = r * 1.35f),
            radius = r, center = c,
        )
        // rim
        drawCircle(
            Brush.sweepGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.22f)), c),
            radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()),
        )
        // specular
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * (0.4f + 0.6f * power)), Color.Transparent),
                center = c - Offset(r * 0.38f, r * 0.42f), radius = r * 0.45f),
            radius = r * 0.45f, center = c - Offset(r * 0.38f, r * 0.42f),
        )
    }
}

/** A chunky, glassy slider. [brush] draws a gradient track (warmth, hue); otherwise the fill uses the accent. */
@Composable
fun PillSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    valueText: String,
    icon: ImageVector? = null,
    brush: Brush? = null,
    fill: Color = LocalHalo.current.accent,
    height: Dp = 58.dp,
    haptics: Haptics? = null,
    tag: String = label,
    onChangeFinished: () -> Unit = {},
) {
    val halo = LocalHalo.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onChangeFinished)
    var dragValue by remember { mutableFloatStateOf(value) }
    val shown by animateFloatAsState(value.coerceIn(0f, 1f), spring(stiffness = 900f), label = "slider")

    fun emit(v: Float) {
        val nv = v.coerceIn(0f, 1f)
        if (floor(nv * 20) != floor(current * 20)) haptics?.tick()
        change(nv)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(halo.surfaceHigh)
            .testTag("slider-$tag")
            .pointerInput(Unit) {
                detectTapGestures { pos -> emit(pos.x / size.width); finished() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragValue = current },
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                ) { ch, dx ->
                    ch.consume()
                    dragValue = (dragValue + dx / size.width).coerceIn(0f, 1f)
                    emit(dragValue)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val x = w * shown
            if (brush != null) {
                drawRect(brush, alpha = 0.9f)
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.35f))))
            } else {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(fill.copy(alpha = 0.12f), fill.copy(alpha = 0.55f)), endX = x.coerceAtLeast(1f)),
                    size = Size(x, size.height),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
            val thumbW = 4.dp.toPx()
            val tx = x.coerceIn(10.dp.toPx(), w - 10.dp.toPx())
            drawRoundRect(
                Color.White.copy(alpha = 0.95f),
                topLeft = Offset(tx - thumbW / 2, size.height * 0.22f),
                size = Size(thumbW, size.height * 0.56f),
                cornerRadius = CornerRadius(thumbW),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.92f))
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight.Medium)
        }
    }
}

/** Hue around the edge, saturation towards the middle. */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    onChange: (hue: Float, sat: Float) -> Unit,
    modifier: Modifier = Modifier,
    haptics: Haptics? = null,
    onChangeFinished: () -> Unit = {},
) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val hues = remember { (0..12).map { Color.hsv((it * 30f) % 360f, 1f, 1f) } }
    var lastBucket by remember { mutableFloatStateOf(-1f) }

    fun handle(pos: Offset, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2
        val dx = pos.x - cx; val dy = pos.y - cy
        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0) angle += 360f
        val sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        val bucket = floor(angle / 15f)
        if (bucket != lastBucket) { haptics?.tick(); lastBucket = bucket }
        change(angle, sat)
    }

    Canvas(
        modifier
            .aspectRatio(1f)
            .testTag("wheel")
            .pointerInput(Unit) {
                detectTapGestures { handle(it, size.width.toFloat(), size.height.toFloat()); finished() }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { finished() }, onDragCancel = { finished() }) { ch, _ ->
                    ch.consume()
                    handle(ch.position, size.width.toFloat(), size.height.toFloat())
                }
            },
    ) {
        val r = size.minDimension / 2
        drawCircle(Brush.sweepGradient(hues, center), radius = r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), radius = r)
        drawCircle(Color.Black.copy(alpha = 0.18f), radius = r, style = Stroke(2.dp.toPx()))
        val a = Math.toRadians(hue.toDouble())
        val p = Offset(center.x + (cos(a) * r * saturation).toFloat(), center.y + (sin(a) * r * saturation).toFloat())
        val tr = 15.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = tr + 3.dp.toPx(), center = p + Offset(0f, 2.dp.toPx()))
        drawCircle(Color.hsv(((hue % 360) + 360) % 360, saturation, 1f), radius = tr, center = p)
        drawCircle(Color.White, radius = tr, center = p, style = Stroke(3.dp.toPx()))
    }
}

/** Pill tabs with a sliding highlight. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, haptics: Haptics? = null) {
    val halo = LocalHalo.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(halo.surface)
            .border(1.dp, halo.outline, RoundedCornerShape(23.dp))
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(19.dp))
                .background(halo.surfaceHigh)
                .border(1.dp, halo.accent.copy(alpha = 0.35f), RoundedCornerShape(19.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(19.dp))
                        .clickable { haptics?.tick(); onSelect(i) }
                        .testTag("tab-$label"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == selected) halo.text else halo.textDim,
                    )
                }
            }
        }
    }
}

@Composable
fun HaloCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val halo = LocalHalo.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(halo.surface)
            .border(1.dp, halo.outline.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = halo.textDim)
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalHalo.current.textDim, modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
}

/** A small round swatch. */
@Composable
fun Swatch(color: Color, selected: Boolean, size: Dp = 40.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val halo = LocalHalo.current
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, lerp(halo.outline, Color.White, ring), CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}
