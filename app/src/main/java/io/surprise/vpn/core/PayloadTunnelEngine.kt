package io.surprise.vpn.core

import io.surprise.vpn.data.model.VpnProfile
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Carries a raw Xray protocol stream through the shared HTTP/WebSocket payload engine. */
class PayloadTunnelEngine(
    private val profile: VpnProfile,
    private val log: (String, String) -> Unit = { message, level -> TunnelLogs.app(message, level) }
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile
    private var bridge: XrayEngine.PayloadBridge? = null

    val localPort: Int
        get() = bridge?.port ?: 0

    /** Cancellable: cancelling the caller abandons the native handshake and cleans it up. */
    suspend fun start() {
        check(!closed.get()) { "Payload engine is already closed" }
        require(profile.protocol.name != "SSH") { "Use the SSH engine for SSH profiles" }
        val started = runNativeCancellable(
            name = "payload-start",
            onAbandoned = { abandoned -> runCatching { XrayEngine.stopPayload(abandoned.sessionId) } }
        ) { XrayEngine.startPayload(profile) }
        if (closed.get()) {
            runCatching { XrayEngine.stopPayload(started.sessionId) }
            throw IllegalStateException("Payload engine was closed while starting")
        }
        bridge = started
        log(
            "Payload carrier ready on ${started.host}:${started.port} (HTTP ${started.statusCode}, redirects=${started.redirects}, websocket=${started.webSocket})",
            "INFO"
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val sessionId = bridge?.sessionId.orEmpty()
        bridge = null
        runCatching { XrayEngine.stopPayload(sessionId) }
            .onFailure { log("Could not stop payload bridge: ${it.message}", "WARN") }
    }
}
