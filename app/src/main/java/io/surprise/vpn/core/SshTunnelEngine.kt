package io.surprise.vpn.core

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import io.surprise.vpn.data.model.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSH transport used by the guided SSH profile.
 *
 * The engine deliberately exposes only a local SOCKS5 endpoint to Xray. Xray
 * remains responsible for TUN routing, DNS rules, metrics, and lifecycle. This
 * keeps SSH support additive instead of duplicating the VPN implementation.
 * UDP is not advertised: SSH dynamic forwarding is TCP-only.
 */
class SshTunnelEngine(
    private val profile: VpnProfile,
    private val protectSocket: (Socket) -> Boolean,
    private val log: (String, String) -> Unit = { message, level -> TunnelLogs.app(message, level) }
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var session: Session? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: kotlinx.coroutines.Job? = null

    val localSocksPort: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        require(profile.protocol.name == "SSH") { "SSH engine requires an SSH profile" }
        val jsch = JSch()
        if (profile.sshPrivateKey.isNotBlank()) {
            jsch.addIdentity(
                "v2tun-key",
                profile.sshPrivateKey.toByteArray(StandardCharsets.UTF_8),
                null,
                null
            )
        }
        val user = profile.host.ifBlank { "root" }
        val created = jsch.getSession(user, profile.server, profile.port)
        created.setConfig("StrictHostKeyChecking", "no")
        if (profile.secret.isNotBlank()) created.setPassword(profile.secret)
        created.setSocketFactory(SshSocketFactory(profile, protectSocket))
        created.connect(15_000)
        session = created
        serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        acceptJob = scope.launch {
            log("SSH authenticated; local SOCKS bridge on 127.0.0.1:${localSocksPort}", "INFO")
            while (!closed.get()) {
                val client = runCatching { serverSocket?.accept() }.getOrNull() ?: break
                launch { handleClient(client) }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val version = input.read()
            if (version != 5) return
            val methods = input.read().coerceAtLeast(0)
            repeat(methods) { input.read() }
            output.write(byteArrayOf(5, 0))
            output.flush()
            if (input.read() != 5 || input.read() != 1) return
            input.read()
            val addressType = input.read()
            val host = when (addressType) {
                1 -> readBytes(input, 4).joinToString(".") { (it.toInt() and 255).toString() }
                3 -> readBytes(input, input.read()).toString(StandardCharsets.UTF_8)
                4 -> readBytes(input, 16).joinToString(":") { "%02x".format(it) }
                else -> return
            }
            val port = ((input.read() and 255) shl 8) or (input.read() and 255)
            val ssh = session ?: return
            val channel = runCatching {
                (ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                    setHost(host)
                    setPort(port)
                    setOrgIPAddress("127.0.0.1")
                    setOrgPort(0)
                    connect(15_000)
                }
            }.getOrNull() ?: run {
                output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            socket.soTimeout = 0
            val channelInput = channel.inputStream
            val channelOutput = channel.outputStream
            val toSsh = scope.launch { copy(input, channelOutput) }
            val fromSsh = scope.launch { copy(channelInput, output) }
            toSsh.join()
            toSsh.cancel()
            fromSsh.cancel()
            runCatching { channel.disconnect() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Throwable) {
            // The peer closing either half is normal for a SOCKS stream.
        }
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        require(count in 0..255)
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IllegalStateException("Incomplete SOCKS request")
            offset += read
        }
        return bytes
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { session?.disconnect() }
        session = null
        serverSocket = null
        scope.cancel()
    }

    private class SshSocketFactory(
        private val profile: VpnProfile,
        private val protectSocket: (Socket) -> Boolean
    ) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            return GoCarrierSocket(profile.copy(server = host, port = port), protectSocket)
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }
}
