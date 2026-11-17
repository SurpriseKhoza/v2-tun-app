package io.surprise.vpn.core

import io.surprise.vpn.data.model.VpnProfile
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSH bridge backed by libXray's native Go implementation.
 *
 * libXray owns SSH authentication, payload negotiation, redirects, WebSocket
 * upgrades, protected sockets, and the local SOCKS5 listener. Xray continues
 * to own TUN routing and sends its SSH traffic through the returned listener.
 */
class SshTunnelEngine(
    private val profile: VpnProfile,
    private val log: (String, String) -> Unit = { message, level -> TunnelLogs.app(message, level) }
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile
    private var bridge: XrayEngine.SshBridge? = null

    val localSocksPort: Int
        get() = bridge?.port ?: 0

    /** Cancellable: cancelling the caller abandons the native handshake and cleans it up. */
    suspend fun start() {
        check(!closed.get()) { "SSH engine is already closed" }
        require(profile.protocol.name == "SSH") { "SSH engine requires an SSH profile" }
        val started = runNativeCancellable(
            name = "ssh-start",
            onAbandoned = { abandoned -> runCatching { XrayEngine.stopSsh(abandoned.sessionId) } }
        ) { XrayEngine.startSsh(profile) }
        if (closed.get()) {
            runCatching { XrayEngine.stopSsh(started.sessionId) }
            throw IllegalStateException("SSH engine was closed while starting")
        }
        bridge = started
        val carrier = if (started.statusCode > 0) {
            "HTTP ${started.statusCode}, redirects=${started.redirects}, websocket=${started.webSocket}"
        } else {
            "direct carrier"
        }
        log(
            "SSH authenticated; libXray SOCKS bridge on ${started.host}:${started.port} ($carrier)",
            "INFO"
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val sessionId = bridge?.sessionId.orEmpty()
        bridge = null
        runCatching { XrayEngine.stopSsh(sessionId) }
            .onFailure { log("Could not stop libXray SSH session: ${it.message}", "WARN") }
    }
}
