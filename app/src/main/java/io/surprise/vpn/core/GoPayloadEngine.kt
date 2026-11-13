package io.surprise.vpn.core

import java.io.Closeable
import java.nio.ByteBuffer

/** Go payload carrier used by SSH; Xray's own transport remains independent. */
class GoPayloadEngine : Closeable {
    init { System.loadLibrary("payloadengine") }

    fun open(proxyHost: String, proxyPort: Int, targetHost: String, targetPort: Int, sni: String, tls: Boolean, payload: String): Long =
        PayloadNative.open(proxyHost, proxyPort, targetHost, targetPort, sni, tls, payload)

    fun openOnFd(fd: Int, targetHost: String, targetPort: Int, sni: String, tls: Boolean, payload: String): Long =
        PayloadNative.openOnFd(fd, targetHost, targetPort, sni, tls, payload)

    fun read(handle: Long, maxBytes: Int = 16 * 1024): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val direct = ByteBuffer.allocateDirect(maxBytes)
        val count = PayloadNative.read(handle, direct, maxBytes)
        if (count <= 0) return ByteArray(0)
        direct.position(0)
        return ByteArray(count).also(direct::get)
    }

    fun write(handle: Long, data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val direct = ByteBuffer.allocateDirect(data.size)
        direct.put(data).position(0)
        return PayloadNative.write(handle, direct, data.size)
    }

    fun close(handle: Long) = PayloadNative.close(handle)
    fun lastError(): String = PayloadNative.lastError()
    override fun close() = Unit
}

private object PayloadNative {
    init { System.loadLibrary("payloadengine") }
    external fun open(proxyHost: String, proxyPort: Int, targetHost: String, targetPort: Int, sni: String, tls: Boolean, payload: String): Long
    external fun openOnFd(fd: Int, targetHost: String, targetPort: Int, sni: String, tls: Boolean, payload: String): Long
    external fun read(handle: Long, buffer: ByteBuffer, max: Int): Int
    external fun write(handle: Long, buffer: ByteBuffer, length: Int): Int
    external fun close(handle: Long)
    external fun lastError(): String
}
