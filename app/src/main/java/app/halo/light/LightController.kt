package app.halo.light

import app.halo.data.BuiltInPresets
import app.halo.data.Preset
import app.halo.data.PresetKind
import app.halo.data.SavedDevice
import app.halo.data.Store
import app.halo.tuya.BulbCodec
import app.halo.tuya.BulbMode
import app.halo.tuya.BulbScene
import app.halo.tuya.BulbSchema
import app.halo.tuya.BulbState
import app.halo.tuya.SceneUnit
import app.halo.tuya.TuyaDevice
import app.halo.tuya.TuyaDeviceConfig
import app.halo.tuya.TuyaException
import app.halo.tuya.TuyaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Online : Conn
    data class Offline(val message: String, val badKey: Boolean = false) : Conn
}

/** The app's view of one bulb: optimistic state, smooth slider updates, friendly errors. */
class LightController(val device: SavedDevice, private val scope: CoroutineScope) {
    private val tag = device.name.take(12)
    private val tuya = TuyaDevice(
        TuyaDeviceConfig(device.id, device.host, device.localKey, TuyaVersion.from(device.version)),
        log = { Diagnostics.log(tag, it) },
    )

    private val _state = MutableStateFlow(BulbState())
    val state: StateFlow<BulbState> = _state

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn

    /** When the bulb's own sleep timer will switch it off (epoch ms), if set. */
    val sleepEndsAt = MutableStateFlow<Long?>(null)

    /** The active animated scene/preset id, for highlighting in the UI. */
    val activePresetId = MutableStateFlow<String?>(null)

    @Volatile var schema: BulbSchema = device.schema?.let { runCatching { BulbSchema.valueOf(it) }.getOrNull() } ?: BulbSchema.V2
        private set
    private var schemaKnown = device.schema != null

    private val outbox = Channel<Map<String, Any?>>(Channel.CONFLATED)
    private val refreshLock = Mutex()
    private var fadeJob: Job? = null

    init {
        scope.launch {
            tuya.updates.collect { applyDps(it) }
        }
        scope.launch {
            for (dps in outbox) {
                try {
                    tuya.set(dps, awaitAck = false)
                    markOnline()
                } catch (e: Exception) {
                    fail(e)
                }
                delay(SLIDER_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------ connection

    /** Reads the bulb's state. Returns true when online. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        if (_conn.value !is Conn.Online) _conn.value = Conn.Connecting
        return try {
            val dps = tuya.status()
            BulbSchema.detect(dps)?.let { detected ->
                if (!schemaKnown || detected != schema) {
                    schema = detected
                    schemaKnown = true
                    Store.upsertDevice(device.copy(schema = detected.name), makeActive = false)
                }
            }
            applyDps(dps)
            markOnline()
            true
        } catch (e: Exception) {
            fail(e)
            false
        }
    }

    fun idle() {
        tuya.disconnectQuietly()
        if (_conn.value is Conn.Online) _conn.value = Conn.Idle
    }

    fun close() = tuya.close()

    private fun applyDps(dps: Map<String, Any?>) {
        if (!schemaKnown) BulbSchema.detect(dps)?.let { schema = it; schemaKnown = true }
        _state.value = BulbCodec.parse(schema, dps, _state.value)
        val cd = _state.value.countdownSeconds
        if (dps.containsKey(if (schema == BulbSchema.V2) "26" else "7")) {
            sleepEndsAt.value = if (cd > 0) System.currentTimeMillis() + cd * 1000L else null
        }
        if (!_state.value.on) sleepEndsAt.value = null
        markOnline()
    }

    private fun markOnline() {
        if (_conn.value !is Conn.Online) _conn.value = Conn.Online
    }

    private fun fail(e: Throwable) {
        Diagnostics.log(tag, "error: ${e.javaClass.simpleName}: ${e.message}")
        _conn.value = when (e) {
            is TuyaException.BadKey -> Conn.Offline(e.message ?: "Wrong local key", badKey = true)
            is TuyaException -> Conn.Offline(e.message ?: "Can't reach the light")
            is IllegalArgumentException -> Conn.Offline(e.message ?: "Check the light's settings", badKey = true)
            else -> Conn.Offline("Can't reach the light (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Sends and waits for the bulb to confirm. Returns false on failure (state shows the error). */
    suspend fun send(dps: Map<String, Any?>): Boolean = try {
        tuya.set(dps, awaitAck = true)
        markOnline()
        true
    } catch (e: Exception) {
        fail(e)
        false
    }

    // ------------------------------------------------------------ user actions

    fun togglePower() = setPower(!_state.value.on)

    fun setPower(on: Boolean) {
        cancelFade()
        _state.value = _state.value.copy(on = on)
        scope.launch { send(BulbCodec.power(schema, on)) }
    }

    /** While dragging: updates instantly, sends at most every ~90ms. */
    fun previewWhite(brightness: Float, warmth: Float) {
        cancelFade()
        activePresetId.value = null
        _state.value = _state.value.copy(on = true, mode = BulbMode.WHITE, brightness = brightness, warmth = warmth)
        outbox.trySend(BulbCodec.white(schema, brightness, warmth))
    }

    fun previewColour(hue: Float, sat: Float, value: Float) {
        cancelFade()
        activePresetId.value = null
        _state.value = _state.value.copy(on = true, mode = BulbMode.COLOUR, hue = hue, saturation = sat, value = value)
        outbox.trySend(BulbCodec.colour(schema, hue, sat, value))
    }

    /** Brightness for whichever mode the bulb is in. */
    fun previewBrightness(frac: Float) {
        val s = _state.value
        if (s.mode == BulbMode.COLOUR) previewColour(s.hue, s.saturation, frac) else previewWhite(frac, s.warmth)
    }

    /** Called when a drag ends, so the final value is confirmed. */
    fun commit() {
        val s = _state.value
        scope.launch {
            delay(SLIDER_INTERVAL_MS + 20)
            send(if (s.mode == BulbMode.COLOUR) BulbCodec.colour(schema, s.hue, s.saturation, s.value)
                 else BulbCodec.white(schema, s.brightness, s.warmth))
        }
    }

    fun applyPreset(p: Preset) {
        cancelFade()
        activePresetId.value = p.id
        _state.value = optimistic(p)
        scope.launch { send(presetDps(p)) }
    }

    /** For schedules: applies a preset and waits for the bulb. */
    suspend fun applyPresetNow(p: Preset): Boolean {
        activePresetId.value = p.id
        _state.value = optimistic(p)
        return send(presetDps(p))
    }

    private fun optimistic(p: Preset): BulbState = when (p.kind) {
        PresetKind.WHITE -> _state.value.copy(on = true, mode = BulbMode.WHITE, brightness = p.brightness, warmth = p.warmth)
        PresetKind.COLOUR -> _state.value.copy(on = true, mode = BulbMode.COLOUR, hue = p.hue, saturation = p.saturation, value = p.brightness)
        PresetKind.BULB_SCENE, PresetKind.CLASSIC_SCENE -> _state.value.copy(on = true, mode = BulbMode.SCENE,
            hue = p.colours.firstOrNull()?.hue ?: _state.value.hue)
    }

    fun presetDps(p: Preset): Map<String, Any?> = when (p.kind) {
        PresetKind.WHITE -> BulbCodec.white(schema, p.brightness, p.warmth)
        PresetKind.COLOUR -> BulbCodec.colour(schema, p.hue, p.saturation, p.brightness)
        PresetKind.BULB_SCENE ->
            if (schema == BulbSchema.V2) BulbCodec.v2Scene(sceneFor(p)) else BulbCodec.v1Scene(classicFallback(p))
        PresetKind.CLASSIC_SCENE ->
            if (schema == BulbSchema.V1) BulbCodec.v1Scene(p.classicIndex)
            else BulbCodec.v2Scene(sceneFor(BuiltInPresets.bulbScenes[(p.classicIndex - 1).coerceIn(0, 3)]))
    }

    private fun classicFallback(p: Preset) = when {
        p.transition == "JUMP" -> 3
        p.colours.size >= 5 -> 4
        else -> 1
    }

    fun setCountdown(seconds: Int) {
        _state.value = _state.value.copy(countdownSeconds = seconds)
        sleepEndsAt.value = if (seconds > 0) System.currentTimeMillis() + seconds * 1000L else null
        scope.launch { send(BulbCodec.countdown(schema, seconds)) }
    }

    // ------------------------------------------------------------ fades

    fun cancelFade() {
        fadeJob?.cancel()
        fadeJob = null
    }

    /**
     * Gradually moves brightness from [from] to [to] (white light at [warmth])
     * over [durationMs]. Turns off at the end if [offAtEnd].
     */
    suspend fun fade(from: Float, to: Float, warmth: Float, durationMs: Long, offAtEnd: Boolean = false) {
        val steps = (durationMs / 1500L).coerceIn(1, 400).toInt()
        val stepDelay = durationMs / steps
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // ease-in-out so the change feels natural
            val eased = (t * t * (3 - 2 * t))
            val b = from + (to - from) * eased
            val w = if (to > from) warmth * eased else warmth
            _state.value = _state.value.copy(on = true, mode = BulbMode.WHITE, brightness = b, warmth = w)
            var ok = send(BulbCodec.white(schema, b, w))
            if (!ok) { delay(2000); ok = refresh() && send(BulbCodec.white(schema, b, w)) }
            if (i < steps) delay(stepDelay)
        }
        if (offAtEnd) {
            _state.value = _state.value.copy(on = false)
            send(BulbCodec.power(schema, false))
        }
    }

    fun startFade(from: Float, to: Float, warmth: Float, durationMs: Long, offAtEnd: Boolean) {
        cancelFade()
        fadeJob = scope.launch { fade(from, to, warmth, durationMs, offAtEnd) }
    }

    companion object {
        const val SLIDER_INTERVAL_MS = 90L

        fun sceneFor(p: Preset): BulbScene {
            val hold = (100 - p.speed * 95).roundToInt()
            val fade = (100 - p.speed * 90).roundToInt()
            val transition = runCatching { SceneUnit.Transition.valueOf(p.transition) }.getOrDefault(SceneUnit.Transition.GRADIENT)
            val units = p.colours.ifEmpty { listOf(app.halo.data.SceneColour(p.hue, p.saturation)) }.map {
                SceneUnit(hue = it.hue, saturation = it.saturation, brightness = p.brightness, white = it.white,
                    warmth = it.warmth, hold = hold, fade = fade, transition = transition)
            }
            // Scene numbers 0-7 are reserved for the bulb's own scenes on some firmware; use a custom slot.
            return BulbScene(number = 8 + (p.id.hashCode() and 0x7), units = units)
        }
    }
}
