package app.halo.tuya

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Protocol checks against vectors produced by tinytuya (tools/gen_vectors.py)
 * and, when HALO_FAKE_BULBS is set, against live simulated bulbs
 * (tools/fake_bulb.py). Kept free of JUnit so it can also run from a plain main().
 */
object ProtocolChecks {
    object V {
    const val c33 = "000055aa000000050000000700000057332e3300000000000000000000000023d681a3c46b76acf440f1db5dc5f25902f3dbe19d7df7e4ac8c3127efb3e568f9825e8a7c974132c83dccc1368b0f44ee3e082fdee411ae5bdd22255e5c1447bc7743970000aa55"
    const val q33 = "000055aa000000060000000a00000018fb3928935d0859ba555bbeef0a90accf2dfd551e0000aa55"
    const val c34 = "000055aa000000070000000d000000746a2f4275da435008b5b5d1f2e19bf95d9fabc506b104548101bafb6638e0886b1e4c9dd4fafe30e6932f5acf9b6f464670d4a55f261ed8f42c4bfa88010d703a02eb823ac763cc4fd528647916d1a66827f47774e2ec5f15a10a494a22f2346a97b34e6719fc352b9517772b47766e540000aa55"
    const val q34 = "000055aa000000080000001000000034b7dadf8fb2a094e0cb3c1f1caaf99adee1993730a52b54761d4ae3ef2fb409373463f604402e8ed73d8e6258a764554f0000aa55"
    const val c35 = "000066990000000000090000000d00000063000102030405060708090a0b15447ce622319dd4423374fb35ff5864c5ab8c5a0b5c009545758cd60f0e09c4435f4b34606e0087a3d5dc85a5a3a2a30b6242df0b4cee8de82465f9831b8a7e36f12c57730722b36a2880c72002d6c42e1f39a654e89b00009966"
    const val d33q = "000055aa000000010000000a0000004c0000000023d681a3c46b76acf440f1db5dc5f25988c0c6a08bac3eef2a668540bc81ab481ca59a731b658dab52f6974bf30f9834bf65b67ce95436bd1c3a437942ebfdcb817c0fad0000aa55"
    const val d33s = "000055aa00000002000000080000005b00000000332e3300000000000000000000000023d681a3c46b76acf440f1db5dc5f25988c0c6a08bac3eef2a668540bc81ab481ca59a731b658dab52f6974bf30f9834bf65b67ce95436bd1c3a437942ebfdcb5f4ee71a0000aa55"
    const val d34s = "000055aa000000030000000800000078000000006a2f4275da435008b5b5d1f2e19bf95d30938775f93647b840b0b6383048424096bba5435380685743497f47fb7ddb327edefaa0bffb4fbda19a927d367e0923fbffeb950169703d8efe65b7ff0e709b47d711cad1ee7cbcf266695c96946328ccf5a830fde02e92fbdb520781acf8e90000aa55"
    const val d35s = "000066990000000000040000000800000068000102030405060708090a0b266a49e6111fa8d4423374fb35ff581fe7dbfe4e5d57068c603394d9014d1f855b425e60202d128de8c7de99a5fdb7a51f2554861b1abcc4ff2e2ea69d09dc6678b26e023e4a6f6dcb4af0268b1a61127692a9d5303f3f2caa208bd900009966"
    const val d35q = "000066990000000000050000001000000059000102030405060708090a0b266a49e65913f9b1347a10d90fdd397d84f9d2171b4310d8132c94d1130e47920b1b1928726c0295a9d0dc99faebe1a3487a49931918aecefa2e2eebcc987b45f0e0e95265d598aa3e7b007c0d00009966"
    const val v1col = "ff00000000ffff"
    const val v2col = "00f003e803e8"
    const val udp33 = "000055aa00000000000000130000006c000000002a9f8305368a6a303dfe78a9cb5fc15258d69e7cc9972f94a58f4f5b5f1781f53441ade5916646c2833587ce5b5fb5ae139d0eac0281bbe955eb5ae44745f756a16c91fb0f91fd5dcef2d2983203c4a36047da4f427cbe5d5529be865d4aea2ec3f1b0b70000aa55"
    const val udp35 = "000066990000000000000000001300000074000102030405060708090a0bc73d7f42f5a2d9f3b82a32cee7c319d9e05513b8ab4fdd807e8cbf1f087a3c3159aa38d8b843fc87575756c4876a705603942c829df92d87d00e067f17637710dcc6c27efa22818f6ac9b489425d70f222e5632de961a238c61495fdb2c411e24f81ac62cbeaef3500009966"
    const val sign = "EB18E4F4F342FFEEC394946AA5D2A46296174ED758EE93338C5B3346667F7BEF"
    }

    private val KEY = "0123456789abcdef".toByteArray()
    private val SESSION = "fedcba9876543210".toByteArray()
    private val IV = ByteArray(12) { it.toByte() }
    private const val P34 = """{"protocol":5,"t":1700000000,"data":{"dps":{"20":true}}}"""
    private const val P33 = """{"devId":"abc","uid":"abc","t":"1700000000","dps":{"20":true}}"""
    private const val STATUS = """{"devId":"abc","dps":{"20":true,"22":500},"t":1700000000}"""

    private fun eq(name: String, want: Any?, got: Any?) {
        check(want == got) { "$name: expected <$want> but was <$got>" }
    }

    fun json() {
        val obj = Json.parseObject("""{"a":1,"b":[true,false,null],"c":"x\"y\u00e9","d":{"e":-2.5}}""")
        eq("int", 1L, obj["a"])
        eq("list", listOf(true, false, null), obj["b"])
        eq("str", "x\"yé", obj["c"])
        eq("nested", -2.5, (obj["d"] as Map<*, *>)["e"])
        eq("roundtrip", """{"a":1,"b":[true,false,null],"c":"x\"yé","d":{"e":-2.5}}""", Json.stringify(obj))
        eq("compact", """{"dps":{"20":true}}""", Json.stringify(mapOf("dps" to mapOf("20" to true))))
    }

    fun encodeMatchesTinytuya() {
        eq("3.3 control", V.c33, TuyaCodec.encode(TuyaVersion.V33, 5, TuyaCmd.CONTROL, P33.toByteArray(), KEY).toHex())
        eq("3.3 query", V.q33, TuyaCodec.encode(TuyaVersion.V33, 6, TuyaCmd.DP_QUERY, """{"gwId":"abc"}""".toByteArray(), KEY).toHex())
        eq("3.4 control", V.c34, TuyaCodec.encode(TuyaVersion.V34, 7, TuyaCmd.CONTROL_NEW, P34.toByteArray(), SESSION).toHex())
        eq("3.4 query", V.q34, TuyaCodec.encode(TuyaVersion.V34, 8, TuyaCmd.DP_QUERY_NEW, "{}".toByteArray(), SESSION).toHex())
        eq("3.5 control", V.c35, TuyaCodec.encode(TuyaVersion.V35, 9, TuyaCmd.CONTROL_NEW, P34.toByteArray(), SESSION, IV).toHex())
    }

    fun decodeTinytuyaFrames() {
        fun d(v: TuyaVersion, hex: String, key: ByteArray) = TuyaCodec.decode(v, hex.hexToBytes(), key)
        for ((name, frame) in listOf(
            "3.3 query reply" to d(TuyaVersion.V33, V.d33q, KEY),
            "3.3 status push" to d(TuyaVersion.V33, V.d33s, KEY),
            "3.4 status push" to d(TuyaVersion.V34, V.d34s, SESSION),
            "3.5 status push" to d(TuyaVersion.V35, V.d35s, SESSION),
            "3.5 query reply" to d(TuyaVersion.V35, V.d35q, SESSION),
        )) {
            eq("$name text", STATUS, frame.text)
            eq("$name retcode", 0, frame.retcode)
            eq("$name dps", mapOf("20" to true, "22" to 500L), TuyaDevice.parseDps(frame.text))
        }
        // Wrong key must be reported as a key problem, not garbage.
        val wrong = "aaaaaaaaaaaaaaaa".toByteArray()
        for ((v, hex) in listOf(TuyaVersion.V34 to V.d34s, TuyaVersion.V35 to V.d35s)) {
            val ok = runCatching { TuyaCodec.decode(v, hex.hexToBytes(), wrong) }.exceptionOrNull() is TuyaException.BadKey
            check(ok) { "$v wrong key not detected" }
        }
    }

    fun discoveryPackets() {
        val a = TuyaDiscovery.parse(V.udp33.hexToBytes(), "10.0.0.9")
        eq("udp33", DiscoveredDevice("abc", "192.168.1.50", TuyaVersion.V33, "pk"), a)
        val b = TuyaDiscovery.parse(V.udp35.hexToBytes(), "10.0.0.9")
        eq("udp35", DiscoveredDevice("abc", "192.168.1.50", TuyaVersion.V35, "pk"), b)
    }

    fun bulbCodec() {
        eq("v1 red", V.v1col, BulbCodec.colourV1(0f, 1f, 1f))
        eq("v2 blue", V.v2col, BulbCodec.colourV2(240f, 1f, 1f))
        val s = BulbCodec.parse(BulbSchema.V2, mapOf("20" to false, "21" to "colour", "22" to 1000L, "24" to "007803e801f4"))
        eq("on", false, s.on); eq("mode", BulbMode.COLOUR, s.mode); eq("bright", 1f, s.brightness)
        eq("hue", 120f, s.hue); eq("val", 0.5f, s.value)
        eq("schema v1", BulbSchema.V1, BulbSchema.detect(mapOf("1" to true, "2" to "white", "3" to 25)))
        eq("schema v2", BulbSchema.V2, BulbSchema.detect(mapOf("20" to true)))
        eq("white v2", mapOf("20" to true, "21" to "white", "22" to 505, "23" to 1000), BulbCodec.white(BulbSchema.V2, 0.5f, 1f))
        eq("scene", "01" + "3232" + "02" + "0000" + "03e8" + "03e8" + "0000" + "0000",
            BulbCodec.encodeScene(BulbScene(1, listOf(SceneUnit(hue = 0f, saturation = 1f, brightness = 1f, hold = 50, fade = 50)))))
    }

    fun cloudSignature() {
        eq("sign", V.sign, TuyaCloud.sign("abcd1234", "secretsecret", null, "1700000000000", "GET", "/v1.0/token?grant_type=1"))
    }

    /** HALO_FAKE_BULBS="3.3:7033,3.4:7034,3.5:7035,3.3:7100" (simulated bulbs started by the build). */
    fun liveFakeBulbs(spec: String? = System.getenv("HALO_FAKE_BULBS")) {
        if (spec.isNullOrBlank()) { println("  (no fake bulbs configured, skipping live checks)"); return }
        for (entry in spec.split(",")) {
            val (ver, port) = entry.split(":")
            runBlocking {
                val dev = TuyaDevice(TuyaDeviceConfig("bf0123456789abcdefgh01", "127.0.0.1", "0123456789abcdef",
                    TuyaVersion.from(ver), port.toInt())) { println("    [$ver:$port] $it") }
                try {
                    val st = dev.status()
                    check(BulbSchema.detect(st) != null) { "$entry: no bulb data points in $st" }
                    val schema = BulbSchema.detect(st)!!
                    val powerKey = if (schema == BulbSchema.V2) "20" else "1"
                    dev.set(BulbCodec.power(schema, false))
                    eq("$entry power off", false, dev.status()[powerKey])
                    // The bulb's own push after a change reaches the updates flow.
                    val pushed = async { withTimeout(3000) { dev.updates.first { it[powerKey] == true } } }
                    kotlinx.coroutines.delay(50)
                    dev.set(BulbCodec.colour(schema, 120f, 1f, 1f))
                    eq("$entry push", true, pushed.await()[powerKey])
                    eq("$entry colour", BulbMode.COLOUR, BulbCodec.parse(schema, dev.status()).mode)
                    // Rapid fire (slider) writes should all land.
                    repeat(20) { dev.set(BulbCodec.white(schema, it / 19f, 0.3f), awaitAck = false) }
                    kotlinx.coroutines.delay(300)
                    eq("$entry final brightness", 1f, BulbCodec.parse(schema, dev.status()).brightness)
                    println("  live $entry ok")
                } finally { dev.close() }
            }
        }
        // A wrong key must surface as BadKey (or a clear timeout for 3.3, which can't tell).
        val (ver, port) = spec.split(",").first { it.startsWith("3.4") || it.startsWith("3.5") }.split(":")
        runBlocking {
            val dev = TuyaDevice(TuyaDeviceConfig("bf0123456789abcdefgh01", "127.0.0.1", "aaaaaaaaaaaaaaaa", TuyaVersion.from(ver), port.toInt()))
            val e = runCatching { dev.status() }.exceptionOrNull()
            check(e is TuyaException.BadKey) { "wrong key gave $e" }
            dev.close()
        }
    }

    /** HALO_FAKE_BROADCAST=1: a fake bulb is broadcasting to 127.0.0.1:6667. */
    fun liveDiscovery() {
        if (System.getenv("HALO_FAKE_BROADCAST") != "1") return
        val found = runBlocking { withTimeout(8000) { TuyaDiscovery.scan(intArrayOf(6667), "127.0.0.1").first() } }
        eq("discovered id", "bf0123456789abcdefgh01", found.id)
        println("  discovery ok: $found")
    }

    val all: List<Pair<String, () -> Unit>> = listOf(
        "json" to ::json, "encode" to ::encodeMatchesTinytuya, "decode" to ::decodeTinytuyaFrames,
        "discovery packets" to ::discoveryPackets, "bulb codec" to ::bulbCodec, "cloud signature" to ::cloudSignature,
        "live bulbs" to { liveFakeBulbs() }, "live discovery" to ::liveDiscovery,
    )
}
