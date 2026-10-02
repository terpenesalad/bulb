package app.halo.data

object BuiltInPresets {
    val all: List<Preset> = listOf(
        Preset(id = "b-read", name = "Reading", kind = PresetKind.WHITE, brightness = 1f, warmth = 0.62f, builtIn = true, emoji = "📖"),
        Preset(id = "b-focus", name = "Focus", kind = PresetKind.WHITE, brightness = 1f, warmth = 1f, builtIn = true, emoji = "⚡"),
        Preset(id = "b-relax", name = "Relax", kind = PresetKind.WHITE, brightness = 0.55f, warmth = 0.12f, builtIn = true, emoji = "🛋"),
        Preset(id = "b-candle", name = "Candlelight", kind = PresetKind.WHITE, brightness = 0.22f, warmth = 0f, builtIn = true, emoji = "🕯"),
        Preset(id = "b-night", name = "Night light", kind = PresetKind.COLOUR, hue = 22f, saturation = 1f, brightness = 0.04f, builtIn = true, emoji = "🌙"),
        Preset(id = "b-movie", name = "Movie", kind = PresetKind.COLOUR, hue = 238f, saturation = 0.85f, brightness = 0.18f, builtIn = true, emoji = "🎬"),
        Preset(id = "b-sunset", name = "Sunset", kind = PresetKind.COLOUR, hue = 16f, saturation = 0.92f, brightness = 0.7f, builtIn = true, emoji = "🌅"),
        Preset(id = "b-neon", name = "Neon", kind = PresetKind.COLOUR, hue = 312f, saturation = 1f, brightness = 0.9f, builtIn = true, emoji = "💜"),
        Preset(id = "b-forest", name = "Forest", kind = PresetKind.COLOUR, hue = 140f, saturation = 0.7f, brightness = 0.6f, builtIn = true, emoji = "🌲"),
        Preset(id = "b-ocean", name = "Deep sea", kind = PresetKind.COLOUR, hue = 196f, saturation = 0.95f, brightness = 0.65f, builtIn = true, emoji = "🌊"),
    )

    /** Animated scenes that run on the bulb itself (newer bulbs), no phone needed. */
    val bulbScenes: List<Preset> = listOf(
        Preset(id = "s-aurora", name = "Aurora", kind = PresetKind.BULB_SCENE, brightness = 0.8f, speed = 0.25f, builtIn = true,
            colours = listOf(SceneColour(150f, 0.8f), SceneColour(180f, 0.9f), SceneColour(275f, 0.7f), SceneColour(205f, 0.9f))),
        Preset(id = "s-ember", name = "Ember", kind = PresetKind.BULB_SCENE, brightness = 0.6f, speed = 0.4f, builtIn = true,
            colours = listOf(SceneColour(10f, 1f), SceneColour(26f, 1f), SceneColour(4f, 0.95f), SceneColour(35f, 0.9f))),
        Preset(id = "s-lagoon", name = "Lagoon", kind = PresetKind.BULB_SCENE, brightness = 0.75f, speed = 0.2f, builtIn = true,
            colours = listOf(SceneColour(185f, 0.9f), SceneColour(215f, 0.85f), SceneColour(170f, 0.7f))),
        Preset(id = "s-vapor", name = "Vaporwave", kind = PresetKind.BULB_SCENE, brightness = 0.85f, speed = 0.45f, builtIn = true,
            colours = listOf(SceneColour(300f, 0.9f), SceneColour(190f, 0.8f), SceneColour(260f, 0.9f))),
        Preset(id = "s-prism", name = "Prism", kind = PresetKind.BULB_SCENE, brightness = 1f, speed = 0.6f, builtIn = true,
            colours = listOf(SceneColour(0f), SceneColour(60f), SceneColour(120f), SceneColour(180f), SceneColour(240f), SceneColour(300f))),
        Preset(id = "s-party", name = "Party", kind = PresetKind.BULB_SCENE, brightness = 1f, speed = 0.9f, transition = "JUMP", builtIn = true,
            colours = listOf(SceneColour(320f), SceneColour(50f), SceneColour(190f), SceneColour(270f), SceneColour(130f))),
    )

    /** Older bulbs only have four fixed scenes. */
    val classicScenes: List<Preset> = listOf(
        Preset(id = "c-1", name = "Nature", kind = PresetKind.CLASSIC_SCENE, classicIndex = 1, builtIn = true),
        Preset(id = "c-2", name = "Pulse", kind = PresetKind.CLASSIC_SCENE, classicIndex = 2, builtIn = true),
        Preset(id = "c-3", name = "Rave", kind = PresetKind.CLASSIC_SCENE, classicIndex = 3, builtIn = true),
        Preset(id = "c-4", name = "Rainbow", kind = PresetKind.CLASSIC_SCENE, classicIndex = 4, builtIn = true),
    )

    fun find(id: String?, user: List<Preset>): Preset? =
        if (id == null) null else (user + all + bulbScenes + classicScenes).firstOrNull { it.id == id }
}
