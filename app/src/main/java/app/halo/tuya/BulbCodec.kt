package app.halo.tuya

import kotlin.math.roundToInt

/**
 * Tuya RGB bulbs come in two data point layouts:
 *  - V2 (most bulbs since ~2019): 20 power, 21 mode, 22 brightness 10-1000,
 *    23 colour temp 0-1000, 24 colour "HHHHSSSSVVVV", 25 scene, 26 countdown
 *  - V1 (older firmware): 1 power, 2 mode, 3 brightness 25-255,
 *    4 colour temp 0-255, 5 colour "rrggbbHHHHSSVV", 6 scene, 7 countdown
 */
enum class BulbSchema { V1, V2;
    companion object {
        fun detect(dps: Map<String, Any?>): BulbSchema? = when {
            dps.containsKey("20") || dps.containsKey("24") -> V2
            dps.containsKey("1") && (dps.containsKey("2") || dps.containsKey("5")) -> V1
            else -> null
        }
    }
}

enum class BulbMode(val tuya: String) {
    WHITE("white"), COLOUR("colour"), SCENE("scene"), MUSIC("music");

    companion object {
        fun from(s: String?) = when {
            s == null -> WHITE
            s.startsWith("scene") -> SCENE
            s == "colour" || s == "color" -> COLOUR
            s == "music" -> MUSIC
            else -> WHITE
        }
    }
}

/** Normalised bulb state. All fractions are 0..1, hue is 0..360. */
data class BulbState(
    val on: Boolean = true,
    val mode: BulbMode = BulbMode.WHITE,
    val brightness: Float = 0.8f,
    /** 0 = warmest, 1 = coolest */
    val warmth: Float = 0.5f,
    val hue: Float = 0f,
    val saturation: Float = 1f,
    /** brightness in colour mode */
    val value: Float = 1f,
    val countdownSeconds: Int = 0,
)

object BulbCodec {

    fun parse(schema: BulbSchema, dps: Map<String, Any?>, previous: BulbState = BulbState()): BulbState {
        var s = previous
        fun num(k: String) = (dps[k] as? Number)?.toFloat()
        fun str(k: String) = dps[k] as? String
        if (schema == BulbSchema.V2) {
            (dps["20"] as? Boolean)?.let { s = s.copy(on = it) }
            str("21")?.let { s = s.copy(mode = BulbMode.from(it)) }
            num("22")?.let { s = s.copy(brightness = ((it - 10f) / 990f).coerceIn(0f, 1f)) }
            num("23")?.let { s = s.copy(warmth = (it / 1000f).coerceIn(0f, 1f)) }
            str("24")?.let { c -> parseColourV2(c)?.let { (h, sa, v) -> s = s.copy(hue = h, saturation = sa, value = v) } }
            num("26")?.let { s = s.copy(countdownSeconds = it.toInt()) }
        } else {
            (dps["1"] as? Boolean)?.let { s = s.copy(on = it) }
            str("2")?.let { s = s.copy(mode = BulbMode.from(it)) }
            num("3")?.let { s = s.copy(brightness = ((it - 25f) / 230f).coerceIn(0f, 1f)) }
            num("4")?.let { s = s.copy(warmth = (it / 255f).coerceIn(0f, 1f)) }
            str("5")?.let { c -> parseColourV1(c)?.let { (h, sa, v) -> s = s.copy(hue = h, saturation = sa, value = v) } }
            num("7")?.let { s = s.copy(countdownSeconds = it.toInt()) }
        }
        return s
    }

    fun power(schema: BulbSchema, on: Boolean): Map<String, Any?> =
        mapOf((if (schema == BulbSchema.V2) "20" else "1") to on)

    fun white(schema: BulbSchema, brightness: Float, warmth: Float, includeMode: Boolean = true): Map<String, Any?> {
        val b = brightness.coerceIn(0f, 1f)
        val w = warmth.coerceIn(0f, 1f)
        val m = LinkedHashMap<String, Any?>()
        if (schema == BulbSchema.V2) {
            m["20"] = true
            if (includeMode) m["21"] = "white"
            m["22"] = (10 + b * 990).roundToInt()
            m["23"] = (w * 1000).roundToInt()
        } else {
            m["1"] = true
            if (includeMode) m["2"] = "white"
            m["3"] = (25 + b * 230).roundToInt()
            m["4"] = (w * 255).roundToInt()
        }
        return m
    }

    fun colour(schema: BulbSchema, hue: Float, sat: Float, value: Float, includeMode: Boolean = true): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (schema == BulbSchema.V2) {
            m["20"] = true
            if (includeMode) m["21"] = "colour"
            m["24"] = colourV2(hue, sat, value)
        } else {
            m["1"] = true
            if (includeMode) m["2"] = "colour"
            m["5"] = colourV1(hue, sat, value)
        }
        return m
    }

    fun countdown(schema: BulbSchema, seconds: Int): Map<String, Any?> =
        mapOf((if (schema == BulbSchema.V2) "26" else "7") to seconds.coerceIn(0, 86400))

    /** Built-in V1 firmware scenes, 1..4. */
    fun v1Scene(index: Int): Map<String, Any?> = mapOf("1" to true, "2" to "scene_${index.coerceIn(1, 4)}")

    /** A scene the V2 bulb runs by itself (keeps going with the phone off). */
    fun v2Scene(scene: BulbScene): Map<String, Any?> = linkedMapOf("20" to true, "21" to "scene", "25" to encodeScene(scene))

    // ------------------------------------------------------------- colours

    fun colourV2(hue: Float, sat: Float, value: Float): String {
        val h = (((hue % 360f) + 360f) % 360f).roundToInt().coerceIn(0, 360)
        val s = (sat.coerceIn(0f, 1f) * 1000).roundToInt()
        val v = (value.coerceIn(0f, 1f) * 1000).roundToInt().coerceAtLeast(10)
        return "%04x%04x%04x".format(h, s, v)
    }

    fun parseColourV2(c: String): Triple<Float, Float, Float>? {
        if (c.length != 12) return null
        return runCatching {
            Triple(c.substring(0, 4).toInt(16).toFloat(),
                c.substring(4, 8).toInt(16) / 1000f,
                (c.substring(8, 12).toInt(16) / 1000f).coerceIn(0f, 1f))
        }.getOrNull()
    }

    fun colourV1(hue: Float, sat: Float, value: Float): String {
        val (r, g, b) = hsvToRgb(hue, sat, value)
        val h = (((hue % 360f) + 360f) % 360f).roundToInt()
        return "%02x%02x%02x%04x%02x%02x".format(r, g, b, h, (sat * 255).roundToInt(), (value * 255).roundToInt())
    }

    fun parseColourV1(c: String): Triple<Float, Float, Float>? {
        if (c.length != 14) return null
        return runCatching {
            Triple(c.substring(6, 10).toInt(16).toFloat(), c.substring(10, 12).toInt(16) / 255f, c.substring(12, 14).toInt(16) / 255f)
        }.getOrNull()
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        return Triple(((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
    }

    // ------------------------------------------------------------- scenes

    /**
     * V2 scene_data: scene number (1 byte) then up to 8 units of
     * switch interval, transition time, mode (0 static, 1 jump, 2 gradient),
     * hue(2), sat(2), value(2), brightness(2), temp(2).
     */
    fun encodeScene(scene: BulbScene): String = buildString {
        append("%02x".format(scene.number and 0xff))
        for (u in scene.units.take(8)) {
            append("%02x%02x%02x".format(u.hold.coerceIn(0, 100), u.fade.coerceIn(0, 100), u.transition.code))
            if (u.white) {
                append("%04x%04x%04x".format(0, 0, 0))
                append("%04x%04x".format((10 + u.brightness.coerceIn(0f, 1f) * 990).roundToInt(), (u.warmth.coerceIn(0f, 1f) * 1000).roundToInt()))
            } else {
                append("%04x%04x%04x".format(u.hue.roundToInt().coerceIn(0, 360),
                    (u.saturation.coerceIn(0f, 1f) * 1000).roundToInt(),
                    (10 + u.brightness.coerceIn(0f, 1f) * 990).roundToInt()))
                append("%04x%04x".format(0, 0))
            }
        }
    }
}

data class BulbScene(val number: Int, val units: List<SceneUnit>)

data class SceneUnit(
    val hue: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val white: Boolean = false,
    val warmth: Float = 0f,
    /** 0..100, how long each colour holds */
    val hold: Int = 50,
    /** 0..100, how long the change takes */
    val fade: Int = 50,
    val transition: Transition = Transition.GRADIENT,
) {
    enum class Transition(val code: Int) { STATIC(0), JUMP(1), GRADIENT(2) }
}
