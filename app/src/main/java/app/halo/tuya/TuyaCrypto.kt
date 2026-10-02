package app.halo.tuya

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TuyaCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun ecbEncrypt(key: ByteArray, data: ByteArray, pad: Boolean = true): ByteArray {
        val c = Cipher.getInstance(if (pad) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** Decrypts and strips PKCS#7 padding leniently (like the device firmware does). */
    fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "Encrypted length ${data.size} is not a multiple of 16" }
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val raw = c.doFinal(data)
        if (raw.isEmpty()) return raw
        val pad = raw.last().toInt() and 0xff
        if (pad < 1 || pad > 16 || pad > raw.size) throw TuyaException.BadKey("Bad padding (wrong local key?)")
        return raw.copyOf(raw.size - pad)
    }

    /** Returns ciphertext + 16 byte tag. */
    fun gcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    /** [data] is ciphertext + 16 byte tag. Throws if authentication fails. */
    fun gcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun crc32(data: ByteArray, len: Int = data.size): Int =
        CRC32().apply { update(data, 0, len) }.value.toInt()

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteBuffer.getBytes(n: Int): ByteArray = ByteArray(n).also { get(it) }
