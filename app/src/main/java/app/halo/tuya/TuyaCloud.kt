package app.halo.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudDevice(
    val id: String,
    val name: String,
    val localKey: String,
    val category: String?,
    val productName: String?,
    val online: Boolean?,
)

/**
 * Minimal Tuya IoT Platform client, used once during setup to read a device's
 * local key. Everything after setup is local.
 */
object TuyaCloud {
    enum class Region(val label: String, val host: String) {
        US("Western America", "openapi.tuyaus.com"),
        US_E("Eastern America", "openapi-ueaz.tuyaus.com"),
        EU("Central Europe", "openapi.tuyaeu.com"),
        EU_W("Western Europe", "openapi-weaz.tuyaeu.com"),
        IN("India", "openapi.tuyain.com"),
        SG("Singapore", "openapi-sg.iotbing.com"),
        CN("China", "openapi.tuyacn.com"),
    }

    class CloudException(message: String) : Exception(message)

    /** Tries each region (or just [region]) and returns the first that knows this account's devices. */
    suspend fun fetchDevices(accessId: String, secret: String, region: Region? = null): Pair<Region, List<CloudDevice>> =
        withContext(Dispatchers.IO) {
            val regions = region?.let { listOf(it) } ?: Region.entries
            var lastError: String? = null
            var anyToken = false
            for (r in regions) {
                val token = try { token(r, accessId.trim(), secret.trim()) } catch (e: CloudException) {
                    lastError = e.message; null
                } ?: continue
                anyToken = true
                val devices = try { devices(r, accessId.trim(), secret.trim(), token) } catch (e: CloudException) {
                    lastError = e.message; continue
                }
                if (devices.isNotEmpty()) return@withContext r to devices
                lastError = "No devices are linked in the ${r.label} data centre"
            }
            throw CloudException(
                if (!anyToken) "Tuya didn't accept that Access ID / Secret. ${lastError ?: ""}".trim()
                else "${lastError ?: "No devices found"}. Make sure you linked your Smart Life account to the cloud project (Devices → Link App Account)."
            )
        }

    private fun token(r: Region, id: String, secret: String): String {
        val res = request(r, id, secret, null, "/v1.0/token?grant_type=1")
        @Suppress("UNCHECKED_CAST")
        val result = res["result"] as? Map<String, Any?> ?: throw CloudException(res["msg"]?.toString() ?: "No token")
        return result["access_token"] as? String ?: throw CloudException("No token")
    }

    private fun devices(r: Region, id: String, secret: String, token: String): List<CloudDevice> {
        val out = ArrayList<CloudDevice>()
        var lastRowKey: String? = null
        repeat(10) {
            val q = buildString {
                if (lastRowKey != null) append("last_row_key=").append(URLEncoder.encode(lastRowKey, "UTF-8")).append('&')
                append("size=50")
            }
            val res = request(r, id, secret, token, "/v1.0/iot-01/associated-users/devices?$q")
            @Suppress("UNCHECKED_CAST")
            val result = res["result"] as? Map<String, Any?> ?: throw CloudException(res["msg"]?.toString() ?: "Couldn't list devices")
            @Suppress("UNCHECKED_CAST")
            for (d in (result["devices"] as? List<Map<String, Any?>>).orEmpty()) {
                out += CloudDevice(
                    id = d["id"] as? String ?: continue,
                    name = d["name"] as? String ?: "Tuya device",
                    localKey = d["local_key"] as? String ?: "",
                    category = d["category"] as? String,
                    productName = d["product_name"] as? String,
                    online = d["online"] as? Boolean,
                )
            }
            if (result["has_more"] != true) return out
            lastRowKey = result["last_row_key"] as? String ?: return out
        }
        return out
    }

    /** Builds the signature exactly as Tuya's "new" (2021+) signing scheme expects. */
    fun sign(clientId: String, secret: String, token: String?, t: String, method: String, pathAndQuery: String, body: String = ""): String {
        val stringToSign = method + "\n" + TuyaCrypto.sha256Hex(body.toByteArray()) + "\n" + "\n" + pathAndQuery
        val str = clientId + (token ?: "") + t + stringToSign
        return TuyaCrypto.hmacSha256(secret.toByteArray(), str.toByteArray()).toHex().uppercase()
    }

    private fun request(r: Region, id: String, secret: String, token: String?, pathAndQuery: String): Map<String, Any?> {
        val t = System.currentTimeMillis().toString()
        val conn = URL("https://${r.host}$pathAndQuery").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("client_id", id)
            conn.setRequestProperty("sign", sign(id, secret, token, t, "GET", pathAndQuery))
            conn.setRequestProperty("t", t)
            conn.setRequestProperty("sign_method", "HMAC-SHA256")
            if (token != null) conn.setRequestProperty("access_token", token)
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            val obj = runCatching { Json.parseObject(text) }.getOrNull()
                ?: throw CloudException("Unexpected reply from Tuya (HTTP $code)")
            if (obj["success"] != true) throw CloudException(obj["msg"]?.toString() ?: "Tuya returned an error")
            return obj
        } catch (e: java.io.IOException) {
            throw CloudException("Couldn't reach Tuya's servers (${r.label})")
        } finally {
            conn.disconnect()
        }
    }
}
