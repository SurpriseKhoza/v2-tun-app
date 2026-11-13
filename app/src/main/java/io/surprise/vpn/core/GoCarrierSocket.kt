package io.surprise.vpn.core

import android.os.ParcelFileDescriptor
import io.surprise.vpn.data.model.VpnProfile
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal class GoCarrierSocket(
    profile: VpnProfile,
    private val protectSocket: (Socket) -> Boolean
) : Socket() {
    private val engine = GoPayloadEngine()
    private val handle: Long
    private var closed = false

    init {
        var targetHost = profile.server
        var targetPort = profile.port
        var targetPath = "/"
        var tls = profile.sshTlsEnabled
        var sni = profile.sni.ifBlank { targetHost }
        var endpoint = parseEndpoint(profile.sshRemoteProxy, targetHost, targetPort)
        var payload = if (profile.sshPayloadEnabled) profile.sshPayload else ""
        var redirects = 0
        var attempts = 0
        while (true) {
            attempts++
            val raw = Socket()
            check(protectSocket(raw)) { "Could not protect Go payload socket from the VPN route" }
            try {
                raw.connect(InetSocketAddress(endpoint.first, endpoint.second), 15_000)
                val descriptor = ParcelFileDescriptor.fromSocket(raw)
                val fd = descriptor.detachFd()
                raw.close()
                val opened = engine.openOnFd(fd, targetHost, targetPort, sni, tls, payloadForPath(payload, targetPath))
                if (opened != 0L) {
                    handle = opened
                    break
                }
                val error = engine.lastError()
                if (!error.startsWith("redirect:") || redirects++ >= MAX_REDIRECTS) {
                    if (isTransient(error) && attempts < MAX_ATTEMPTS) {
                        Thread.sleep((BASE_RETRY_MS shl (attempts - 1).coerceAtMost(3)).toLong())
                        continue
                    }
                    throw IllegalStateException(error.ifBlank { "Go payload negotiation failed" })
                }
                val redirect = URI(error.removePrefix("redirect:"))
                val nextScheme = redirect.scheme?.lowercase() ?: if (tls) "https" else "http"
                targetHost = redirect.host ?: targetHost
                targetPort = if (redirect.port > 0) redirect.port else if (nextScheme == "https") 443 else 80
                targetPath = buildPath(redirect)
                tls = nextScheme == "https"
                sni = targetHost
                endpoint = targetHost to targetPort
                attempts = 0
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            }
        }
    }

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            val data = engine.read(handle, length)
            if (data.isEmpty()) return -1
            data.copyInto(bytes, offset)
            return data.size
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            if (length == 0) return
            val written = engine.write(handle, bytes.copyOfRange(offset, offset + length))
            if (written < 0) throw IllegalStateException(engine.lastError().ifBlank { "Go payload write failed" })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.close(handle)
        engine.close()
    }

    private fun parseEndpoint(value: String, fallbackHost: String, fallbackPort: Int): Pair<String, Int> {
        if (value.isBlank()) return fallbackHost to fallbackPort
        val normalized = value.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringAfterLast('@')
        val separator = normalized.lastIndexOf(':')
        if (separator <= 0) return normalized to 8080
        return normalized.substring(0, separator) to (normalized.substring(separator + 1).toIntOrNull() ?: 8080)
    }

    private fun buildPath(uri: URI): String = uri.rawPath.orEmpty().ifBlank { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty()

    private fun payloadForPath(payload: String, path: String): String {
        if (payload.isBlank() || path == "/") return payload
        val lines = payload.replace("[crlf]", "\r\n", ignoreCase = true).split("\r\n").toMutableList()
        if (lines.isNotEmpty()) {
            val parts = lines[0].split(" ").toMutableList()
            if (parts.size >= 3 && parts[0].matches(Regex("[A-Z]+"))) { parts[1] = path; lines[0] = parts.joinToString(" ") }
        }
        return lines.joinToString("[crlf]")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_ATTEMPTS = 4
        const val BASE_RETRY_MS = 500
    }

    private fun isTransient(error: String): Boolean {
        val value = error.lowercase()
        return listOf(" 500", " 502", " 503", " 504", "connection reset", "timeout", "eof", "temporarily").any(value::contains)
    }
}
