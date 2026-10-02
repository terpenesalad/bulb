package app.halo.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.BuildConfig
import app.halo.R
import app.halo.data.AppData
import app.halo.data.SavedDevice
import app.halo.data.Store
import app.halo.data.ThemeStyle
import app.halo.light.Diagnostics
import app.halo.schedule.Scheduler
import app.halo.ui.components.HaloCard
import app.halo.ui.components.PillSlider
import app.halo.ui.components.SectionLabel
import app.halo.ui.components.rememberHaptics
import app.halo.ui.theme.AccentChoices
import app.halo.ui.theme.LocalHalo
import app.halo.ui.theme.palette
import app.halo.widget.LightTileService
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(data: AppData, onEditLight: (SavedDevice) -> Unit, onAddLight: () -> Unit) {
    val halo = LocalHalo.current
    val s = data.settings
    val haptics = rememberHaptics(s.haptics)
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = halo.text, modifier = Modifier.padding(top = 12.dp))

        // ---------------- Appearance
        SectionLabel("Appearance")
        HaloCard {
            Text("Theme", style = MaterialTheme.typography.titleMedium, color = halo.text)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeStyle.entries.forEach { t ->
                    val p = palette(t, halo.accent)
                    val selected = s.theme == t
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(p.background)
                            .border(if (selected) 2.dp else 1.dp, if (selected) halo.accent else halo.outline, RoundedCornerShape(18.dp))
                            .clickable { haptics.tick(); Store.settings { it.copy(theme = t) } }
                            .padding(12.dp)
                            .testTag("theme-${t.name}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.surfaceHigh))
                        Box(Modifier.fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(halo.accent.copy(alpha = 0.8f)))
                        Text(when (t) { ThemeStyle.AMOLED -> "AMOLED"; ThemeStyle.MIDNIGHT -> "Midnight"; ThemeStyle.GRAPHITE -> "Graphite" },
                            style = MaterialTheme.typography.labelLarge, color = p.text)
                    }
                }
            }
            ToggleRow("Match my light", "Tint the app with the bulb's current colour", s.adaptiveAccent) { v -> Store.settings { it.copy(adaptiveAccent = v) } }
            AnimatedVisibility(!s.adaptiveAccent) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accent", style = MaterialTheme.typography.titleMedium, color = halo.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccentChoices.forEach { a ->
                            val selected = s.accent == a
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(a))
                                    .clickable { haptics.tick(); Store.settings { it.copy(accent = a) } },
                            )
                        }
                    }
                }
            }
            PillSlider(s.glow, { v -> Store.settings { it.copy(glow = v) } }, label = "Glow", valueText = "${(s.glow * 100).roundToInt()}%",
                haptics = haptics, tag = "glow")
            ToggleRow("Animations", "Breathing glow and smooth transitions", s.animations) { v -> Store.settings { it.copy(animations = v) } }
            ToggleRow("Haptics", "Gentle ticks as you slide", s.haptics) { v -> Store.settings { it.copy(haptics = v) } }
            ToggleRow("24-hour time", null, s.use24h) { v -> Store.settings { it.copy(use24h = v) } }
        }

        // ---------------- Lights
        SectionLabel("Lights")
        HaloCard {
            data.devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onEditLight(d) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = if (d.id == data.activeDevice?.id) halo.accent else halo.textDim)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = halo.text)
                        Text("${d.host} · protocol ${d.version}${d.schema?.let { " · ${if (it == "V1") "classic" else "modern"} bulb" } ?: ""}",
                            style = MaterialTheme.typography.labelMedium, color = halo.textDim)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = halo.textDim)
                }
            }
            PillButton("Add a light", icon = Icons.Rounded.Add, onClick = onAddLight)
        }

        // ---------------- Location
        SectionLabel("Location · for sunrise and sunset")
        LocationCard(data)

        // ---------------- Shortcuts
        SectionLabel("Shortcuts")
        HaloCard {
            Text("Home screen widget", style = MaterialTheme.typography.titleMedium, color = halo.text)
            Text("Long-press your home screen → Widgets → Halo. One tap toggles the light.", style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium, color = halo.text)
            Text("Toggle the light from the notification shade.", style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            if (Build.VERSION.SDK_INT >= 33) {
                PillButton("Add tile") { requestTile(context) }
            } else {
                Text("Pull down Quick Settings → edit (pencil) → drag in “Bedroom light”.", style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            }
        }

        // ---------------- Reliability
        SectionLabel("Reliability")
        ReliabilityCard()

        // ---------------- Diagnostics
        SectionLabel("Diagnostics")
        DiagnosticsCard()

        Text("Halo ${BuildConfig.VERSION_NAME} · talks to your light directly over Wi-Fi",
            style = MaterialTheme.typography.labelMedium, color = halo.textFaint, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
    }
}

@SuppressLint("WrongConstant")
private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = context.getSystemService(StatusBarManager::class.java)
    runCatching {
        sbm.requestAddTileService(
            ComponentName(context, LightTileService::class.java),
            Store.value.activeDevice?.name ?: "Bedroom light",
            AndroidIcon.createWithResource(context, R.drawable.ic_bulb),
            ContextCompat.getMainExecutor(context),
        ) { }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val halo = LocalHalo.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = halo.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = halo.accent, checkedThumbColor = halo.onAccent,
                uncheckedTrackColor = halo.surfaceHigh, uncheckedBorderColor = halo.outline, uncheckedThumbColor = halo.textDim),
        )
    }
}

@Composable
private fun LocationCard(data: AppData) {
    val halo = LocalHalo.current
    val context = LocalContext.current
    val s = data.settings
    var manual by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun useCurrent() {
        val lm = context.getSystemService(LocationManager::class.java)
        @SuppressLint("MissingPermission")
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) {
            message = "Couldn't get a location fix. Open a maps app once, or enter it by hand."
            return
        }
        // Rounded to ~1 km: plenty for sun times, and kept vague on purpose.
        val lat = (loc.latitude * 100).roundToInt() / 100.0
        val lon = (loc.longitude * 100).roundToInt() / 100.0
        Store.settings { it.copy(latitude = lat, longitude = lon, locationLabel = "Current location") }
        message = null
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useCurrent() else message = "Location permission was declined. You can enter it by hand instead."
    }

    HaloCard {
        Text(
            if (s.latitude != null) "${s.locationLabel ?: "Set"} · ${"%.2f".format(Locale.US, s.latitude)}, ${"%.2f".format(Locale.US, s.longitude ?: 0.0)}"
            else "Not set. Needed for schedules that follow sunrise or sunset.",
            style = MaterialTheme.typography.bodyMedium, color = halo.text,
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = halo.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Use my location", icon = Icons.Rounded.MyLocation) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) useCurrent()
                else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            PillButton("Enter") { manual = true }
        }
    }

    if (manual) {
        var lat by remember { mutableStateOf(s.latitude?.toString() ?: "") }
        var lon by remember { mutableStateOf(s.longitude?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Latitude and longitude, e.g. -33.87 and 151.21. Search your suburb's coordinates if unsure.",
                        style = MaterialTheme.typography.bodyMedium)
                    HaloField(lat, { lat = it }, "Latitude", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    HaloField(lon, { lon = it }, "Longitude", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val la = lat.toDoubleOrNull()
                    val lo = lon.toDoubleOrNull()
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        Store.settings { it.copy(latitude = la, longitude = lo, locationLabel = "Custom") }
                        manual = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReliabilityCard() {
    val halo = LocalHalo.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2500); tick++ } }
    val pm = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(tick) { pm.isIgnoringBatteryOptimizations(context.packageName) }
    val exact = remember(tick) { Scheduler.canExact(context) }
    HaloCard {
        StatusLine("Background running", if (unrestricted) "Allowed" else "Restricted by battery saver", unrestricted)
        if (!unrestricted) PillButton("Allow", primary = true) {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
        }
        StatusLine("Exact alarms", if (exact) "On time to the minute" else "May run a few minutes late", exact)
        if (!exact && Build.VERSION.SDK_INT >= 31) PillButton("Allow") {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        }
        Text("Schedules need your phone on the same Wi-Fi as the light when they run. The sleep timer runs on the bulb itself.",
            style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
    }
}

@Composable
private fun StatusLine(title: String, value: String, ok: Boolean) {
    val halo = LocalHalo.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) halo.good else halo.accent))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = halo.text, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = halo.textDim)
    }
}

@Composable
private fun DiagnosticsCard() {
    val halo = LocalHalo.current
    val context = LocalContext.current
    val lines by Diagnostics.lines.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    HaloCard {
        Text("A log of recent connection events. If something isn't working, copy it and share it.",
            style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (open) "Hide log" else "Show log") { open = !open }
            PillButton("Copy", icon = Icons.Rounded.ContentCopy) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Halo log", lines.joinToString("\n")))
            }
        }
        AnimatedVisibility(open) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(halo.background, halo.surfaceHigh)))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(lines.takeLast(120).joinToString("\n").ifBlank { "Nothing yet." }, fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, color = halo.textDim)
                }
            }
        }
    }
}
