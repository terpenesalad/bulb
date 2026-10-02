package app.halo.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.halo.data.AppData
import app.halo.data.BuiltInPresets
import app.halo.data.Schedule
import app.halo.data.ScheduleAction
import app.halo.data.Store
import app.halo.data.TriggerType
import app.halo.schedule.Scheduler
import app.halo.schedule.SunCalc
import app.halo.ui.components.HaloCard
import app.halo.ui.components.PillSlider
import app.halo.ui.components.SectionLabel
import app.halo.ui.components.SegmentedTabs
import app.halo.ui.components.rememberHaptics
import app.halo.ui.theme.LocalHalo
import app.halo.ui.theme.kelvin
import app.halo.ui.theme.kelvinColor
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")

fun timeLabel(h: Int, m: Int, use24h: Boolean): String =
    LocalTime.of(h, m).format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()))

fun actionLabel(s: Schedule, data: AppData): String = when (s.action) {
    ScheduleAction.TURN_ON -> "Turn on · ${(s.brightness * 100).roundToInt()}%"
    ScheduleAction.TURN_OFF -> "Turn off"
    ScheduleAction.WAKE_UP -> "Sunrise wake-up · ${s.fadeMinutes} min"
    ScheduleAction.WIND_DOWN -> "Wind down · ${s.fadeMinutes} min fade"
    ScheduleAction.PRESET -> BuiltInPresets.find(s.presetId, data.presets)?.name ?: "Preset"
}

fun daysLabel(days: Set<Int>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}

fun whenLabel(s: Schedule, data: AppData): String = when (s.trigger) {
    TriggerType.CLOCK -> timeLabel(s.hour, s.minute, data.settings.use24h)
    TriggerType.SUNRISE, TriggerType.SUNSET -> {
        val base = if (s.trigger == TriggerType.SUNRISE) "Sunrise" else "Sunset"
        when {
            s.offsetMinutes == 0 -> base
            s.offsetMinutes > 0 -> "$base +${s.offsetMinutes}m"
            else -> "$base ${s.offsetMinutes}m"
        }
    }
}

@Composable
fun SchedulesScreen(data: AppData, onEdit: (Schedule?) -> Unit) {
    val halo = LocalHalo.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Schedules", style = MaterialTheme.typography.headlineMedium, color = halo.text, modifier = Modifier.weight(1f))
            PillButton("New", primary = true, icon = Icons.Rounded.Add) { onEdit(null) }
        }

        ReliabilityBanner()

        if (data.schedules.isEmpty()) {
            HaloCard {
                Icon(Icons.Rounded.AlarmOn, null, tint = halo.accent, modifier = Modifier.size(28.dp))
                Text("Let your light run your day", style = MaterialTheme.typography.titleLarge, color = halo.text)
                Text("Wake up to a slow sunrise, dim down at bedtime, or switch on at sunset. Pick a starting point:",
                    style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Suggestion(Icons.Rounded.WbSunny, "Sunrise wake-up", "Weekdays 6:45 am, 20 min fade") {
                        Store.upsertSchedule(Schedule(label = "Wake up", hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5),
                            action = ScheduleAction.WAKE_UP, fadeMinutes = 20, brightness = 1f, warmth = 0.45f, deviceId = data.activeDevice?.id))
                    }
                    Suggestion(Icons.Rounded.Nightlight, "Bedtime wind-down", "Every day 10:30 pm, 30 min fade") {
                        Store.upsertSchedule(Schedule(label = "Bedtime", hour = 22, minute = 30, action = ScheduleAction.WIND_DOWN,
                            fadeMinutes = 30, deviceId = data.activeDevice?.id))
                    }
                    Suggestion(Icons.Rounded.LightMode, "On at sunset", "Warm glow when it gets dark") {
                        Store.upsertSchedule(Schedule(label = "Evening", trigger = TriggerType.SUNSET, action = ScheduleAction.TURN_ON,
                            brightness = 0.6f, warmth = 0.15f, deviceId = data.activeDevice?.id))
                    }
                }
            }
        }

        data.schedules.sortedBy { if (it.trigger == TriggerType.CLOCK) it.hour * 60 + it.minute else 2000 }.forEach { s ->
            ScheduleCard(s, data, tick) { onEdit(s) }
        }
    }
}

@Composable
private fun Suggestion(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val halo = LocalHalo.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(halo.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = halo.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = halo.text)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
        }
        Icon(Icons.Rounded.Add, null, tint = halo.textDim)
    }
}

@Composable
private fun ScheduleCard(s: Schedule, data: AppData, tick: Int, onClick: () -> Unit) {
    val halo = LocalHalo.current
    val next = remember(s, data.settings, tick) { if (s.enabled) Scheduler.describeNext(s, data.settings) else null }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(halo.surface)
            .border(1.dp, halo.outline.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
            .testTag("schedule-${s.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(whenLabel(s, data), style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light),
                color = if (s.enabled) halo.text else halo.textFaint)
            Text(listOf(s.label.ifBlank { null }, actionLabel(s, data)).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = if (s.enabled) halo.text else halo.textFaint)
            Text(daysLabel(s.days) + (next?.let { " · $it" } ?: if (s.enabled && (s.trigger != TriggerType.CLOCK && data.settings.latitude == null)) " · set your location in Settings" else ""),
                style = MaterialTheme.typography.labelMedium, color = halo.textDim)
        }
        Switch(
            checked = s.enabled,
            onCheckedChange = { Store.upsertSchedule(s.copy(enabled = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = halo.accent, checkedThumbColor = halo.onAccent,
                uncheckedTrackColor = halo.surfaceHigh, uncheckedBorderColor = halo.outline, uncheckedThumbColor = halo.textDim),
        )
    }
}

/** Nudges for the two settings that make alarms reliable. */
@Composable
private fun ReliabilityBanner() {
    val halo = LocalHalo.current
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val pm = context.getSystemService(PowerManager::class.java)
    val optimized = remember(refresh) { !pm.isIgnoringBatteryOptimizations(context.packageName) }
    LaunchedEffect(Unit) { while (true) { delay(3000); refresh++ } }
    AnimatedVisibility(optimized) {
        HaloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BatteryAlert, null, tint = halo.accent)
                Spacer(Modifier.width(10.dp))
                Text("For on-time schedules, let Halo run in the background.", style = MaterialTheme.typography.bodyMedium,
                    color = halo.text, modifier = Modifier.weight(1f))
            }
            PillButton("Allow", primary = true) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditor(data: AppData, initial: Schedule?, onClose: () -> Unit) {
    val halo = LocalHalo.current
    val haptics = rememberHaptics(data.settings.haptics)
    var s by remember { mutableStateOf(initial ?: Schedule(label = "", deviceId = data.activeDevice?.id)) }
    val timeState = rememberTimePickerState(s.hour, s.minute, data.settings.use24h)
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(timeState.hour, timeState.minute) { s = s.copy(hour = timeState.hour, minute = timeState.minute) }

    Column(
        Modifier
            .fillMaxSize()
            .background(halo.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = halo.text) }
            Text(if (initial == null) "New schedule" else "Edit schedule", style = MaterialTheme.typography.titleLarge,
                color = halo.text, modifier = Modifier.weight(1f))
            if (initial != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = halo.textDim) }
        }

        SectionLabel("What should happen")
        val actions = listOf(
            ScheduleAction.WAKE_UP to "Wake up", ScheduleAction.TURN_ON to "Turn on", ScheduleAction.PRESET to "Preset",
            ScheduleAction.WIND_DOWN to "Wind down", ScheduleAction.TURN_OFF to "Turn off",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (a, label) ->
                Chip(label, s.action == a) { haptics.tick(); s = s.copy(action = a) }
            }
        }
        Text(
            when (s.action) {
                ScheduleAction.WAKE_UP -> "Starts as a deep amber glow and brightens over the fade time, so it's fully bright at the time you pick."
                ScheduleAction.WIND_DOWN -> "Slowly dims from wherever the light is, then switches off."
                ScheduleAction.TURN_ON -> "Switches on at the brightness and warmth below."
                ScheduleAction.TURN_OFF -> "Switches the light off."
                ScheduleAction.PRESET -> "Applies one of your presets or scenes."
            },
            style = MaterialTheme.typography.bodyMedium, color = halo.textDim,
        )

        SectionLabel("When")
        SegmentedTabs(listOf("Time", "Sunrise", "Sunset"), s.trigger.ordinal, { s = s.copy(trigger = TriggerType.entries[it]) }, haptics = haptics)
        if (s.trigger == TriggerType.CLOCK) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = halo.surfaceHigh, selectorColor = halo.accent,
                        timeSelectorSelectedContainerColor = halo.accent.copy(alpha = 0.25f),
                        timeSelectorUnselectedContainerColor = halo.surfaceHigh,
                        timeSelectorSelectedContentColor = halo.text, timeSelectorUnselectedContentColor = halo.textDim,
                        periodSelectorSelectedContainerColor = halo.accent.copy(alpha = 0.25f),
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorSelectedContentColor = halo.text, periodSelectorUnselectedContentColor = halo.textDim,
                        periodSelectorBorderColor = halo.outline, clockDialSelectedContentColor = halo.onAccent,
                        clockDialUnselectedContentColor = halo.textDim,
                    ),
                )
            }
        } else {
            val settings = data.settings
            if (settings.latitude == null) {
                HaloCard {
                    Text("Sunrise and sunset need your location. Add it in Settings → Location (it's only used on this phone).",
                        style = MaterialTheme.typography.bodyMedium, color = halo.text)
                }
            } else {
                val (rise, set) = SunCalc.times(LocalDate.now(), settings.latitude, settings.longitude ?: 0.0, ZoneId.systemDefault())
                    ?: (null to null)
                val base = if (s.trigger == TriggerType.SUNRISE) rise else set
                Text(
                    "Today: ${base?.plusMinutes(s.offsetMinutes.toLong())?.let { timeLabel(it.hour, it.minute, settings.use24h) } ?: "no ${s.trigger.name.lowercase()}"}",
                    style = MaterialTheme.typography.titleMedium, color = halo.text,
                )
            }
            PillSlider(
                value = (s.offsetMinutes + 120) / 240f,
                onValueChange = { s = s.copy(offsetMinutes = ((it * 240 - 120) / 5).roundToInt() * 5) },
                label = "Offset", valueText = when {
                    s.offsetMinutes == 0 -> "Exactly"
                    s.offsetMinutes > 0 -> "${s.offsetMinutes} min after"
                    else -> "${-s.offsetMinutes} min before"
                },
                haptics = haptics, tag = "offset",
            )
        }

        SectionLabel("Repeat")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            (1..7).forEach { d ->
                val on = d in s.days
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (on) halo.accent else halo.surfaceHigh)
                        .clickable { haptics.tick(); s = s.copy(days = if (on) s.days - d else s.days + d) },
                    contentAlignment = Alignment.Center,
                ) { Text(dayNames[d - 1], style = MaterialTheme.typography.labelLarge, color = if (on) halo.onAccent else halo.textDim) }
            }
        }
        Text(daysLabel(s.days) + if (s.days.isEmpty()) " (turns itself off after running)" else "", style = MaterialTheme.typography.labelMedium, color = halo.textDim)

        if (s.action == ScheduleAction.WAKE_UP || s.action == ScheduleAction.WIND_DOWN) {
            SectionLabel("Fade")
            PillSlider((s.fadeMinutes - 1) / 59f, { s = s.copy(fadeMinutes = (1 + it * 59).roundToInt()) },
                label = "Fade time", valueText = "${s.fadeMinutes} min", haptics = haptics, tag = "fade")
        }
        if (s.action == ScheduleAction.WAKE_UP || s.action == ScheduleAction.TURN_ON) {
            SectionLabel("Light")
            PillSlider(s.brightness, { s = s.copy(brightness = it.coerceAtLeast(0.01f)) }, label = "Brightness",
                valueText = "${(s.brightness * 100).roundToInt()}%", icon = Icons.Rounded.LightMode, haptics = haptics, tag = "sched-brightness")
            PillSlider(s.warmth, { s = s.copy(warmth = it) }, label = "Warmth", valueText = "${"%,d".format(kelvin(s.warmth))}K",
                icon = Icons.Rounded.Thermostat,
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(kelvinColor(0f), kelvinColor(0.5f), kelvinColor(1f))),
                haptics = haptics, tag = "sched-warmth")
        }
        if (s.action == ScheduleAction.PRESET) {
            SectionLabel("Preset")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (data.presets + BuiltInPresets.all + BuiltInPresets.bulbScenes).forEach { p ->
                    Chip(p.name, s.presetId == p.id) { haptics.tick(); s = s.copy(presetId = p.id) }
                }
            }
        }

        SectionLabel("Name")
        OutlinedTextField(
            value = s.label, onValueChange = { s = s.copy(label = it.take(30)) }, singleLine = true,
            placeholder = { Text("e.g. Wake up") }, modifier = Modifier.fillMaxWidth().testTag("schedule-name"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = halo.accent, unfocusedBorderColor = halo.outline),
            shape = RoundedCornerShape(16.dp),
        )
        if (data.devices.size > 1) {
            SectionLabel("Light")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.devices.forEach { d -> Chip(d.name, (s.deviceId ?: data.activeDevice?.id) == d.id) { s = s.copy(deviceId = d.id) } }
            }
        }

        Spacer(Modifier.height(4.dp))
        PillButton("Save schedule", primary = true, icon = Icons.Rounded.PowerSettingsNew, modifier = Modifier.fillMaxWidth(),
            enabled = s.action != ScheduleAction.PRESET || s.presetId != null) {
            haptics.press()
            Store.upsertSchedule(s.copy(enabled = true))
            onClose()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this schedule?") },
            confirmButton = { TextButton(onClick = { Store.deleteSchedule(s.id); confirmDelete = false; onClose() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val halo = LocalHalo.current
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) halo.accent.copy(alpha = 0.22f) else halo.surface)
            .border(1.dp, if (selected) halo.accent else halo.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("chip-$label"),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) halo.text else halo.textDim)
    }
}
