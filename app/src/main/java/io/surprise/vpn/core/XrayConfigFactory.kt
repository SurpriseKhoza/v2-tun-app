package io.surprise.vpn.core

import io.surprise.vpn.data.model.AppSettings
import io.surprise.vpn.data.model.Security
import io.surprise.vpn.data.model.Transport
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import io.surprise.vpn.util.Base64Codec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

data class BuiltXrayConfig(
    val json: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPassword: String
)

data class ValidationResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object XrayConfigFactory {

    private const val TUN_TAG = "v2tun-in"
    private const val SOCKS_TAG = "v2tun-socks"
    private const val DNS_TAG = "v2tun-dns"
    private const val DIRECT_TAG = "direct"
    private const val BLOCK_TAG = "block"

    private val privateCidrs = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
        "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8"
    )

    fun build(
        profile: VpnProfile,
        settings: AppSettings,
        tunFd: Int,
        logDirectory: File? = null,
        proxyPortOverride: Int? = null,
        payloadBridgePortOverride: Int? = null
    ): BuiltXrayConfig {
        // proxyPortOverride is an already-bound upstream bridge. Xray still
        // needs a separate local SOCKS listener for diagnostics; reusing the
        // bridge port causes "address already in use" during startup.
        val socksPort = findFreePort()
        val token = randomToken(12)
        val root = if (profile.jsonConfig.isNullOrBlank()) {
            guidedRoot(profile, settings, tunFd, socksPort, token, logDirectory, proxyPortOverride, payloadBridgePortOverride)
        } else {
            customRoot(profile.jsonConfig, settings, tunFd, socksPort, token, logDirectory)
        }
        return BuiltXrayConfig(
            json = root.toString(2),
            socksPort = socksPort,
            socksUser = "v2tun",
            socksPassword = token
        )
    }

    fun preview(profile: VpnProfile, settings: AppSettings): String {
        val root = if (profile.jsonConfig.isNullOrBlank()) {
            guidedRoot(profile, settings, 7, 10808, "preview-token", null, null, null)
        } else {
            customRoot(profile.jsonConfig, settings, 7, 10808, "preview-token", null)
        }
        return root.toString(2)
    }

    fun validate(profile: VpnProfile, settings: AppSettings): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (profile.name.isBlank()) errors += "Give this configuration a name."
        if (!profile.isJsonBacked) {
            if (profile.server.isBlank()) errors += "Server address is required."
            if (profile.port !in 1..65535) errors += "Port must be between 1 and 65535."
            if (profile.protocol == VpnProtocol.SSH) {
                if (profile.secret.isBlank() && profile.sshPrivateKey.isBlank()) {
                    errors += "SSH password or private key is required."
                }
                if (profile.sshTlsEnabled && profile.sni.isBlank()) {
                    errors += "TLS requires an SNI / server name."
                }
                if (profile.sshPayloadEnabled && profile.sshPayload.isBlank()) {
                    errors += "Payload cannot be empty when Payload is enabled."
                }
            } else if (profile.secret.isBlank()) {
                errors += when (profile.protocol) {
                    VpnProtocol.SSH -> "SSH password or private key is required."
                    VpnProtocol.VLESS, VpnProtocol.VMESS -> "The UUID / user ID is required."
                    else -> "The password is required."
                }
            }
            if (profile.protocol != VpnProtocol.SSH && profile.sshPayloadEnabled && profile.sshPayload.isBlank()) {
                errors += "Payload cannot be empty when Payload is enabled."
            }
            if (profile.protocol == VpnProtocol.SHADOWSOCKS && profile.method.isBlank()) {
                errors += "Choose a Shadowsocks encryption method."
            }
            if (profile.security == Security.REALITY) {
                if (profile.publicKey.isBlank()) errors += "REALITY requires a public key."
                if (profile.sni.isBlank()) warnings += "REALITY usually needs a server name (SNI)."
                if (profile.shortId.isBlank()) warnings += "REALITY short ID is empty."
            }
            if (profile.transport == Transport.WEBSOCKET && !profile.path.startsWith("/")) {
                warnings += "WebSocket paths normally start with /."
            }
            if (profile.allowInsecure) warnings += "Certificate verification is disabled for this server."
        }
        try {
            val built = build(profile, settings, 7, null)
            val root = JSONObject(built.json)
            val outbounds = root.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) errors += "The configuration has no outbound server."
            if (outbounds != null && (0 until outbounds.length()).none {
                    val protocol = outbounds.optJSONObject(it)?.optString("protocol").orEmpty()
                    protocol.isNotBlank() && protocol !in setOf("freedom", "blackhole", "dns")
                }
            ) {
                errors += "The configuration needs at least one proxy outbound."
            }
            val inbounds = root.optJSONArray("inbounds")
            if (inbounds == null || (0 until inbounds.length()).none {
                    inbounds.optJSONObject(it)?.optString("protocol") == "tun"
                }
            ) {
                errors += "The configuration is missing a TUN inbound."
            }
        } catch (t: Throwable) {
            errors += t.message?.takeIf { it.isNotBlank() } ?: "The configuration could not be parsed."
        }
        return ValidationResult(errors.distinct(), warnings.distinct())
    }

    private fun guidedRoot(
        profile: VpnProfile,
        settings: AppSettings,
        tunFd: Int,
        socksPort: Int,
        token: String,
        logDirectory: File?,
        proxyPortOverride: Int?,
        payloadBridgePort: Int?
    ): JSONObject {
        val proxy = outboundFor(profile, proxyPortOverride ?: socksPort, payloadBridgePort).put("tag", "proxy")
        val root = JSONObject()
            .put("log", logObject(settings, logDirectory))
            .put("inbounds", JSONArray().apply {
                put(tunInbound(settings, tunFd))
                put(socksInbound(socksPort, token))
            })
            .put("outbounds", JSONArray().apply {
                put(proxy)
                put(freedomOutbound())
                put(dnsOutbound())
                put(blackholeOutbound())
            })
            .put("dns", dnsObject(settings))
            .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", JSONArray().apply {
                put(JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(TUN_TAG))
                    .put("port", "53")
                    .put("outboundTag", DNS_TAG))
                put(JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray(privateCidrs))
                    .put("outboundTag", DIRECT_TAG))
                put(JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "proxy"))
            }))
            .put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
        return root
    }

    private fun customRoot(
        raw: String,
        settings: AppSettings,
        tunFd: Int,
        socksPort: Int,
        token: String,
        logDirectory: File?
    ): JSONObject {
        val parsed = JSONObject(raw)
        val root = if (parsed.has("outbounds")) parsed else JSONObject()
            .put("outbounds", JSONArray().put(parsed))
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("The JSON configuration has no outbounds array.")
        if (outbounds.length() == 0) throw IllegalArgumentException("The JSON configuration has no outbounds.")

        var firstTag: String? = null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("tag").isBlank()) outbound.put("tag", if (i == 0) "proxy" else "outbound-$i")
            val protocol = outbound.optString("protocol").orEmpty()
            if (firstTag == null && protocol !in setOf("freedom", "blackhole", "dns")) {
                firstTag = outbound.optString("tag", "proxy")
            }
        }
        val proxyTag = firstTag ?: throw IllegalArgumentException(
            "The JSON configuration needs at least one proxy outbound (VLESS, VMess, Trojan, or Shadowsocks)."
        )

        if (!root.has("log")) root.put("log", logObject(settings, logDirectory))
        if (!root.has("dns")) root.put("dns", dnsObject(settings))
        if (!root.has("routing")) {
            root.put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", JSONArray().apply {
                put(JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(TUN_TAG))
                    .put("port", "53")
                    .put("outboundTag", ensureDnsOutbound(outbounds)))
                put(JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray(privateCidrs))
                    .put("outboundTag", ensureOutbound(outbounds, DIRECT_TAG, freedomOutbound())))
                put(JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", proxyTag))
            }))
        }

        root.put("inbounds", JSONArray().apply {
            put(tunInbound(settings, tunFd))
            put(socksInbound(socksPort, token))
        })
        val env = root.optJSONObject("env") ?: JSONObject()
        env.put("xray.tun.fd", tunFd.toString())
        root.put("env", env)
        return root
    }

    private fun outboundFor(profile: VpnProfile, sshProxyPort: Int, payloadBridgePort: Int? = null): JSONObject {
        val outbound = JSONObject()
        when (profile.protocol) {
            VpnProtocol.SSH -> {
                outbound.put("protocol", "socks")
                    .put("settings", JSONObject().put("servers", JSONArray().put(
                        JSONObject()
                            .put("address", "127.0.0.1")
                            .put("port", sshProxyPort)
                            .put("users", JSONArray())
                    )))
            }
            VpnProtocol.VLESS -> {
                outbound.put("protocol", "vless")
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject()
                        .put("address", if (payloadBridgePort != null) "127.0.0.1" else profile.server)
                        .put("port", payloadBridgePort ?: profile.port)
                        .put("users", JSONArray().put(JSONObject()
                            .put("id", profile.secret)
                            .put("encryption", "none")
                            .apply { if (profile.flow.isNotBlank()) put("flow", profile.flow) }
                        ))
                )))
            }
            VpnProtocol.VMESS -> {
                outbound.put("protocol", "vmess")
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject()
                        .put("address", if (payloadBridgePort != null) "127.0.0.1" else profile.server)
                        .put("port", payloadBridgePort ?: profile.port)
                        .put("users", JSONArray().put(JSONObject()
                            .put("id", profile.secret)
                            .put("alterId", profile.alterId)
                            .put("security", "auto")
                        ))
                )))
            }
            VpnProtocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject()
                        .put("address", if (payloadBridgePort != null) "127.0.0.1" else profile.server)
                        .put("port", payloadBridgePort ?: profile.port)
                        .put("password", profile.secret)
                )))
            }
            VpnProtocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject()
                        .put("address", if (payloadBridgePort != null) "127.0.0.1" else profile.server)
                        .put("port", payloadBridgePort ?: profile.port)
                        .put("method", profile.method)
                        .put("password", profile.secret)
                )))
            }
        }
        if (profile.muxEnabled) {
            outbound.put("mux", JSONObject()
                .put("enabled", true)
                .put("concurrency", profile.muxConcurrency.coerceIn(1, 128)))
        }
        if (profile.protocol == VpnProtocol.SSH) return outbound

        val payloadCarrier = payloadBridgePort != null
        val stream = JSONObject().put("network", if (payloadCarrier) "tcp" else profile.transport.wireName)
        if (payloadCarrier) stream.put("security", "none")
        if (!payloadCarrier && profile.security != Security.NONE) {
            stream.put("security", profile.security.wireName)
            if (profile.security == Security.TLS) {
                stream.put("tlsSettings", JSONObject()
                    .put("serverName", profile.sni.ifBlank { profile.server })
                    .put("allowInsecure", profile.allowInsecure)
                    .apply { if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint) }
                )
            } else {
                stream.put("realitySettings", JSONObject()
                    .put("serverName", profile.sni.ifBlank { profile.server })
                    .put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                    .put("publicKey", profile.publicKey)
                    .put("shortId", profile.shortId)
                    .put("spiderX", profile.spiderX.ifBlank { "/" })
                )
            }
        }
        when (profile.transport) {
            Transport.WEBSOCKET -> stream.put("wsSettings", JSONObject()
                .put("path", profile.path.ifBlank { "/" })
                .apply {
                    if (profile.host.isNotBlank()) put("headers", JSONObject().put("Host", profile.host))
                })
            Transport.GRPC -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", profile.serviceName)
                .put("multiMode", false))
            Transport.HTTP2 -> stream.put("httpSettings", JSONObject()
                .put("path", profile.path.ifBlank { "/" })
                .apply { if (profile.host.isNotBlank()) put("host", JSONArray().put(profile.host)) })
            Transport.HTTP_UPGRADE -> stream.put("httpupgradeSettings", JSONObject()
                .put("path", profile.path.ifBlank { "/" })
                .put("host", profile.host))
            Transport.XHTTP -> stream.put("xhttpSettings", JSONObject()
                .put("path", profile.path.ifBlank { "/" })
                .put("host", profile.host)
                .put("mode", "auto"))
            Transport.MKCP -> stream.put("kcpSettings", JSONObject()
                .put("mtu", 1350)
                .put("tti", 50)
                .put("uplinkCapacity", 5)
                .put("downlinkCapacity", 20)
                .put("congestion", false)
                .put("readBufferSize", 1)
                .put("writeBufferSize", 1)
                .put("header", JSONObject().put("type", "none")))
            Transport.TCP -> Unit
        }
        outbound.put("streamSettings", stream)
        return outbound
    }

    private fun tunInbound(settings: AppSettings, tunFd: Int): JSONObject = JSONObject()
        .put("tag", TUN_TAG)
        .put("protocol", "tun")
        .put("settings", JSONObject()
            .put("name", "v2tun0")
            .put("mtu", settings.mtu.coerceIn(1200, 1500)))

    private fun socksInbound(port: Int, token: String): JSONObject = JSONObject()
        .put("tag", SOCKS_TAG)
        .put("listen", "127.0.0.1")
        .put("port", port)
        .put("protocol", "socks")
        .put("settings", JSONObject()
            .put("auth", "password")
            .put("users", JSONArray().put(JSONObject()
                .put("user", "v2tun")
                .put("pass", token)))
            .put("udp", true))

    private fun freedomOutbound(): JSONObject = JSONObject()
        .put("tag", DIRECT_TAG)
        .put("protocol", "freedom")
        .put("settings", JSONObject().put("domainStrategy", "UseIP"))

    private fun dnsOutbound(): JSONObject = JSONObject()
        .put("tag", DNS_TAG)
        .put("protocol", "dns")

    private fun blackholeOutbound(): JSONObject = JSONObject()
        .put("tag", BLOCK_TAG)
        .put("protocol", "blackhole")

    private fun ensureOutbound(outbounds: JSONArray, tag: String, prototype: JSONObject): String {
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            if (item.optString("tag") == tag) return tag
        }
        outbounds.put(prototype)
        return tag
    }

    private fun ensureDnsOutbound(outbounds: JSONArray): String {
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            if (item.optString("protocol") == "dns") {
                val tag = item.optString("tag").ifBlank { DNS_TAG }
                if (item.optString("tag").isBlank()) item.put("tag", tag)
                return tag
            }
        }
        outbounds.put(dnsOutbound())
        return DNS_TAG
    }

    private fun dnsObject(settings: AppSettings): JSONObject {
        val servers = JSONArray()
        listOf(settings.dnsPrimary, settings.dnsSecondary)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { value ->
                servers.put(if (value.equals("local", ignoreCase = true)) "localhost" else value)
            }
        if (servers.length() == 0) {
            servers.put("localhost")
        }
        return JSONObject().put("servers", servers)
    }

    private fun logObject(settings: AppSettings, directory: File?): JSONObject {
        val log = JSONObject()
            .put("loglevel", settings.logLevel.wireName)
            .put("access", "none")
        directory?.let {
            runCatching { it.mkdirs() }
            log.put("error", File(it, "xray-error.log").absolutePath)
        }
        return log
    }

    private fun findFreePort(): Int {
        repeat(10) {
            runCatching {
                ServerSocket(0).use { socket -> return socket.localPort }
            }
        }
        return (20000..60000).random()
    }

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64Codec.encodeUrlSafe(data)
    }
}
