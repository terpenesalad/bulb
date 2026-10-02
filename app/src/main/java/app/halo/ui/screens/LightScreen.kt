package app.halo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.data.AppData
import app.halo.data.BuiltInPresets
import app.halo.data.Preset
import app.halo.data.PresetKind
import app.halo.data.SceneColour
import app.halo.data.Store
import app.halo.effects.EffectConfig
import app.halo.effects.EffectType
import app.halo.effects.Effects
import app.halo.light.Conn
import app.halo.light.LightController
import app.halo.tuya.BulbMode
import app.halo.tuya.BulbSchema
import app.halo.tuya.BulbState
import app.halo.ui.components.ColorWheel
import app.halo.ui.components.GlowOrb
import app.halo.ui.components.HaloCard
import app.halo.ui.components.Haptics
import app.halo.ui.components.PillSlider
import app.halo.ui.components.SectionLabel
import app.halo.ui.components.SegmentedTabs
import app.halo.ui.components.Swatch
import app.halo.ui.components.rememberHaptics
import app.halo.ui.theme.LocalHalo
import app.halo.ui.theme.displayColor
import app.halo.ui.theme.kelvin
import app.halo.ui.theme.kelvinColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun LightScreen(
    data: AppData,
    controller: LightController,
    onAddLight: () -> Unit,
    onEditLight: () -> Unit,
) {
    val halo = LocalHalo.current
    val settings = data.settings
    val state by controller.state.collectAsStateWithLifecycle()
    val conn by controller.conn.collectAsStateWithLifecycle()
    val running by Effects.running.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(settings.haptics)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(if (state.mode == BulbMode.COLOUR) 1 else 0) }

    // Gentle polling while visible so changes from the wall switch or other apps show up.
    LaunchedEffect(controller) {
        controller.refresh()
        while (true) {
            delay(30_000)
            controller.refresh()
        }
    }

    fun stopEffect() { if (running != null) Effects.stop(context) }

    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        TopRow(data, controller, conn, onAddLight, onEditLight, onRefresh = { scope.launch { controller.refresh() } })

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GlowOrb(
                color = state.displayColor(),
                on = state.on,
                level = if (state.mode == BulbMode.COLOUR) state.value else state.brightness,
                glow = settings.glow,
                animate = settings.animations && state.on,
                modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            ) {
                haptics.press()
                stopEffect()
                controller.togglePower()
            }
            Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = headline(state),
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                    label = "headline",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.displayMedium, color = halo.text, modifier = Modifier.testTag("headline"))
                }
                Text(subline(state, running), style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            }
        }

        AnimatedVisibility(conn is Conn.Offline) {
            val off = conn as? Conn.Offline
            OfflineCard(off?.message ?: "", off?.badKey == true, onRetry = { scope.launch { controller.refresh() } }, onFix = onEditLight)
        }

        Spacer(Modifier.height(18.dp))
        PowerRow(state, haptics) { on -> stopEffect(); controller.setPower(on) }
        Spacer(Modifier.height(18.dp))

        SegmentedTabs(listOf("White", "Colour", "Scenes", "Effects"), tab, { tab = it }, haptics = haptics)
        Spacer(Modifier.height(16.dp))

        AnimatedContent(targetState = tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "tab") { t ->
            when (t) {
                0 -> WhiteTab(state, haptics, onChange = { b, w -> stopEffect(); controller.previewWhite(b, w) }, onDone = controller::commit)
                1 -> ColourTab(state, data, haptics,
                    onChange = { h, s, v -> stopEffect(); controller.previewColour(h, s, v) }, onDone = controller::commit)
                2 -> ScenesTab(data, controller, state, haptics, onApply = { stopEffect(); controller.applyPreset(it) })
                else -> EffectsTab(state, running, haptics)
            }
        }

        Spacer(Modifier.height(16.dp))
        SleepTimerCard(controller, haptics)
    }
}

private fun headline(s: BulbState): String = when {
    !s.on -> "Off"
    s.mode == BulbMode.SCENE -> "Scene"
    s.mode == BulbMode.COLOUR -> "${(s.value * 100).roundToInt()}%"
    else -> "${(s.brightness * 100).roundToInt()}%"
}

private fun subline(s: BulbState, effect: EffectConfig?): String = when {
    effect != null -> "${effect.type.title} playing"
    !s.on -> "Tap the light to turn it on"
    s.mode == BulbMode.SCENE -> "Animated scene"
    s.mode == BulbMode.COLOUR -> colourName(s.hue, s.saturation)
    else -> "${kelvinName(s.warmth)} · ${"%,d".format(kelvin(s.warmth))}K"
}

fun kelvinName(w: Float) = when {
    w < 0.12f -> "Candle"
    w < 0.35f -> "Warm white"
    w < 0.65f -> "Neutral white"
    w < 0.88f -> "Daylight"
    else -> "Cool daylight"
}

fun colourName(h: Float, s: Float): String {
    if (s < 0.18f) return "Soft white"
    val hue = ((h % 360) + 360) % 360
    val name = when {
        hue < 12 -> "Red"; hue < 30 -> "Ember"; hue < 45 -> "Amber"; hue < 65 -> "Gold"; hue < 90 -> "Lime"
        hue < 150 -> "Green"; hue < 175 -> "Mint"; hue < 200 -> "Teal"; hue < 225 -> "Sky"; hue < 255 -> "Blue"
        hue < 280 -> "Indigo"; hue < 305 -> "Violet"; hue < 335 -> "Magenta"; else -> "Rose"
    }
    return if (s < 0.5f) "Pastel ${name.lowercase()}" else name
}

@Composable
private fun TopRow(
    data: AppData,
    controller: LightController,
    conn: Conn,
    onAddLight: () -> Unit,
    onEditLight: () -> Unit,
    onRefresh: () -> Unit,
) {
    val halo = LocalHalo.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(controller.device.name, style = MaterialTheme.typography.headlineMedium, color = halo.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, null, tint = halo.textDim)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    data.devices.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.name) },
                            leadingIcon = { if (d.id == controller.device.id) Icon(Icons.Rounded.Check, null) },
                            onClick = { menu = false; Store.update { it.copy(activeDeviceId = d.id) } },
                        )
                    }
                    DropdownMenuItem(text = { Text("Light settings") }, onClick = { menu = false; onEditLight() })
                    DropdownMenuItem(text = { Text("Add a light") }, leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { menu = false; onAddLight() })
                }
            }
            StatusPill(conn)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = halo.textDim) }
    }
}

@Composable
fun StatusPill(conn: Conn) {
    val halo = LocalHalo.current
    val (color, text) = when (conn) {
        Conn.Online -> halo.good to "Connected over Wi-Fi"
        Conn.Connecting -> halo.accent to "Connecting…"
        Conn.Idle -> halo.textFaint to "Ready"
        is Conn.Offline -> halo.danger to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp).testTag("status")) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = halo.textDim)
    }
}

@Composable
private fun OfflineCard(message: String, badKey: Boolean, onRetry: () -> Unit, onFix: () -> Unit) {
    val halo = LocalHalo.current
    HaloCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, null, tint = halo.danger)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = halo.text, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Try again", primary = true, onClick = onRetry)
            if (badKey) PillButton("Check settings", onClick = onFix)
        }
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val halo = LocalHalo.current
    val bg = if (primary) halo.accent else halo.surfaceHigh
    val fg = if (primary) halo.onAccent else halo.text
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) bg else halo.surfaceHigh.copy(alpha = 0.5f))
            .border(1.dp, if (primary) Color.Transparent else halo.outline, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp)
            .testTag("btn-$text"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else halo.textFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else halo.textFaint)
    }
}

@Composable
private fun PowerRow(state: BulbState, haptics: Haptics, onPower: (Boolean) -> Unit) {
    val halo = LocalHalo.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(true to "On", false to "Off").forEach { (on, label) ->
            val selected = state.on == on
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) halo.accent.copy(alpha = if (on) 0.22f else 0.08f) else halo.surface)
                    .border(1.dp, if (selected) halo.accent.copy(alpha = 0.5f) else halo.outline, RoundedCornerShape(18.dp))
                    .clickable { haptics.press(); onPower(on) }
                    .testTag("power-$label"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = if (selected) halo.text else halo.textDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) halo.text else halo.textDim)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhiteTab(state: BulbState, haptics: Haptics, onChange: (Float, Float) -> Unit, onDone: () -> Unit) {
    val halo = LocalHalo.current
    val b = if (state.mode == BulbMode.WHITE) state.brightness else state.value
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PillSlider(
            value = b, onValueChange = { onChange(it, state.warmth) }, onChangeFinished = onDone,
            label = "Brightness", valueText = "${(b * 100).roundToInt()}%", icon = Icons.Rounded.LightMode,
            haptics = haptics, tag = "brightness",
        )
        PillSlider(
            value = state.warmth, onValueChange = { onChange(b, it) }, onChangeFinished = onDone,
            label = "Warmth", valueText = "${"%,d".format(kelvin(state.warmth))}K", icon = Icons.Rounded.Thermostat,
            brush = Brush.horizontalGradient(listOf(kelvinColor(0f), kelvinColor(0.5f), kelvinColor(1f))),
            haptics = haptics, tag = "warmth",
        )
        SectionLabel("Quick whites")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Candle" to 0f, "Warm" to 0.18f, "Soft" to 0.35f, "Neutral" to 0.5f, "Daylight" to 0.82f, "Cool" to 1f).forEach { (name, w) ->
                val selected = state.mode == BulbMode.WHITE && kotlin.math.abs(state.warmth - w) < 0.03f
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) halo.surfaceHigh else halo.surface)
                        .border(1.dp, if (selected) halo.accent.copy(alpha = 0.6f) else halo.outline, RoundedCornerShape(16.dp))
                        .clickable { haptics.tick(); onChange(b.coerceAtLeast(0.05f), w); onDone() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(kelvinColor(w)))
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.labelLarge, color = halo.text)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ColourTab(
    state: BulbState,
    data: AppData,
    haptics: Haptics,
    onChange: (Float, Float, Float) -> Unit,
    onDone: () -> Unit,
) {
    val halo = LocalHalo.current
    val v = if (state.mode == BulbMode.COLOUR) state.value else state.brightness.coerceAtLeast(0.3f)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ColorWheel(
            hue = state.hue, saturation = state.saturation,
            onChange = { h, s -> onChange(h, s, v) }, onChangeFinished = onDone,
            haptics = haptics,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().padding(8.dp),
        )
        PillSlider(
            value = v, onValueChange = { onChange(state.hue, state.saturation, it.coerceAtLeast(0.01f)) }, onChangeFinished = onDone,
            label = "Brightness", valueText = "${(v * 100).roundToInt()}%", icon = Icons.Rounded.BrightnessMedium,
            fill = Color.hsv(((state.hue % 360) + 360) % 360, state.saturation, 1f), haptics = haptics, tag = "colour-brightness",
        )
        PillSlider(
            value = state.saturation, onValueChange = { onChange(state.hue, it, v) }, onChangeFinished = onDone,
            label = "Saturation", valueText = "${(state.saturation * 100).roundToInt()}%",
            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(((state.hue % 360) + 360) % 360, 1f, 1f))),
            haptics = haptics, tag = "saturation",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Your colours", Modifier.weight(1f))
            Text("Hold to remove", style = MaterialTheme.typography.labelMedium, color = halo.textFaint)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            data.settings.swatches.forEach { sw ->
                val c = Color.hsv(sw.hue, sw.saturation, 1f)
                val selected = state.mode == BulbMode.COLOUR && kotlin.math.abs(state.hue - sw.hue) < 2 && kotlin.math.abs(state.saturation - sw.saturation) < 0.03f
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(2.dp, if (selected) Color.White else halo.outline, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(c)
                        .combinedClickable(
                            onClick = { haptics.tick(); onChange(sw.hue, sw.saturation, v); onDone() },
                            onLongClick = { haptics.press(); Store.settings { s -> s.copy(swatches = s.swatches - sw) } },
                        ),
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, halo.outline, CircleShape)
                    .clickable {
                        haptics.press()
                        Store.settings { s -> s.copy(swatches = (s.swatches + SceneColour(state.hue, state.saturation)).takeLast(16)) }
                    }
                    .testTag("add-swatch"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "Save colour", tint = halo.textDim) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ScenesTab(data: AppData, controller: LightController, state: BulbState, haptics: Haptics, onApply: (Preset) -> Unit) {
    val halo = LocalHalo.current
    val activeId by controller.activePresetId.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Preset?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Your presets", Modifier.weight(1f))
            PillButton("Save current", icon = Icons.Rounded.Add) { saving = true }
        }
        if (data.presets.isEmpty()) {
            Text("Set the light how you like it, then tap Save current to keep it here.",
                style = MaterialTheme.typography.bodyMedium, color = halo.textDim, modifier = Modifier.padding(horizontal = 6.dp))
        } else {
            PresetGrid(data.presets, activeId, haptics, onApply, onLong = { deleting = it })
        }
        SectionLabel("Moods")
        PresetGrid(BuiltInPresets.all, activeId, haptics, onApply)
        if (controller.schema == BulbSchema.V2) {
            SectionLabel("Living scenes · run on the bulb")
            PresetGrid(BuiltInPresets.bulbScenes, activeId, haptics, onApply)
        } else {
            SectionLabel("Bulb scenes")
            PresetGrid(BuiltInPresets.classicScenes, activeId, haptics, onApply)
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.testTag("preset-name"))
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = when (state.mode) {
                        BulbMode.COLOUR -> Preset(name = name.ifBlank { colourName(state.hue, state.saturation) }, kind = PresetKind.COLOUR,
                            hue = state.hue, saturation = state.saturation, brightness = state.value)
                        else -> Preset(name = name.ifBlank { kelvinName(state.warmth) }, kind = PresetKind.WHITE,
                            brightness = state.brightness, warmth = state.warmth)
                    }
                    Store.upsertPreset(p)
                    saving = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            confirmButton = { TextButton(onClick = { Store.deletePreset(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

fun presetBrush(p: Preset): Brush = when (p.kind) {
    PresetKind.WHITE -> Brush.linearGradient(listOf(kelvinColor(p.warmth).copy(alpha = 0.25f + 0.6f * p.brightness), kelvinColor(p.warmth).copy(alpha = 0.08f)))
    PresetKind.COLOUR -> Brush.linearGradient(listOf(Color.hsv(p.hue, p.saturation, 1f).copy(alpha = 0.3f + 0.6f * p.brightness), Color.hsv(p.hue, p.saturation, 0.5f).copy(alpha = 0.15f)))
    PresetKind.BULB_SCENE -> Brush.linearGradient(p.colours.map { Color.hsv(it.hue, it.saturation, 1f).copy(alpha = 0.75f) }.ifEmpty { listOf(Color.Gray, Color.DarkGray) })
    PresetKind.CLASSIC_SCENE -> Brush.linearGradient(when (p.classicIndex) {
        1 -> listOf(Color(0xFF3DDC97), Color(0xFF2B6CB0)); 2 -> listOf(Color(0xFFFF6B9A), Color(0xFF7C3AED))
        3 -> listOf(Color(0xFFFF3D3D), Color(0xFF3DFF8A), Color(0xFF3D7BFF)); else -> listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)
    }.map { it.copy(alpha = 0.7f) })
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PresetGrid(presets: List<Preset>, activeId: String?, haptics: Haptics, onApply: (Preset) -> Unit, onLong: ((Preset) -> Unit)? = null) {
    val halo = LocalHalo.current
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
        presets.forEach { p ->
            val active = p.id == activeId
            Box(
                Modifier
                    .weight(1f)
                    .height(84.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(halo.surface)
                    .background(presetBrush(p))
                    .border(if (active) 2.dp else 1.dp, if (active) Color.White.copy(alpha = 0.85f) else halo.outline, RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = { haptics.tick(); onApply(p) },
                        onLongClick = onLong?.let { { haptics.press(); it(p) } },
                    )
                    .padding(14.dp)
                    .testTag("preset-${p.name}"),
            ) {
                if (p.emoji != null) Text(p.emoji, fontSize = 20.sp, modifier = Modifier.align(Alignment.TopStart))
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        if (presets.size % 2 == 1) Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsTab(state: BulbState, running: EffectConfig?, haptics: Haptics) {
    val halo = LocalHalo.current
    val context = LocalContext.current
    var speed by rememberSaveable { mutableFloatStateOf(running?.speed ?: 0.5f) }
    var intensity by rememberSaveable { mutableFloatStateOf(running?.intensity ?: 0.7f) }
    var pendingMusic by remember { mutableStateOf(false) }

    fun configFor(t: EffectType) = EffectConfig(t, speed, intensity,
        hue = if (state.mode == BulbMode.COLOUR) state.hue else 30f,
        saturation = if (state.mode == BulbMode.COLOUR) state.saturation.coerceAtLeast(0.4f) else 0.9f)

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingMusic) Effects.start(context, configFor(EffectType.MUSIC))
        pendingMusic = false
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start(t: EffectType) {
        haptics.press()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (t == EffectType.MUSIC && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMusic = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Effects.start(context, configFor(t))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (running != null) {
            HaloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = halo.accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${running.type.title} is playing", style = MaterialTheme.typography.titleMedium, color = halo.text)
                        Text("Keeps going with the screen off", style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
                    }
                    PillButton("Stop", icon = Icons.Rounded.Stop, primary = true) { Effects.stop(context) }
                }
            }
        }
        PillSlider(speed, { speed = it }, label = "Speed", valueText = "${(speed * 100).roundToInt()}%", haptics = haptics, tag = "speed",
            onChangeFinished = { running?.let { Effects.start(context, it.copy(speed = speed)) } })
        PillSlider(intensity, { intensity = it }, label = "Intensity", valueText = "${(intensity * 100).roundToInt()}%", haptics = haptics, tag = "intensity",
            onChangeFinished = { running?.let { Effects.start(context, it.copy(intensity = intensity)) } })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
            EffectType.entries.forEach { t ->
                val active = running?.type == t
                Column(
                    Modifier
                        .weight(1f)
                        .height(104.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(halo.surface)
                        .background(effectBrush(t))
                        .border(if (active) 2.dp else 1.dp, if (active) Color.White.copy(alpha = 0.85f) else halo.outline, RoundedCornerShape(20.dp))
                        .clickable { if (active) Effects.stop(context) else start(t) }
                        .padding(14.dp)
                        .testTag("effect-${t.title}"),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(if (active) "Playing · tap to stop" else t.blurb, style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (EffectType.entries.size % 2 == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun effectBrush(t: EffectType): Brush = Brush.linearGradient(
    when (t) {
        EffectType.BREATHE -> listOf(Color(0x66FFB547), Color(0x11FFB547))
        EffectType.CANDLE -> listOf(Color(0x88FF7A2E), Color(0x22FFB547))
        EffectType.RAINBOW -> listOf(Color(0x77FF4D4D), Color(0x77FFD24D), Color(0x774DFF88), Color(0x774DA6FF), Color(0x77C44DFF))
        EffectType.OCEAN -> listOf(Color(0x7718C8D6), Color(0x332E5BFF))
        EffectType.PARTY -> listOf(Color(0x88FF2E9A), Color(0x667C3AED), Color(0x6618E0FF))
        EffectType.LIGHTNING -> listOf(Color(0x552E3B80), Color(0x22DDE8FF))
        EffectType.MUSIC -> listOf(Color(0x77A855F7), Color(0x5522D3EE))
    },
)

@Composable
private fun SleepTimerCard(controller: LightController, haptics: Haptics) {
    val halo = LocalHalo.current
    val endsAt by controller.sleepEndsAt.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) { while (endsAt != null) { now = System.currentTimeMillis(); delay(15_000) } }
    HaloCard(title = "Sleep timer", trailing = {
        if (endsAt != null) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(); controller.setCountdown(0) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Close, null, tint = halo.textDim, modifier = Modifier.size(16.dp))
                Text("Cancel", style = MaterialTheme.typography.labelMedium, color = halo.textDim)
            }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, tint = halo.accent)
            Spacer(Modifier.width(10.dp))
            val e = endsAt
            Text(
                if (e != null && e > now) "Turns off at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e))}"
                else "The bulb switches itself off, even if your phone is away.",
                style = MaterialTheme.typography.bodyMedium, color = halo.text,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 30, 60, 90, 120).forEach { m ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(halo.surfaceHigh)
                        .clickable { haptics.tick(); controller.setCountdown(m * 60) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m",
                        style = MaterialTheme.typography.labelLarge, color = halo.text, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
