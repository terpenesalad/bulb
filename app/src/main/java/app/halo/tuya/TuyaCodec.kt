package app.halo.tuya

import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer

enum class TuyaVersion(val label: String) {
    V33("3.3"), V34("3.4"), V35("3.5");

    val header: ByteArray get() = label.toByteArray() + ByteArray(12)

    companion object {
        fun from(label: String?): TuyaVersion = when (label?.trim()) {
            "3.4" -> V34
            "3.5" -> V35
            else -> V33
        }
    }
}

object TuyaCmd {
    const val SESS_KEY_NEG_START = 3
    const val SESS_KEY_NEG_RESP = 4
    const val SESS_KEY_NEG_FINISH = 5
    const val CONTROL = 7
    const val STATUS = 8
    const val HEART_BEAT = 9
    const val DP_QUERY = 10
    const val CONTROL_NEW = 13
    const val DP_QUERY_NEW = 16
    const val UPDATEDPS = 18
    const val UDP_NEW = 19
    const val REQ_DEVINFO = 0x25
    const val LAN_EXT_STREAM = 0x40

    /** Commands whose payload never carries the "3.x" + 12 zero byte header. */
    val NO_HEADER = setOf(DP_QUERY, DP_QUERY_NEW, UPDATEDPS, HEART_BEAT,
        SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH, LAN_EXT_STREAM)
}

sealed class TuyaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadKey(message: String = "The bulb rejected the local key") : TuyaException(message)
    class Timeout(message: String = "The bulb didn't answer in time") : TuyaException(message)
    class Unreachable(message: String, cause: Throwable? = null) : TuyaException(message, cause)
    class Protocol(message: String) : TuyaException(message)
}

/** A decoded frame. [payload] is plaintext with the return code and version header removed. */
class TuyaFrame(val seq: Int, val cmd: Int, val retcode: Int?, val payload: ByteArray) {
    val text: String get() = String(payload, Charsets.UTF_8)
    override fun toString() = "TuyaFrame(seq=$seq, cmd=$cmd, ret=$retcode, payload=${text.take(200)})"
}

object TuyaCodec {
    const val PREFIX_55AA = 0x000055AA
    const val SUFFIX_55AA = 0x0000AA55
    const val PREFIX_6699 = 0x00006699
    const val SUFFIX_6699 = 0x00009966
    private const val MAX_LEN = 64 * 1024

    val UDP_KEY: ByteArray = TuyaCrypto.md5("yGAdlopoPVldABfn".toByteArray())

    /**
     * Builds a client -> device message: adds the version header where needed,
     * encrypts with [key] (local key or session key) and frames it.
     */
    fun encode(version: TuyaVersion, seq: Int, cmd: Int, payload: ByteArray, key: ByteArray, iv: ByteArray? = null): ByteArray {
        val wantsHeader = cmd !in TuyaCmd.NO_HEADER
        return when (version) {
            TuyaVersion.V33 -> {
                var body = TuyaCrypto.ecbEncrypt(key, payload)
                if (wantsHeader) body = version.header + body
                pack55AA(seq, cmd, body, hmacKey = null)
            }
            TuyaVersion.V34 -> {
                val raw = if (wantsHeader) version.header + payload else payload
                pack55AA(seq, cmd, TuyaCrypto.ecbEncrypt(key, raw), hmacKey = key)
            }
            TuyaVersion.V35 -> {
                val raw = if (wantsHeader) version.header + payload else payload
                pack6699(seq, cmd, raw, key, iv ?: TuyaCrypto.randomBytes(12))
            }
        }
    }

    fun pack55AA(seq: Int, cmd: Int, body: ByteArray, hmacKey: ByteArray?): ByteArray {
        val endLen = if (hmacKey != null) 36 else 8
        val buf = ByteBuffer.allocate(16 + body.size + endLen)
        buf.putInt(PREFIX_55AA).putInt(seq).putInt(cmd).putInt(body.size + endLen).put(body)
        val signed = buf.array().copyOf(16 + body.size)
        if (hmacKey != null) buf.put(TuyaCrypto.hmacSha256(hmacKey, signed)) else buf.putInt(TuyaCrypto.crc32(signed))
        buf.putInt(SUFFIX_55AA)
        return buf.array()
    }

    fun pack6699(seq: Int, cmd: Int, raw: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == 12)
        val header = ByteBuffer.allocate(18)
            .putInt(PREFIX_6699).putShort(0).putInt(seq).putInt(cmd).putInt(12 + raw.size + 16)
            .array()
        val sealed = TuyaCrypto.gcmEncrypt(key, iv, header.copyOfRange(4, 18), raw)
        return ByteBuffer.allocate(18 + 12 + sealed.size + 4)
            .put(header).put(iv).put(sealed).putInt(SUFFIX_6699).array()
    }

    /** Reads exactly one raw frame from the stream (blocking). */
    fun readFrame(input: DataInputStream): ByteArray {
        // Resynchronise on a prefix if the stream is ever out of step.
        var prefix = input.readInt()
        var skipped = 0
        while (prefix != PREFIX_55AA && prefix != PREFIX_6699) {
            prefix = (prefix shl 8) or input.readUnsignedByte()
            if (++skipped > MAX_LEN) throw TuyaException.Protocol("Lost sync with the bulb")
        }
        return if (prefix == PREFIX_55AA) {
            val head = ByteArray(12).also { input.readFully(it) }
            val len = ByteBuffer.wrap(head, 8, 4).int
            if (len < 8 || len > MAX_LEN) throw TuyaException.Protocol("Bad frame length $len")
            val rest = ByteArray(len).also { input.readFully(it) }
            ByteBuffer.allocate(16 + len).putInt(prefix).put(head).put(rest).array()
        } else {
            val head = ByteArray(14).also { input.readFully(it) }
            val len = ByteBuffer.wrap(head, 10, 4).int
            if (len < 28 || len > MAX_LEN) throw TuyaException.Protocol("Bad frame length $len")
            val rest = ByteArray(len + 4).also { input.readFully(it) }
            ByteBuffer.allocate(18 + len + 4).putInt(prefix).put(head).put(rest).array()
        }
    }

    /**
     * Decodes a device -> client frame.
     *
     * @param retcode true: frame carries a return code (TCP replies from the bulb),
     *                null: guess (UDP broadcasts)
     */
    fun decode(version: TuyaVersion, frame: ByteArray, key: ByteArray, retcode: Boolean? = true): TuyaFrame {
        val bb = ByteBuffer.wrap(frame)
        return when (bb.int) {
            PREFIX_55AA -> decode55AA(version, frame, key, retcode)
            PREFIX_6699 -> decode6699(version, frame, key, retcode)
            else -> throw TuyaException.Protocol("Unknown frame prefix")
        }
    }

    private fun decode55AA(version: TuyaVersion, frame: ByteArray, key: ByteArray, retcodeMode: Boolean?): TuyaFrame {
        val bb = ByteBuffer.wrap(frame)
        bb.int
        val seq = bb.int
        val cmd = bb.int
        val len = bb.int
        val useHmac = version != TuyaVersion.V33
        val endLen = if (useHmac) 36 else 8
        if (len < endLen || 16 + len > frame.size) throw TuyaException.Protocol("Truncated frame")
        val signedEnd = 16 + len - endLen
        if (useHmac) {
            val want = TuyaCrypto.hmacSha256(key, frame.copyOfRange(0, signedEnd))
            val got = frame.copyOfRange(signedEnd, signedEnd + 32)
            if (!want.contentEquals(got)) throw TuyaException.BadKey("Message signature didn't match (wrong local key or protocol version?)")
        } else {
            val want = TuyaCrypto.crc32(frame, signedEnd)
            val got = ByteBuffer.wrap(frame, signedEnd, 4).int
            if (want != got) throw TuyaException.Protocol("CRC mismatch")
        }
        var payload = frame.copyOfRange(16, signedEnd)

        // Return code: device replies prefix their payload with 4 bytes. Encrypted bodies are a
        // multiple of 16, optionally preceded by a 15 byte "3.3" header, so the length tells us.
        var ret: Int? = null
        val m = payload.size % 16
        val hasRet = when (retcodeMode) {
            true, null -> payload.size >= 4 && (m == 4 || m == 3) && payload[0].toInt() == 0 && payload[1].toInt() == 0
            false -> false
        }
        if (hasRet) {
            ret = ByteBuffer.wrap(payload, 0, 4).int
            payload = payload.copyOfRange(4, payload.size)
        }
        if (payload.isEmpty()) return TuyaFrame(seq, cmd, ret, payload)

        // Some devices (and all UDP broadcasts from 3.1 firmware) send plain JSON.
        if (payload[0] == '{'.code.toByte()) return TuyaFrame(seq, cmd, ret, payload)

        payload = when (version) {
            TuyaVersion.V33 -> {
                if (payload.size % 16 == 15) payload = payload.copyOfRange(15, payload.size)
                if (payload.size % 16 != 0) return TuyaFrame(seq, cmd, ret, payload) // plain text error
                TuyaCrypto.ecbDecrypt(key, payload)
            }
            else -> stripVersionHeader(TuyaCrypto.ecbDecrypt(key, payload))
        }
        return TuyaFrame(seq, cmd, ret, payload)
    }

    private fun decode6699(version: TuyaVersion, frame: ByteArray, key: ByteArray, retcodeMode: Boolean?): TuyaFrame {
        val bb = ByteBuffer.wrap(frame)
        bb.int; bb.short
        val seq = bb.int
        val cmd = bb.int
        val len = bb.int
        if (18 + len + 4 > frame.size || len < 28) throw TuyaException.Protocol("Truncated frame")
        val aad = frame.copyOfRange(4, 18)
        val iv = frame.copyOfRange(18, 30)
        val sealed = frame.copyOfRange(30, 18 + len)
        var plain = try {
            TuyaCrypto.gcmDecrypt(key, iv, aad, sealed)
        } catch (e: Exception) {
            throw TuyaException.BadKey("Couldn't decrypt the bulb's reply (wrong local key?)")
        }
        var ret: Int? = null
        val strip = when (retcodeMode) {
            true -> plain.size >= 4
            null -> plain.size > 4 && plain[0] != '{'.code.toByte() && plain[4] == '{'.code.toByte()
            false -> false
        }
        if (strip) {
            ret = ByteBuffer.wrap(plain, 0, 4).int
            plain = plain.copyOfRange(4, plain.size)
        }
        return TuyaFrame(seq, cmd, ret, stripVersionHeader(plain))
    }

    private fun stripVersionHeader(p: ByteArray): ByteArray =
        if (p.size >= 15 && p[0] == '3'.code.toByte() && p[1] == '.'.code.toByte() && p[2] in '0'.code.toByte()..'9'.code.toByte())
            p.copyOfRange(15, p.size) else p

    /** Decodes a UDP discovery broadcast into its JSON text, or null. */
    fun decodeBroadcast(data: ByteArray): String? = runCatching {
        if (data.isNotEmpty() && data[0] == '{'.code.toByte()) return String(data).trimEnd('\u0000')
        val prefix = ByteBuffer.wrap(data).int
        val text = when (prefix) {
            PREFIX_55AA -> decode55AA(TuyaVersion.V33, data, UDP_KEY, null).text
            PREFIX_6699 -> decode6699(TuyaVersion.V35, data, UDP_KEY, null).text
            else -> String(TuyaCrypto.ecbDecrypt(UDP_KEY, data))
        }
        text.trimEnd('\u0000')
    }.getOrNull()

    fun readFully(input: DataInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        try { input.readFully(b) } catch (e: EOFException) { throw TuyaException.Unreachable("Connection closed by the bulb", e) }
        return b
    }
}
