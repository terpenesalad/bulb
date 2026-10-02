package app.halo.ui.screens

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.halo.data.AppData
import app.halo.data.SavedDevice
import app.halo.data.Store
import app.halo.effects.Effects
import app.halo.light.Diagnostics
import app.halo.light.LightHub
import app.halo.tuya.BulbSchema
import app.halo.tuya.CloudDevice
import app.halo.tuya.DiscoveredDevice
import app.halo.tuya.TuyaCloud
import app.halo.tuya.TuyaDevice
import app.halo.tuya.TuyaDeviceConfig
import app.halo.tuya.TuyaDiscovery
import app.halo.tuya.TuyaException
import app.halo.tuya.TuyaVersion
import app.halo.ui.components.HaloCard
import app.halo.ui.components.SectionLabel
import app.halo.ui.theme.LocalHalo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun SetupScreen(data: AppData, editing: SavedDevice?, onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val halo = LocalHalo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(editing?.name ?: if (data.devices.isEmpty()) "Bedroom light" else "") }
    var devId by remember { mutableStateOf(editing?.id ?: "") }
    var host by remember { mutableStateOf(editing?.host ?: "") }
    var key by remember { mutableStateOf(editing?.localKey ?: "") }
    var version by remember { mutableStateOf(TuyaVersion.from(editing?.version)) }
    var showKey by remember { mutableStateOf(false) }

    val found = remember { mutableStateListOf<DiscoveredDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    var accessId by remember { mutableStateOf(data.settings.tuyaAccessId) }
    var accessSecret by remember { mutableStateOf("") }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudDevices by remember { mutableStateOf<List<CloudDevice>>(emptyList()) }
    var showHelp by remember { mutableStateOf(data.devices.isEmpty()) }

    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }

    fun startScan() {
        scanJob?.cancel()
        scanning = true
        scanJob = scope.launch {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("halo-scan").apply { setReferenceCounted(false); acquire() }
            try {
                withTimeout(20_000) {
                    TuyaDiscovery.scan().collect { d ->
                        if (found.none { it.id == d.id }) found.add(d)
                        // Fill in the blanks automatically.
                        if (devId.isBlank() || devId == d.id) {
                            if (devId.isBlank() && found.size == 1) devId = d.id
                            if (devId == d.id) { host = d.ip; version = d.version }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { lock.release() }
                scanning = false
            }
        }
    }

    LaunchedEffect(Unit) { if (editing == null) startScan() }
    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }

    fun fetchFromCloud() {
        cloudBusy = true
        cloudError = null
        scope.launch {
            try {
                val (region, list) = TuyaCloud.fetchDevices(accessId, accessSecret)
                Store.settings { it.copy(tuyaAccessId = accessId.trim(), tuyaRegion = region.name) }
                cloudDevices = list.sortedByDescending { d -> found.any { it.id == d.id } || d.category == "dj" }
                // If one matches what we found on the network (or the only bulb), pick it.
                val pick = list.firstOrNull { d -> found.any { it.id == d.id } } ?: list.singleOrNull()
                    ?: list.firstOrNull { it.id == devId }
                pick?.let { d ->
                    devId = d.id; key = d.localKey
                    if (name.isBlank() || name == "Bedroom light") name = d.name
                    found.firstOrNull { it.id == d.id }?.let { host = it.ip; version = it.version }
                }
            } catch (e: Exception) {
                cloudError = e.message ?: "Couldn't reach Tuya"
            } finally {
                cloudBusy = false
            }
        }
    }

    fun connectAndSave(force: Boolean) {
        val cleanKey = key.trim()
        if (force) {
            Store.upsertDevice(SavedDevice(devId.trim(), name.ifBlank { "Light" }, host.trim(), cleanKey, version.label, editing?.schema))
            if (editing != null && editing.id != devId.trim()) Store.removeDevice(editing.id)
            onDone(); return
        }
        testing = true
        testFailed = false
        testMessage = "Connecting…"
        scope.launch {
            // Bulbs often allow only one connection, so let go of ours while testing.
            Effects.stop(context)
            LightHub.activeController()?.idle()
            delay(300)
            val order = listOf(version) + TuyaVersion.entries.filter { it != version }
            var lastError: String? = null
            for (v in order) {
                testMessage = "Trying protocol ${v.label}…"
                val dev = runCatching {
                    TuyaDevice(TuyaDeviceConfig(devId.trim(), host.trim(), cleanKey, v)) { Diagnostics.log("setup", it) }
                }.getOrElse { e -> lastError = e.message; null } ?: break
                try {
                    val dps = withTimeout(10_000) { dev.status() }
                    val schema = BulbSchema.detect(dps)
                    version = v
                    Store.upsertDevice(SavedDevice(devId.trim(), name.ifBlank { "Light" }, host.trim(), cleanKey, v.label, schema?.name))
                    if (editing != null && editing.id != devId.trim()) Store.removeDevice(editing.id)
                    testMessage = "Connected!"
                    testing = false
                    dev.close()
                    LightHub.onForeground()
                    onDone()
                    return@launch
                } catch (e: TuyaException.Unreachable) {
                    lastError = e.message
                    dev.close()
                    break // a network problem; other versions won't help
                } catch (e: Exception) {
                    lastError = when (e) {
                        is kotlinx.coroutines.TimeoutCancellationException -> "The light didn't answer."
                        else -> e.message
                    }
                    dev.close()
                }
            }
            testing = false
            testFailed = true
            testMessage = (lastError ?: "Couldn't connect.") + " Double-check the local key (it changes whenever the light is re-paired)."
        }
    }

    val valid = devId.isNotBlank() && host.isNotBlank() && key.trim().length == 16

    Column(
        Modifier
            .fillMaxSize()
            .background(halo.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = halo.text) }
            else Spacer(Modifier.height(48.dp))
        }
        if (editing == null && data.devices.isEmpty()) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(halo.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lightbulb, null, tint = halo.accent, modifier = Modifier.size(34.dp))
            }
            Text("Welcome to Halo", style = MaterialTheme.typography.displayMedium, color = halo.text)
            Text("Let's connect your light. Everything runs over your home Wi-Fi; no cloud needed after this.",
                style = MaterialTheme.typography.bodyLarge, color = halo.textDim)
        } else {
            Text(if (editing != null) "Light settings" else "Add a light", style = MaterialTheme.typography.headlineMedium, color = halo.text)
        }

        // ---- 1. Scan
        HaloCard(title = "1 · Find it on your Wi-Fi", trailing = {
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = halo.accent)
        }) {
            Text("Your phone needs to be on the same Wi-Fi network as the light.", style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            found.forEach { d ->
                val selected = d.id == devId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) halo.accent.copy(alpha = 0.15f) else halo.surfaceHigh)
                        .border(1.dp, if (selected) halo.accent else halo.outline, RoundedCornerShape(16.dp))
                        .clickable { devId = d.id; host = d.ip; version = d.version }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = if (selected) halo.accent else halo.textDim)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cloudDevices.firstOrNull { it.id == d.id }?.name ?: "Tuya device", style = MaterialTheme.typography.titleMedium, color = halo.text)
                        Text("${d.ip} · v${d.version.label} · …${d.id.takeLast(6)}", style = MaterialTheme.typography.labelMedium, color = halo.textDim)
                    }
                }
            }
            if (!scanning && found.isEmpty()) {
                Text("Nothing heard yet. Check the light is switched on, then scan again. You can also type its IP address below.",
                    style = MaterialTheme.typography.bodyMedium, color = halo.text)
            }
            PillButton(if (scanning) "Scanning…" else "Scan again", icon = Icons.Rounded.Radar, enabled = !scanning) { startScan() }
        }

        // ---- 2. Key
        HaloCard(title = "2 · Get its local key") {
            Text("Tuya lights need a secret “local key”. Halo can fetch it from your free Tuya developer account.",
                style = MaterialTheme.typography.bodyMedium, color = halo.textDim)
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { showHelp = !showHelp }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("How do I get these?", style = MaterialTheme.typography.labelLarge, color = halo.accent)
                Icon(if (showHelp) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = halo.accent)
            }
            AnimatedVisibility(showHelp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Move the light into the Smart Life app (remove it from the Genio app, then add it in Smart Life).",
                        "Sign up at platform.tuya.com, then Cloud → Development → Create Cloud Project. Choose “Smart Home” and tick every data centre.",
                        "In the project, open Devices → Link App Account → Add App Account and scan the QR code with Smart Life (Me tab → scan icon).",
                        "Copy the Access ID and Access Secret from the project's Overview page into the boxes below.",
                    ).forEachIndexed { i, step ->
                        Row {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = halo.accent, modifier = Modifier.width(22.dp))
                            Text(step, style = MaterialTheme.typography.bodyMedium, color = halo.text)
                        }
                    }
                }
            }
            HaloField(accessId, { accessId = it.trim() }, "Access ID / Client ID", tag = "access-id")
            HaloField(accessSecret, { accessSecret = it.trim() }, "Access Secret", secret = true, tag = "access-secret")
            Text("The secret is only used for this request and isn't saved.", style = MaterialTheme.typography.labelMedium, color = halo.textFaint)
            PillButton(if (cloudBusy) "Fetching…" else "Fetch my lights", primary = true, icon = Icons.Rounded.CloudDownload,
                enabled = !cloudBusy && accessId.isNotBlank() && accessSecret.isNotBlank()) { fetchFromCloud() }
            cloudError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = halo.danger) }
            cloudDevices.forEach { d ->
                val selected = d.id == devId
                val onNetwork = found.firstOrNull { it.id == d.id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) halo.accent.copy(alpha = 0.15f) else halo.surfaceHigh)
                        .border(1.dp, if (selected) halo.accent else halo.outline, RoundedCornerShape(16.dp))
                        .clickable {
                            devId = d.id; key = d.localKey
                            if (name.isBlank() || name == "Bedroom light") name = d.name
                            onNetwork?.let { host = it.ip; version = it.version }
                        }
                        .padding(14.dp),
                ) {
                    Column {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = halo.text)
                        Text(listOfNotNull(d.productName, if (onNetwork != null) "found on Wi-Fi" else "not seen on Wi-Fi yet").joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = halo.textDim)
                    }
                }
            }
        }

        // ---- 3. Details
        HaloCard(title = "3 · Check the details") {
            HaloField(name, { name = it.take(24) }, "Name", tag = "name")
            HaloField(devId, { devId = it.trim() }, "Device ID", tag = "device-id")
            HaloField(host, { host = it.trim() }, "IP address", keyboard = KeyboardType.Uri, tag = "host")
            HaloField(key, { key = it }, "Local key (16 characters)", secret = !showKey, tag = "local-key",
                trailing = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key", tint = halo.textDim)
                    }
                })
            if (key.isNotEmpty() && key.trim().length != 16) {
                Text("The key should be exactly 16 characters (this one is ${key.trim().length}).", style = MaterialTheme.typography.labelMedium, color = halo.danger)
            }
            SectionLabel("Protocol version")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuyaVersion.entries.forEach { v -> Chip(v.label, version == v) { version = v } }
            }
            Text("Not sure? Leave it. Halo tries the others if this one doesn't work.", style = MaterialTheme.typography.labelMedium, color = halo.textFaint)
        }

        testMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (testFailed) halo.danger else halo.text, modifier = Modifier.testTag("test-message"))
        }
        PillButton(if (testing) "Connecting…" else "Connect & save", primary = true, modifier = Modifier.fillMaxWidth(), enabled = valid && !testing) {
            connectAndSave(force = false)
        }
        if (testFailed && valid) {
            PillButton("Save anyway", modifier = Modifier.fillMaxWidth()) { connectAndSave(force = true) }
        }
        if (editing != null) {
            PillButton("Remove this light", modifier = Modifier.fillMaxWidth()) {
                LightHub.forget(editing.id)
                Store.removeDevice(editing.id)
                onDone()
            }
        }
    }
}

@Composable
fun HaloField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    tag: String = label,
    trailing: @Composable (() -> Unit)? = null,
) {
    val halo = LocalHalo.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth().testTag("field-$tag"),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = halo.accent, unfocusedBorderColor = halo.outline,
            focusedLabelColor = halo.accent, cursorColor = halo.accent,
        ),
    )
}
