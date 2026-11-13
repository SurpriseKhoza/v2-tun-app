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
    fun test(profile: VpnProfile, protectSocket: (Socket) -> Boolean = { true }): HttpPayloadTestResult {
        if (profile.protocol != VpnProtocol.SSH) return failure("Select an SSH profile to test an HTTP payload.")
        if (profile.server.isBlank() || profile.port !in 1..65535) return failure("The SSH host or port is invalid.")
        return runCatching {
            GoCarrierSocket(profile, protectSocket).use { }
            HttpPayloadTestResult(true, null, 0, false, profile.server, "/", "Go payload engine accepted the carrier.")
        }.getOrElse { failure(it.message?.take(240) ?: "Payload negotiation failed.") }
    }

    private fun failure(message: String) = HttpPayloadTestResult(false, null, 0, false, null, null, message)
}
