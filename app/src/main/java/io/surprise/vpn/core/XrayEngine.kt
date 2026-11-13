package io.surprise.vpn.core

import libXray.LibXray
import io.surprise.vpn.data.model.VpnProfile
import org.json.JSONObject

object XrayEngine {
    const val CORE_VERSION = "26.11.9"
    private const val API_VERSION = 3

    data class SshBridge(
        val sessionId: String,
        val host: String,
        val port: Int,
        val statusCode: Int,
        val redirects: Int,
        val webSocket: Boolean,
        val finalHost: String,
        val finalPath: String
    )

    fun run(xrayJson: String): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "runXray")
            .put("payload", JSONObject().put("xrayJson", xrayJson))
        return invoke(request.toString())
    }

    fun stop(): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "stopXray")
            .put("payload", JSONObject())
        return invoke(request.toString())
    }

    fun startSsh(profile: VpnProfile): SshBridge {
        require(profile.protocol.name == "SSH") { "libXray SSH requires an SSH profile" }
        val proxy = parseProxy(profile.sshRemoteProxy)
        val config = JSONObject()
            .put("server", profile.server)
            .put("port", profile.port)
            .put("username", profile.host.ifBlank { "root" })
            .put("password", profile.secret)
            .put("privateKey", profile.sshPrivateKey)
            .put("proxyHost", proxy.first)
            .put("proxyPort", proxy.second)
            .put("sni", profile.sni.ifBlank { profile.server })
            .put("tls", profile.sshTlsEnabled)
            .put("payload", if (profile.sshPayloadEnabled) profile.sshPayload else "")
            .put("maxRedirects", 5)
            .put("allowRedirectTo", false)
            .put("followRedirects", true)
            // Existing V2-TUN SSH profiles used JSch with host-key checking
            // disabled. Preserve that behavior until a fingerprint field is
            // added to the profile editor.
            .put("insecureSkipHostKey", true)
        val response = invoke(JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "startSSH")
            .put("payload", JSONObject().put("config", config))
            .toString())
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(errorMessage(response))
        }
        val data = response.optJSONObject("data")
            ?: throw IllegalStateException("The Xray engine returned no SSH bridge data.")
        val sessionId = data.optString("sessionId")
        val host = data.optString("host", "127.0.0.1")
        val port = data.optInt("port", 0)
        if (sessionId.isBlank() || port !in 1..65535) {
            throw IllegalStateException("The Xray engine returned an invalid SSH bridge.")
        }
        return SshBridge(
            sessionId = sessionId,
            host = host,
            port = port,
            statusCode = data.optInt("statusCode", 0),
            redirects = data.optInt("redirects", 0),
            webSocket = data.optBoolean("webSocket", false),
            finalHost = data.optString("finalHost", profile.server),
            finalPath = data.optString("finalPath", "/")
        )
    }

    fun stopSsh(sessionId: String): JSONObject {
        if (sessionId.isBlank()) return JSONObject().put("success", true).put("data", JSONObject())
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "stopSSH")
            .put("payload", JSONObject().put("sessionId", sessionId))
        return invoke(request.toString())
    }

    fun isRunning(): Boolean {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "getXrayState")
            .put("payload", JSONObject())
        val response = invoke(request.toString())
        return response.optBoolean("success", false) &&
            response.optJSONObject("data")?.optBoolean("running", false) == true
    }

    private fun invoke(request: String): JSONObject {
        val raw = LibXray.invoke(request)
        return runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalStateException("The Xray engine returned an unreadable response.") }
    }

    fun errorMessage(response: JSONObject): String =
        response.optString("error").ifBlank { "The Xray engine could not start the tunnel." }

    private fun parseProxy(value: String): Pair<String, Int> {
        if (value.isBlank()) return "" to 0
        val normalized = value.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringAfterLast('@')
        val separator = normalized.lastIndexOf(':')
        if (separator <= 0) return normalized to 8080
        return normalized.substring(0, separator) to
            (normalized.substring(separator + 1).toIntOrNull() ?: 8080)
    }
}
