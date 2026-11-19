package com.erashon.sharedrop.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object T {
    const val HELLO = 1
    const val AUTH = 2
    const val AUTH_OK = 3
    const val PAIR_SECRET = 4
    const val OFFER = 5
    const val ACCEPT = 6
    const val FILE_START = 7
    const val DATA = 8
    const val FILE_END = 9
    const val DONE = 10
    const val ERROR = 12
}

class Frame(val type: Int, val payload: ByteArray)

object Crypto {
    private val rnd = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun pinSecret(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("sharedrop-pin:$code".toByteArray())

    fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Canal de tramas sobre un socket TCP. Antes de [enableCrypto] va en claro; después, AES-256-GCM. */
class Channel(private val sock: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
    private val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))

    private var key: SecretKeySpec? = null
    private var sendPrefix = 0
    private var recvPrefix = 0
    private var sendCtr = 0L
    private var recvCtr = 0L

    val remoteAddress: String get() = sock.inetAddress?.hostAddress ?: "?"

    fun enableCrypto(sessionKey: ByteArray, isClient: Boolean) {
        key = SecretKeySpec(sessionKey, "AES")
        sendPrefix = if (isClient) 1 else 2
        recvPrefix = if (isClient) 2 else 1
    }

    private fun nonce(prefix: Int, ctr: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(prefix).putLong(ctr).array()

    @Synchronized
    fun send(type: Int, payload: ByteArray = ByteArray(0), len: Int = payload.size) {
        val k = key
        if (k == null) {
            output.writeInt(1 + len)
            output.writeByte(type)
            output.write(payload, 0, len)
        } else {
            val plain = ByteArray(1 + len)
            plain[0] = type.toByte()
            System.arraycopy(payload, 0, plain, 1, len)
            val ctr = sendCtr++
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(sendPrefix, ctr)))
            val ct = c.doFinal(plain)
            output.writeInt(8 + ct.size)
            output.writeLong(ctr)
            output.write(ct)
        }
        output.flush()
    }

    fun recv(): Frame {
        val len = input.readInt()
        if (len < 1 || len > MAX_FRAME) throw IOException("Trama inválida ($len)")
        val body = ByteArray(len)
        input.readFully(body)
        val k = key ?: return Frame(body[0].toInt() and 0xff, body.copyOfRange(1, body.size))
        if (len < 8 + 16 + 1) throw IOException("Trama cifrada demasiado corta")
        val bb = ByteBuffer.wrap(body)
        val ctr = bb.long
        if (ctr != recvCtr) throw IOException("Contador fuera de orden")
        recvCtr++
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(recvPrefix, ctr)))
        val plain = c.doFinal(body, 8, len - 8)
        return Frame(plain[0].toInt() and 0xff, plain.copyOfRange(1, plain.size))
    }

    override fun close() {
        runCatching { sock.close() }
    }

    companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
