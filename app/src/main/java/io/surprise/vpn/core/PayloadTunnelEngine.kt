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
    private var bridge: XrayEngine.PayloadBridge? = null

    val localPort: Int
        get() = bridge?.port ?: 0

    fun start() {
        check(!closed.get()) { "Payload engine is already closed" }
        require(profile.protocol.name != "SSH") { "Use the SSH engine for SSH profiles" }
        val started = XrayEngine.startPayload(profile)
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
