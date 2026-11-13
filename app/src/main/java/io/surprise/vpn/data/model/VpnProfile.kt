package io.surprise.vpn.data.model

import org.json.JSONObject
import java.util.UUID

/**
 * A single user-owned server configuration. [jsonConfig] is optional and, when present,
 * is treated as the authoritative Xray configuration instead of the guided fields.
 */
data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: VpnProtocol = VpnProtocol.VLESS,
    val server: String = "",
    val port: Int = 443,
    val secret: String = "",
    val method: String = "aes-256-gcm",
    val alterId: Int = 0,
    val transport: Transport = Transport.TCP,
    val security: Security = Security.TLS,
    val sni: String = "",
    val host: String = "",
    val path: String = "/",
    val serviceName: String = "",
    val flow: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "/",
    val allowInsecure: Boolean = false,
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val sshPayloadEnabled: Boolean = false,
    val sshTlsEnabled: Boolean = false,
    val sshPayload: String = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: keep-alive[crlf][crlf]",
    val sshRemoteProxy: String = "",
    val sshPrivateKey: String = "",
    val jsonConfig: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val endpoint: String
        get() = if (port in 1..65535) "$server:$port" else server

    val isJsonBacked: Boolean
        get() = !jsonConfig.isNullOrBlank()

    val securityLabel: String
        get() = when {
            isJsonBacked -> "JSON"
            protocol == VpnProtocol.SSH -> "SSH"
            security == Security.NONE -> transport.label
            else -> "${security.label} · ${transport.label}"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("server", server)
        put("port", port)
        put("secret", secret)
        put("method", method)
        put("alterId", alterId)
        put("transport", transport.name)
        put("security", security.name)
        put("sni", sni)
        put("host", host)
        put("path", path)
        put("serviceName", serviceName)
        put("flow", flow)
        put("fingerprint", fingerprint)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("spiderX", spiderX)
        put("allowInsecure", allowInsecure)
        put("muxEnabled", muxEnabled)
        put("muxConcurrency", muxConcurrency)
        put("sshPayloadEnabled", sshPayloadEnabled)
        put("sshTlsEnabled", sshTlsEnabled)
        put("sshPayload", sshPayload)
        put("sshRemoteProxy", sshRemoteProxy)
        put("sshPrivateKey", sshPrivateKey)
        jsonConfig?.let { put("jsonConfig", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): VpnProfile = VpnProfile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            protocol = VpnProtocol.from(json.optString("protocol")),
            server = json.optString("server"),
            port = json.optInt("port", 443),
            secret = json.optString("secret"),
            method = json.optString("method", "aes-256-gcm"),
            alterId = json.optInt("alterId", 0),
            transport = Transport.from(json.optString("transport")),
            security = Security.from(json.optString("security")),
            sni = json.optString("sni"),
            host = json.optString("host"),
            path = json.optString("path", "/"),
            serviceName = json.optString("serviceName"),
            flow = json.optString("flow"),
            fingerprint = json.optString("fingerprint", "chrome"),
            publicKey = json.optString("publicKey"),
            shortId = json.optString("shortId"),
            spiderX = json.optString("spiderX", "/"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            muxEnabled = json.optBoolean("muxEnabled", false),
            muxConcurrency = json.optInt("muxConcurrency", 8).coerceIn(1, 128),
            sshPayloadEnabled = json.optBoolean("sshPayloadEnabled", false),
            sshTlsEnabled = json.optBoolean("sshTlsEnabled", false),
            sshPayload = json.optString("sshPayload", "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: keep-alive[crlf][crlf]"),
            sshRemoteProxy = json.optString("sshRemoteProxy"),
            sshPrivateKey = json.optString("sshPrivateKey"),
            jsonConfig = json.optString("jsonConfig").takeIf { it.isNotBlank() },
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
