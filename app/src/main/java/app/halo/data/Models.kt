package app.halo.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedDevice(
    val id: String,
    val name: String = "Bedroom light",
    val host: String,
    val localKey: String,
    val version: String = "3.3",
    /** "V1" or "V2" once detected */
    val schema: String? = null,
    val productName: String? = null,
)

@Serializable
enum class PresetKind { WHITE, COLOUR, BULB_SCENE, CLASSIC_SCENE }

@Serializable
data class Preset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: PresetKind,
    val brightness: Float = 1f,
    val warmth: Float = 0.5f,
    val hue: Float = 0f,
    val saturation: Float = 1f,
    /** For BULB_SCENE: hue/sat pairs the bulb cycles through */
    val colours: List<SceneColour> = emptyList(),
    /** 0..1 */
    val speed: Float = 0.5f,
    val transition: String = "GRADIENT",
    /** For CLASSIC_SCENE (older bulbs): built-in scene 1..4 */
    val classicIndex: Int = 1,
    val builtIn: Boolean = false,
    val emoji: String? = null,
)

@Serializable
data class SceneColour(val hue: Float, val saturation: Float = 1f, val white: Boolean = false, val warmth: Float = 0f)

@Serializable
enum class TriggerType { CLOCK, SUNRISE, SUNSET }

@Serializable
enum class ScheduleAction { TURN_ON, TURN_OFF, PRESET, WAKE_UP, WIND_DOWN }

@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val label: String = "",
    val trigger: TriggerType = TriggerType.CLOCK,
    val hour: Int = 7,
    val minute: Int = 0,
    /** minutes relative to sunrise/sunset */
    val offsetMinutes: Int = 0,
    /** ISO days: 1 = Monday .. 7 = Sunday. Empty = once. */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val action: ScheduleAction = ScheduleAction.TURN_ON,
    val presetId: String? = null,
    /** target brightness for TURN_ON / WAKE_UP (0..1) */
    val brightness: Float = 1f,
    val warmth: Float = 0.3f,
    val fadeMinutes: Int = 20,
    val deviceId: String? = null,
)

@Serializable
enum class ThemeStyle { AMOLED, MIDNIGHT, GRAPHITE }

@Serializable
data class AppSettings(
    val theme: ThemeStyle = ThemeStyle.AMOLED,
    /** ARGB */
    val accent: Long = 0xFFFFB547,
    /** Tint the whole app with the bulb's current colour */
    val adaptiveAccent: Boolean = true,
    val glow: Float = 0.8f,
    val haptics: Boolean = true,
    val animations: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationLabel: String? = null,
    /** saved colour swatches, as hue/sat */
    val swatches: List<SceneColour> = listOf(
        SceneColour(18f, 0.9f), SceneColour(330f, 0.75f), SceneColour(265f, 0.8f),
        SceneColour(200f, 0.85f), SceneColour(150f, 0.7f), SceneColour(45f, 0.95f),
    ),
    val showSeconds: Boolean = false,
    val use24h: Boolean = false,
    val tuyaAccessId: String = "",
    val tuyaRegion: String? = null,
)

@Serializable
data class AppData(
    val devices: List<SavedDevice> = emptyList(),
    val activeDeviceId: String? = null,
    val presets: List<Preset> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    val activeDevice: SavedDevice? get() = devices.firstOrNull { it.id == activeDeviceId } ?: devices.firstOrNull()
}
