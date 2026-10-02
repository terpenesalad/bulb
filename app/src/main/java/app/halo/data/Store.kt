package app.halo.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/** All app state, kept as one small JSON file. Readable synchronously (alarms and widgets need that). */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val writer = Executors.newSingleThreadExecutor()
    private lateinit var file: AtomicFile
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    val value: AppData get() = _data.value

    @Volatile private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = AtomicFile(File(context.filesDir, "halo.json"))
        _data.value = runCatching {
            json.decodeFromString(AppData.serializer(), String(file.readFully(), Charsets.UTF_8))
        }.getOrElse { AppData() }
        loaded = true
    }

    @Synchronized
    fun update(transform: (AppData) -> AppData) {
        val next = transform(_data.value)
        if (next == _data.value) return
        _data.value = next
        val text = json.encodeToString(AppData.serializer(), next)
        writer.execute { save(text) }
    }

    private fun save(text: String) {
        synchronized(file) {
            val out = file.startWrite()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
            }
        }
    }

    // ---- helpers

    fun upsertDevice(device: SavedDevice, makeActive: Boolean = true) = update { d ->
        val list = d.devices.filterNot { it.id == device.id } + device
        d.copy(devices = list, activeDeviceId = if (makeActive) device.id else d.activeDeviceId ?: device.id)
    }

    fun removeDevice(id: String) = update { d ->
        val list = d.devices.filterNot { it.id == id }
        d.copy(devices = list, activeDeviceId = if (d.activeDeviceId == id) list.firstOrNull()?.id else d.activeDeviceId)
    }

    fun settings(transform: (AppSettings) -> AppSettings) = update { it.copy(settings = transform(it.settings)) }

    fun upsertPreset(p: Preset) = update { d -> d.copy(presets = d.presets.filterNot { it.id == p.id } + p) }
    fun deletePreset(id: String) = update { d -> d.copy(presets = d.presets.filterNot { it.id == id }) }

    fun upsertSchedule(s: Schedule) = update { d ->
        val idx = d.schedules.indexOfFirst { it.id == s.id }
        d.copy(schedules = if (idx < 0) d.schedules + s else d.schedules.toMutableList().also { it[idx] = s })
    }
    fun deleteSchedule(id: String) = update { d -> d.copy(schedules = d.schedules.filterNot { it.id == id }) }
}
