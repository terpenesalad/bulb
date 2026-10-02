package app.halo.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredDevice(
    val id: String,
    val ip: String,
    val version: TuyaVersion,
    val productKey: String?,
)

/**
 * Listens for the UDP broadcasts Tuya devices send every few seconds, and
 * nudges 3.5 devices to announce themselves.
 */
object TuyaDiscovery {
    val PORTS = intArrayOf(6666, 6667, 7000)

    fun parse(data: ByteArray, fromIp: String): DiscoveredDevice? {
        val text = TuyaCodec.decodeBroadcast(data) ?: return null
        val obj = runCatching { Json.parseObject(text) }.getOrNull() ?: return null
        val id = (obj["gwId"] ?: obj["devId"] ?: obj["id"]) as? String ?: return null
        if (obj["from"] == "app") return null // another phone's discovery request
        val ip = (obj["ip"] as? String)?.takeIf { it.isNotBlank() } ?: fromIp
        return DiscoveredDevice(id, ip, TuyaVersion.from(obj["version"]?.toString()), obj["productKey"] as? String)
    }

    /** Emits each device once, as it's heard. Cancel the collector to stop. */
    fun scan(ports: IntArray = PORTS, broadcastAddress: String = "255.255.255.255"): Flow<DiscoveredDevice> = callbackFlow {
        val seen = HashSet<String>()
        val sockets = ports.toList().mapNotNull { port ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 500
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        }
        if (sockets.isEmpty()) close(IllegalStateException("Couldn't listen for bulbs on this network"))
        run {
            for (sock in sockets) launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                while (isActive && !sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                    val dev = parse(pkt.data.copyOf(pkt.length), pkt.address.hostAddress ?: "") ?: continue
                    synchronized(seen) { if (!seen.add(dev.id + dev.ip)) null else dev }?.let { trySend(it) }
                }
            }
            launch(Dispatchers.IO) {
                // 3.5 devices stay quiet until an app asks.
                val req = TuyaCodec.pack6699(0, TuyaCmd.REQ_DEVINFO,
                    Json.stringify(mapOf("from" to "app", "ip" to "0.0.0.0")).toByteArray(),
                    TuyaCodec.UDP_KEY, TuyaCrypto.randomBytes(12))
                val out = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
                try {
                    while (isActive) {
                        runCatching { out.send(DatagramPacket(req, req.size, InetAddress.getByName(broadcastAddress), 7000)) }
                        delay(3000)
                    }
                } finally { out.close() }
            }
            awaitClose { sockets.forEach { it.close() } }
        }
    }.flowOn(Dispatchers.IO)
}
