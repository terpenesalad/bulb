package app.halo.tuya

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class TuyaDeviceConfig(
    val id: String,
    val host: String,
    val localKey: String,
    val version: TuyaVersion,
    val port: Int = 6668,
)

/**
 * One persistent LAN connection to a Tuya device.
 *
 * Calls are safe from any coroutine; they reconnect automatically.
 */
class TuyaDevice(
    val config: TuyaDeviceConfig,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val requestLock = Mutex()
    private val inbox = Channel<TuyaFrame>(capacity = 64)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var input: DataInputStream? = null
    private var key: ByteArray = config.localKey.toByteArray(Charsets.ISO_8859_1)
    private var seq = 1
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var lastRx = 0L
    @Volatile private var device22 = false

    private val _updates = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 32)
    /** Every data point change the bulb reports (from our commands, the wall switch, other apps). */
    val updates: SharedFlow<Map<String, Any?>> = _updates

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    init {
        require(config.localKey.length == 16) { "The local key must be 16 characters (got ${config.localKey.length})" }
    }

    // ---------------------------------------------------------------- public API

    /** Reads every data point. */
    suspend fun status(): Map<String, Any?> = withContext(Dispatchers.IO) { statusIo() }

    private suspend fun statusIo(): Map<String, Any?> = withRetry {
        requestLock.withLock {
            var reply = queryOnce()
            if (reply == null && !device22) {
                // Some firmware ("device22") only answers the newer style query.
                device22 = true
                log("switching to device22 style queries")
                reply = queryOnce()
            }
            reply ?: throw TuyaException.Timeout("The bulb didn't send its status. Check the local key and protocol version.")
        }
    }

    /**
     * Writes data points. With [awaitAck] false the call returns as soon as the
     * bytes are sent (used for sliders and effects so they feel instant).
     */
    suspend fun set(dps: Map<String, Any?>, awaitAck: Boolean = true) = withContext(Dispatchers.IO) {
        withRetry {
            requestLock.withLock {
                drainInbox()
                val cmd = sendControl(dps)
                if (awaitAck) {
                    val ack = awaitFrame(3000) { it.cmd == cmd || it.cmd == TuyaCmd.STATUS }
                    if (ack == null) log("no ack for control (continuing)")
                }
            }
        }
    }

    /** Opens the connection now (otherwise it opens on first use). */
    suspend fun connect() {
        ensureConnected()
    }

    /** Drops the socket but keeps this object usable; the next call reconnects. */
    fun disconnectQuietly() {
        disconnect("idle")
    }

    fun close() {
        disconnect("closed")
        scope.cancel()
    }

    // ---------------------------------------------------------------- requests

    private suspend fun queryOnce(): Map<String, Any?>? {
        drainInbox()
        val v = config.version
        val (cmd, body) = when {
            v != TuyaVersion.V33 -> TuyaCmd.DP_QUERY_NEW to "{}"
            device22 -> TuyaCmd.CONTROL_NEW to Json.stringify(linkedMapOf(
                "devId" to config.id, "uid" to config.id, "t" to now().toString(),
                "dps" to DEVICE22_DPS.associateWith { null },
            ))
            else -> TuyaCmd.DP_QUERY to Json.stringify(linkedMapOf(
                "gwId" to config.id, "devId" to config.id, "uid" to config.id, "t" to now().toString(),
            ))
        }
        write(cmd, body.toByteArray())
        var unvalid = false
        val reply = awaitFrame(4000) { f ->
            if (f.text.contains("data unvalid")) { unvalid = true; true }
            else {
                val dps = parseDps(f.text)
                // A query reply; or a full status push (some firmware answers that way).
                // Partial pushes from earlier commands are ignored.
                dps != null && (f.cmd == cmd || f.cmd == TuyaCmd.DP_QUERY || f.cmd == TuyaCmd.DP_QUERY_NEW ||
                    (f.cmd == TuyaCmd.STATUS && dps.size >= 4))
            }
        }
        if (unvalid || reply == null) return null
        return parseDps(reply.text)
    }

    private fun sendControl(dps: Map<String, Any?>): Int {
        return if (config.version == TuyaVersion.V33) {
            write(TuyaCmd.CONTROL, Json.stringify(linkedMapOf(
                "devId" to config.id, "uid" to config.id, "t" to now().toString(), "dps" to dps,
            )).toByteArray())
            TuyaCmd.CONTROL
        } else {
            write(TuyaCmd.CONTROL_NEW, Json.stringify(linkedMapOf(
                "protocol" to 5, "t" to now(), "data" to mapOf("dps" to dps),
            )).toByteArray())
            TuyaCmd.CONTROL_NEW
        }
    }

    private fun drainInbox() {
        while (inbox.tryReceive().isSuccess) { /* discard stale replies */ }
    }

    private suspend fun awaitFrame(timeoutMs: Long, match: (TuyaFrame) -> Boolean): TuyaFrame? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val f = inbox.receive()
                if (f.cmd == BAD_KEY_CMD) throw TuyaException.BadKey("The bulb's reply couldn't be decrypted. Check the local key.")
                if (match(f)) return@withTimeoutOrNull f
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        try {
            ensureConnected()
            return block()
        } catch (e: TuyaException.BadKey) {
            disconnect("bad key"); throw e
        } catch (e: IOException) {
            log("io error ${e.message}, reconnecting")
            disconnect("io error")
        } catch (e: TuyaException.Timeout) {
            disconnect("timeout"); throw e
        }
        ensureConnected()
        return block()
    }

    // ---------------------------------------------------------------- connection

    private suspend fun ensureConnected() = connectLock.withLock {
        if (socket?.isConnected == true && !socket!!.isClosed && readerJob?.isActive == true) return@withLock
        withContext(Dispatchers.IO) { openSocket() }
    }

    private fun openSocket() {
        disconnect("reconnecting", quiet = true)
        log("connecting to ${config.host}:${config.port} v${config.version.label}")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(config.host, config.port), 4000)
        } catch (e: IOException) {
            s.close()
            throw TuyaException.Unreachable("Couldn't reach the bulb at ${config.host}. Is your phone on the same Wi-Fi?", e)
        }
        s.tcpNoDelay = true
        s.keepAlive = true
        socket = s
        out = BufferedOutputStream(s.getOutputStream())
        input = DataInputStream(s.getInputStream().buffered())
        key = config.localKey.toByteArray(Charsets.ISO_8859_1)
        seq = 1
        if (config.version != TuyaVersion.V33) {
            s.soTimeout = 5000
            try {
                negotiateSessionKey()
            } catch (e: java.net.SocketTimeoutException) {
                disconnect("negotiation timeout", quiet = true)
                throw TuyaException.BadKey("The bulb didn't accept the key exchange. Check the local key and protocol version.")
            } catch (e: TuyaException) {
                disconnect("negotiation failed", quiet = true); throw e
            } catch (e: IOException) {
                disconnect("negotiation failed", quiet = true)
                throw TuyaException.BadKey("The bulb closed the connection during the key exchange. Check the local key and protocol version.")
            }
        }
        s.soTimeout = 0
        lastRx = System.currentTimeMillis()
        readerJob = scope.launch { readLoop(s, input!!) }
        heartbeatJob = scope.launch { heartbeatLoop(s) }
        _connected.value = true
        log("connected")
    }

    private fun negotiateSessionKey() {
        val localKey = config.localKey.toByteArray(Charsets.ISO_8859_1)
        val localNonce = TuyaCrypto.randomBytes(16)
        write(TuyaCmd.SESS_KEY_NEG_START, localNonce)
        var resp: TuyaFrame
        do {
            resp = TuyaCodec.decode(config.version, TuyaCodec.readFrame(input!!), localKey)
        } while (resp.cmd != TuyaCmd.SESS_KEY_NEG_RESP)
        if (resp.payload.size < 48) throw TuyaException.BadKey("Key exchange failed (short reply)")
        val remoteNonce = resp.payload.copyOfRange(0, 16)
        val check = TuyaCrypto.hmacSha256(localKey, localNonce)
        if (!check.contentEquals(resp.payload.copyOfRange(16, 48))) throw TuyaException.BadKey()
        write(TuyaCmd.SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(localKey, remoteNonce))
        val x = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        key = if (config.version == TuyaVersion.V34) {
            TuyaCrypto.ecbEncrypt(localKey, x, pad = false)
        } else {
            TuyaCrypto.gcmEncrypt(localKey, localNonce.copyOfRange(0, 12), null, x).copyOfRange(0, 16)
        }
        log("session key negotiated")
    }

    private suspend fun readLoop(s: Socket, inp: DataInputStream) {
        try {
            while (scope.isActive && !s.isClosed) {
                val raw = TuyaCodec.readFrame(inp)
                lastRx = System.currentTimeMillis()
                val frame = try {
                    TuyaCodec.decode(config.version, raw, key)
                } catch (e: TuyaException.BadKey) {
                    log("undecodable frame: ${e.message}")
                    inbox.trySend(TuyaFrame(0, BAD_KEY_CMD, null, ByteArray(0)))
                    continue
                } catch (e: Exception) {
                    log("bad frame: ${e.message}")
                    continue
                }
                if (frame.cmd != TuyaCmd.HEART_BEAT) log("rx $frame")
                parseDps(frame.text)?.let { if (it.isNotEmpty()) _updates.tryEmit(it) }
                inbox.trySend(frame)
            }
        } catch (e: Exception) {
            if (socket === s) log("connection lost: ${e.message}")
        } finally {
            if (socket === s) disconnect("reader ended")
        }
    }

    private suspend fun heartbeatLoop(s: Socket) {
        while (scope.isActive && socket === s) {
            delay(HEARTBEAT_MS)
            if (socket !== s) return
            if (System.currentTimeMillis() - lastRx > HEARTBEAT_MS * 3) {
                log("bulb went quiet, dropping connection")
                disconnect("heartbeat timeout")
                return
            }
            try {
                requestLock.withLock {
                    write(TuyaCmd.HEART_BEAT, Json.stringify(linkedMapOf("gwId" to config.id, "devId" to config.id)).toByteArray())
                }
            } catch (e: Exception) {
                disconnect("heartbeat failed")
                return
            }
        }
    }

    @Synchronized
    private fun write(cmd: Int, payload: ByteArray) {
        val o = out ?: throw IOException("Not connected")
        val frame = TuyaCodec.encode(config.version, seq++, cmd, payload, key)
        o.write(frame)
        o.flush()
    }

    private fun disconnect(reason: String, quiet: Boolean = false) {
        val s = socket
        socket = null
        out = null
        input = null
        heartbeatJob?.cancel()
        readerJob?.cancel()
        runCatching { s?.close() }
        if (s != null && !quiet) log("disconnected: $reason")
        _connected.value = false
    }

    companion object {
        private const val HEARTBEAT_MS = 10_000L
        private const val BAD_KEY_CMD = -2
        private val DEVICE22_DPS = listOf("1", "2", "3", "4", "5", "20", "21", "22", "23", "24")

        private fun now() = System.currentTimeMillis() / 1000

        @Suppress("UNCHECKED_CAST")
        fun parseDps(text: String): Map<String, Any?>? {
            if (!text.startsWith("{")) return null
            val obj = runCatching { Json.parseObject(text) }.getOrNull() ?: return null
            (obj["dps"] as? Map<String, Any?>)?.let { return it }
            ((obj["data"] as? Map<String, Any?>)?.get("dps") as? Map<String, Any?>)?.let { return it }
            return null
        }
    }
}
