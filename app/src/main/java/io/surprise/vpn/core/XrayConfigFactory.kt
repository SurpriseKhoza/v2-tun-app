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

    /** Placeholder diagnostic SOCKS inbound port used in previews and validation. */
    private const val PREVIEW_SOCKS_PORT = 10808

    /** Placeholder SSH bridge port used in previews and validation. Distinct from the inbound. */
    private const val PREVIEW_BRIDGE_PORT = 10809

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
        payloadBridgePortOverride: Int? = null,
        dnsServersOverride: List<String> = emptyList()
    ): BuiltXrayConfig {
        // proxyPortOverride is an already-bound upstream bridge. Xray still
        // needs a separate local SOCKS listener for diagnostics; reusing the
        // bridge port causes "address already in use" during startup.
        val socksPort = findFreePort()
        val token = randomToken(12)
        val root = if (profile.jsonConfig.isNullOrBlank()) {
            guidedRoot(profile, settings, tunFd, socksPort, token, logDirectory, proxyPortOverride, payloadBridgePortOverride, dnsServersOverride)
        } else {
            customRoot(profile.jsonConfig, settings, tunFd, socksPort, token, logDirectory, dnsServersOverride, proxyPortOverride)
        }
        return BuiltXrayConfig(
            json = root.toString(2),
            socksPort = socksPort,
            socksUser = "v2tun",
            socksPassword = token
        )
    }

    fun preview(profile: VpnProfile, settings: AppSettings): String {
        // SSH profiles get a placeholder bridge port that differs from the inbound, so the
        // preview never shows (or seeds the JSON editor with) a self-looping SOCKS outbound.
        val bridge = if (profile.protocol == VpnProtocol.SSH) PREVIEW_BRIDGE_PORT else null
        val root = if (profile.jsonConfig.isNullOrBlank()) {
            guidedRoot(profile, settings, 7, PREVIEW_SOCKS_PORT, "preview-token", null, bridge, null, emptyList())
        } else {
            customRoot(profile.jsonConfig, settings, 7, PREVIEW_SOCKS_PORT, "preview-token", null, emptyList(), bridge)
        }
        return root.toString(2)
    }

    fun validate(profile: VpnProfile, settings: AppSettings): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (profile.name.isBlank()) errors += "Give this configuration a name."
        if (profile.sshRemoteProxy.contains('@') &&
            (profile.protocol == VpnProtocol.SSH || (!profile.isJsonBacked && profile.sshPayloadEnabled))
        ) {
            errors += "Proxy credentials (user:pass@host) are not sent. Remove them and add a Proxy-Authorization header to the payload instead."
        }
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
            // SSH profiles get a placeholder bridge here, exactly like at runtime, so a custom
            // JSON that copied the preview's loopback SOCKS port validates correctly.
            val built = build(
                profile,
                settings,
                7,
                null,
                proxyPortOverride = if (profile.protocol == VpnProtocol.SSH) PREVIEW_BRIDGE_PORT else null
            )
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
        payloadBridgePort: Int?,
        dnsServersOverride: List<String>
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
            .put("dns", dnsObject(settings, dnsServersOverride))
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
        logDirectory: File?,
        dnsServersOverride: List<String>,
        proxyPortOverride: Int?
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
        rewriteLoopbackSocksOutbound(outbounds, socksPort, proxyPortOverride, proxyTag)

        if (!root.has("log")) root.put("log", logObject(settings, logDirectory))
        if (!root.has("dns")) root.put("dns", dnsObject(settings, dnsServersOverride))
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

    /**
     * Custom JSON is commonly copied from a preview where the SOCKS outbound points at a
     * placeholder loopback port (10808 / 10809), or at the per-run diagnostic inbound port.
     * Both are routing loops or dead ends.
     *
     * - With a running SSH bridge ([bridgePort] != null), the primary proxy outbound and any
     *   loopback SOCKS outbound using one of those placeholder ports is pointed at the bridge.
     *   Other loopback SOCKS outbounds (for example a deliberate chain to another local proxy)
     *   are left alone.
     * - Without a bridge (non-SSH JSON), a loopback SOCKS outbound on one of those ports has
     *   nothing listening behind it, so fail early with an actionable error instead of starting
     *   a VPN that silently carries no traffic.
     */
    private fun rewriteLoopbackSocksOutbound(
        outbounds: JSONArray,
        localSocksPort: Int,
        bridgePort: Int?,
        proxyTag: String
    ) {
        val placeholderPorts = setOf(PREVIEW_SOCKS_PORT, PREVIEW_BRIDGE_PORT, localSocksPort)
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol") != "socks") continue
            val primary = outbound.optString("tag") == proxyTag
            val servers = outbound.optJSONObject("settings")?.optJSONArray("servers") ?: continue
            for (j in 0 until servers.length()) {
                val server = servers.optJSONObject(j) ?: continue
                val address = server.optString("address").trim().lowercase()
                val port = server.optInt("port", -1)
                val loopback = address == "127.0.0.1" || address == "localhost" || address == "::1"
                if (!loopback) continue
                val placeholder = port in placeholderPorts
                if (bridgePort != null) {
                    if (primary || placeholder) {
                        server.put("address", "127.0.0.1")
                        server.put("port", bridgePort)
                    }
                    continue
                }
                if (!placeholder) continue
                throw IllegalArgumentException(
                    "SOCKS outbound points to 127.0.0.1:$port, which is a V2-TUN placeholder port with " +
                        "nothing listening behind it. Use a guided SSH profile or point it at a running upstream proxy."
                )
            }
        }
    }

    private fun dnsObject(settings: AppSettings, overrideServers: List<String> = emptyList()): JSONObject {
        val servers = JSONArray()
        val configured = if (overrideServers.isNotEmpty()) {
            overrideServers
        } else {
            listOf(settings.dnsPrimary, settings.dnsSecondary)
        }
        configured
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("local", ignoreCase = true) && !it.equals("localhost", ignoreCase = true) }
            .distinct()
            .map(::xrayDnsServer)
            .forEach(servers::put)
        // Carrier resolvers can return incomplete answers or transient NXDOMAINs.
        // Keep the active-network DNS first for ISP-local names, then provide
        // bounded public TCP fallbacks for ordinary Internet domains.
        if (overrideServers.isNotEmpty()) {
            listOf("tcp://1.1.1.1:53", "tcp://8.8.8.8:53")
                .filterNot { fallback -> (0 until servers.length()).any { servers.optString(it) == fallback } }
                .forEach(servers::put)
        }
        if (servers.length() == 0) {
            // Never emit localhost for the VPN's resolver. Android normally
            // has no DNS daemon listening on 127.0.0.1/[::1]:53.
            servers.put("1.1.1.1")
            servers.put("8.8.8.8")
        }
        return JSONObject().put("servers", servers)
    }

    /**
     * The native SSH SOCKS bridge provides TCP CONNECT, not SOCKS UDP ASSOCIATE.
     * Use Xray's DNS-over-TCP URI form so resolver traffic can travel through
     * the same SSH channel instead of being reset by the bridge.
     */
    private fun xrayDnsServer(server: String): String {
        if (server.contains("://")) return server
        return if (server.count { it == ':' } > 1 && !server.startsWith("[")) {
            "tcp://[$server]"
        } else {
            "tcp://$server"
        }
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
