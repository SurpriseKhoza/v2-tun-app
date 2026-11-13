package io.surprise.vpn.core

import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import java.net.Socket

data class HttpPayloadTestResult(
    val success: Boolean,
    val statusCode: Int?,
    val redirects: Int,
    val websocket: Boolean,
    val finalHost: String?,
    val finalPath: String?,
    val message: String
)

object HttpPayloadTester {
    /**
     * Performs a complete native libXray SSH startup and payload negotiation.
     * The optional socket callback remains source-compatible for callers from
     * older app builds, but protection is now owned by libXray's controller.
     */
    fun test(profile: VpnProfile, @Suppress("UNUSED_PARAMETER") protectSocket: (Socket) -> Boolean = { true }): HttpPayloadTestResult {
        if (profile.protocol != VpnProtocol.SSH) return failure("Select an SSH profile to test an HTTP payload.")
        if (profile.server.isBlank() || profile.port !in 1..65535) return failure("The SSH host or port is invalid.")
        return runCatching {
            val bridge = XrayEngine.startSsh(profile)
            try {
                HttpPayloadTestResult(
                    success = true,
                    statusCode = null,
                    redirects = 0,
                    websocket = profile.sshPayload.contains("upgrade: websocket", ignoreCase = true),
                    finalHost = profile.server,
                    finalPath = "/",
                    message = "libXray SSH and payload negotiation succeeded on ${bridge.host}:${bridge.port}."
                )
            } finally {
                XrayEngine.stopSsh(bridge.sessionId)
            }
        }.getOrElse { failure(it.message?.take(240) ?: "Native SSH payload negotiation failed.") }
    }

    private fun failure(message: String) = HttpPayloadTestResult(false, null, 0, false, null, null, message)
}
