package app.halo.light

import app.halo.data.SavedDevice
import app.halo.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns one [LightController] per saved light, shared by the UI, widget, tile, effects and schedules. */
@OptIn(ExperimentalCoroutinesApi::class)
object LightHub {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = HashMap<String, LightController>()
    private var idleJob: Job? = null

    /** Number of things (effects, schedules) that need the connection kept while the app is hidden. */
    @Volatile var keepAlive = 0

    @Synchronized
    fun controller(device: SavedDevice): LightController {
        val existing = controllers[device.id]
        if (existing != null && existing.device.host == device.host && existing.device.localKey == device.localKey &&
            existing.device.version == device.version) return existing
        existing?.close()
        return LightController(device, scope).also { controllers[device.id] = it }
    }

    fun activeController(): LightController? = Store.value.activeDevice?.let { controller(it) }

    val active: StateFlow<LightController?> = Store.data
        .map { it.activeDevice }
        .distinctUntilChanged()
        .map { d -> d?.let { controller(it) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @Synchronized
    fun forget(id: String) {
        controllers.remove(id)?.close()
    }

    /** App came to the foreground. */
    fun onForeground() {
        idleJob?.cancel()
        scope.launch { activeController()?.refresh() }
    }

    /** App went to the background: free the bulb's connection (it only accepts a few) after a grace period. */
    fun onBackground() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(20_000)
            if (keepAlive > 0) return@launch
            synchronized(this@LightHub) { controllers.values.toList() }.forEach { it.idle() }
        }
    }
}
