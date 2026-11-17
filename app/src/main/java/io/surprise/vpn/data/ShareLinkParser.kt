package io.surprise.vpn.data

import io.surprise.vpn.data.model.Security
import io.surprise.vpn.data.model.Transport
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import io.surprise.vpn.util.Base64Codec
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

object ShareLinkParser {

    fun parse(input: String): List<VpnProfile> {
        val text = input.trim()
        if (text.isBlank()) return emptyList()
        if (text.startsWith("{") || text.startsWith("[")) {
            return listOfNotNull(parseJsonConfig(text))
        }

        var links = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (links.size == 1 && !links.first().contains("://")) {
            decodeBase64(links.first())?.let { decoded ->
                links = decoded.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            }
        }
        return links.mapNotNull { link -> runCatching { parseLink(link) }.getOrNull() }
    }

    fun parseLink(link: String): VpnProfile? = when {
        link.startsWith("ssh://", true) -> parseSsh(link)
        link.startsWith("vless://", true) -> parseVless(link)
        link.startsWith("vmess://", true) -> parseVmess(link)
        link.startsWith("trojan://", true) -> parseTrojan(link)
        link.startsWith("ss://", true) -> parseShadowsocks(link)
        else -> null
    }

    fun parseJsonConfig(text: String): VpnProfile? = runCatching {
        val root = JSONObject(text)
        if (root.optString("protocol").equals("ssh", ignoreCase = true) || root.has("ssh")) {
            return@runCatching parseSshJson(root)
        }
        val outbound = if (root.has("outbounds")) {
            root.optJSONArray("outbounds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol") !in listOf("freedom", "blackhole", "dns") }
            }
        } else root
        val protocol = outbound?.optString("protocol").orEmpty()
        if (outbound == null) return@runCatching null
        if (protocol.lowercase() !in setOf("vless", "vmess", "trojan", "shadowsocks")) {
            return@runCatching null
        }
        val settings = outbound?.optJSONObject("settings")
        var server = ""
        var port = 443
        var secret = ""
        when (protocol.lowercase()) {
            "vless", "vmess" -> {
                val node = settings?.optJSONArray("vnext")?.optJSONObject(0)
                server = node?.optString("address").orEmpty()
                port = node?.optInt("port", 443) ?: 443
                secret = node?.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty()
            }
            "trojan" -> {
                val node = settings?.optJSONArray("servers")?.optJSONObject(0)
                server = node?.optString("address").orEmpty()
                port = node?.optInt("port", 443) ?: 443
                secret = node?.optString("password").orEmpty()
            }
            "shadowsocks" -> {
                val node = settings?.optJSONArray("servers")?.optJSONObject(0)
                server = node?.optString("address").orEmpty()
                port = node?.optInt("port", 8388) ?: 8388
                secret = node?.optString("password").orEmpty()
            }
            else -> Unit
        }
        if (server.isBlank()) return@runCatching null
        val stream = outbound?.optJSONObject("streamSettings")
        val network = stream?.optString("network").orEmpty()
        val security = when (stream?.optString("security")) {
            "reality" -> Security.REALITY
            "tls" -> Security.TLS
            else -> Security.NONE
        }
        val tls = stream?.optJSONObject("tlsSettings")
        val reality = stream?.optJSONObject("realitySettings")
        VpnProfile(
            id = UUID.randomUUID().toString(),
            name = "Imported ${protocol.ifBlank { "Xray" }.uppercase()}${if (server.isNotBlank()) " · $server" else ""}".take(48),
            protocol = VpnProtocol.from(protocol),
            server = server,
            port = port,
            secret = secret,
            method = settings?.optJSONArray("servers")?.optJSONObject(0)?.optString("method").orEmpty().ifBlank { "aes-256-gcm" },
            transport = Transport.from(network),
            security = security,
            sni = (reality?.optString("serverName") ?: tls?.optString("serverName")).orEmpty(),
            host = when (network) {
                "ws" -> stream?.optJSONObject("wsSettings")?.optJSONObject("headers")?.optString("Host").orEmpty()
                "grpc" -> ""
                "h2" -> stream?.optJSONObject("httpSettings")?.optJSONArray("host")?.optString(0).orEmpty()
                "httpupgrade" -> stream?.optJSONObject("httpupgradeSettings")?.optString("host").orEmpty()
                "xhttp" -> stream?.optJSONObject("xhttpSettings")?.optString("host").orEmpty()
                else -> ""
            },
            path = when (network) {
                "ws" -> stream?.optJSONObject("wsSettings")?.optString("path").orEmpty()
                "h2" -> stream?.optJSONObject("httpSettings")?.optString("path").orEmpty()
                "httpupgrade" -> stream?.optJSONObject("httpupgradeSettings")?.optString("path").orEmpty()
                "xhttp" -> stream?.optJSONObject("xhttpSettings")?.optString("path").orEmpty()
                else -> "/"
            }.ifBlank { "/" },
            serviceName = stream?.optJSONObject("grpcSettings")?.optString("serviceName").orEmpty(),
            flow = settings?.optJSONArray("vnext")?.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)?.optString("flow").orEmpty(),
            fingerprint = (reality?.optString("fingerprint") ?: tls?.optString("fingerprint")).orEmpty().ifBlank { "chrome" },
            publicKey = reality?.optString("publicKey").orEmpty(),
            shortId = reality?.optString("shortId").orEmpty(),
            spiderX = reality?.optString("spiderX").orEmpty().ifBlank { "/" },
            allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
            muxEnabled = outbound?.optJSONObject("mux")?.optBoolean("enabled", false) ?: false,
            muxConcurrency = outbound?.optJSONObject("mux")?.optInt("concurrency", 8) ?: 8,
            jsonConfig = text
        )
    }.getOrNull()

    fun toShareLink(profile: VpnProfile): String {
        profile.jsonConfig?.takeIf { it.isNotBlank() }?.let { return it }
        return when (profile.protocol) {
            VpnProtocol.SSH -> buildSsh(profile)
            VpnProtocol.VLESS -> buildVless(profile)
            VpnProtocol.VMESS -> buildVmess(profile)
            VpnProtocol.TROJAN -> buildTrojan(profile)
            VpnProtocol.SHADOWSOCKS -> buildShadowsocks(profile)
        }
    }

    private fun parseSsh(link: String): VpnProfile {
        val body = link.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val queryText = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?')
        val at = authority.lastIndexOf('@')
        require(at > 0) { "Missing SSH credentials" }
        val credentials = authority.substring(0, at)
        val hostPort = parseHostPort(authority.substring(at + 1))
        val query = parseQuery(queryText)
        return VpnProfile(
            name = decode(fragment).ifBlank { "SSH · ${hostPort.first}" },
            protocol = VpnProtocol.SSH,
            server = hostPort.first,
            port = hostPort.second,
            host = decode(credentials.substringBefore(':')),
            secret = decode(credentials.substringAfter(':', "")),
            sshPayloadEnabled = query["payload"] == "1",
            sshTlsEnabled = query["tls"] == "1",
            sshPayload = decode(query["payloadText"].orEmpty()),
            sshRemoteProxy = decode(query["proxy"].orEmpty()),
            sni = decode(query["sni"].orEmpty()),
            sshUdpGwPort = query["udpgw"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 7300,
            sshTcpFastOpen = query["tfo"] == "1"
        )
    }

    private fun parseSshJson(root: JSONObject): VpnProfile {
        val source = root.optJSONObject("ssh") ?: root
        val server = firstString(source, "server", "sshServer", "hostname", "address")
            .ifBlank { source.optString("host").takeIf { source.has("username") || source.has("user") }.orEmpty() }
        val username = firstString(source, "username", "user", "sshUsername")
        val password = firstString(source, "password", "pass", "secret", "sshPassword")
        val payload = firstString(source, "payload", "customPayload", "sshPayload")
        val proxy = firstString(source, "proxy", "remoteProxy", "httpProxy", "sshRemoteProxy")
        val tls = source.optBoolean("tls", false) ||
            source.optBoolean("ssl", false) ||
            source.optBoolean("tlsEnabled", false)
        val port = source.optInt("port", source.optInt("sshPort", 22)).coerceIn(1, 65535)
        val udpGwPort = source.optInt("udpGwPort", source.optInt("sshUdpGwPort", 7300)).coerceIn(1, 65535)
        return VpnProfile(
            name = firstString(source, "name", "profileName").ifBlank { "SSH · $server" },
            protocol = VpnProtocol.SSH,
            server = server,
            port = port,
            host = username,
            secret = password,
            sshPayloadEnabled = source.optBoolean("payloadEnabled", payload.isNotBlank()),
            sshTlsEnabled = tls,
            sshPayload = payload.ifBlank {
                "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: keep-alive[crlf][crlf]"
            },
            sshRemoteProxy = proxy,
            sshUdpGwPort = udpGwPort,
            sshTcpFastOpen = source.optBoolean("tcpFastOpen", source.optBoolean("sshTcpFastOpen", false)),
            sshPrivateKey = firstString(source, "privateKey", "sshPrivateKey"),
            sni = firstString(source, "sni", "serverName", "frontDomain")
        )
    }

    private fun firstString(source: JSONObject, vararg keys: String): String =
        keys.asSequence()
            .map { source.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    /**
     * Share links intentionally omit the SSH private key: links get pasted into chats and
     * notes, and a private key must not travel that way. Password, UDPGW port and TCP Fast
     * Open are included so a round trip does not silently reset them.
     */
    private fun buildSsh(profile: VpnProfile): String {
        val query = linkedMapOf<String, String>()
        if (profile.sshPayloadEnabled) query["payload"] = "1"
        if (profile.sshTlsEnabled) query["tls"] = "1"
        profile.sshPayload.takeIf { it.isNotBlank() }?.let { query["payloadText"] = it }
        profile.sshRemoteProxy.takeIf { it.isNotBlank() }?.let { query["proxy"] = it }
        profile.sni.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        if (profile.sshUdpGwPort != 7300) query["udpgw"] = profile.sshUdpGwPort.toString()
        if (profile.sshTcpFastOpen) query["tfo"] = "1"
        val suffix = queryString(query).takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "ssh://${encode(profile.host)}:${encode(profile.secret)}@${formatHost(profile.server)}:${profile.port}$suffix#${encode(profile.name)}"
    }

    private fun parseVless(link: String): VpnProfile {
        val body = link.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val queryText = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?')
        val at = authority.lastIndexOf('@')
        require(at > 0) { "Missing VLESS user id" }
        val id = decode(authority.substring(0, at))
        val hostPort = parseHostPort(authority.substring(at + 1))
        val query = parseQuery(queryText)
        val security = when (query["security"]?.lowercase()) {
            "reality" -> Security.REALITY
            "tls" -> Security.TLS
            else -> Security.NONE
        }
        return VpnProfile(
            name = decode(fragment).ifBlank { "VLESS · ${hostPort.first}" },
            protocol = VpnProtocol.VLESS,
            server = hostPort.first,
            port = hostPort.second,
            secret = id,
            transport = transportFrom(query["type"]),
            security = security,
            sni = query["sni"].orEmpty(),
            host = query["host"].orEmpty(),
            path = query["path"].orEmpty().ifBlank { "/" },
            serviceName = query["serviceName"].orEmpty(),
            flow = query["flow"].orEmpty(),
            fingerprint = query["fp"].orEmpty().ifBlank { "chrome" },
            publicKey = query["pbk"].orEmpty(),
            shortId = query["sid"].orEmpty(),
            spiderX = query["spx"].orEmpty().ifBlank { "/" },
            allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1"
        )
    }

    private fun parseTrojan(link: String): VpnProfile {
        val body = link.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val queryText = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?')
        val at = authority.lastIndexOf('@')
        require(at > 0) { "Missing Trojan password" }
        val password = decode(authority.substring(0, at))
        val hostPort = parseHostPort(authority.substring(at + 1))
        val query = parseQuery(queryText)
        val security = when (query["security"]?.lowercase()) {
            "reality" -> Security.REALITY
            "none" -> Security.NONE
            else -> Security.TLS
        }
        return VpnProfile(
            name = decode(fragment).ifBlank { "Trojan · ${hostPort.first}" },
            protocol = VpnProtocol.TROJAN,
            server = hostPort.first,
            port = hostPort.second,
            secret = password,
            transport = transportFrom(query["type"]),
            security = security,
            sni = query["sni"].orEmpty(),
            host = query["host"].orEmpty(),
            path = query["path"].orEmpty().ifBlank { "/" },
            serviceName = query["serviceName"].orEmpty(),
            fingerprint = query["fp"].orEmpty().ifBlank { "chrome" },
            publicKey = query["pbk"].orEmpty(),
            shortId = query["sid"].orEmpty(),
            spiderX = query["spx"].orEmpty().ifBlank { "/" },
            allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1"
        )
    }

    private fun parseVmess(link: String): VpnProfile {
        val encoded = link.substringAfter("://").substringBefore('#')
        val json = JSONObject(decodeBase64(encoded) ?: error("Invalid VMess payload"))
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        val transport = transportFrom(json.optString("net"))
        val tls = json.optString("tls")
        val security = when (tls.lowercase()) {
            "reality" -> Security.REALITY
            "tls" -> Security.TLS
            else -> Security.NONE
        }
        return VpnProfile(
            name = json.optString("ps").ifBlank { "VMess · $host" },
            protocol = VpnProtocol.VMESS,
            server = host,
            port = port,
            secret = json.optString("id"),
            alterId = json.optString("aid").toIntOrNull() ?: 0,
            transport = transport,
            security = security,
            sni = json.optString("sni").ifBlank { json.optString("host") },
            host = json.optString("host"),
            path = json.optString("path").ifBlank { "/" },
            serviceName = json.optString("path"),
            fingerprint = json.optString("fp").ifBlank { "chrome" }
        )
    }

    private fun parseShadowsocks(link: String): VpnProfile {
        val body = link.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#').substringBefore('?')
        var authority = beforeFragment
        var decoded = decodeBase64(authority)
        if (decoded != null && decoded.contains('@')) {
            authority = decoded
        } else {
            val at = authority.lastIndexOf('@')
            require(at > 0) { "Invalid Shadowsocks link" }
            val userInfo = authority.substring(0, at)
            val credentials = decodeBase64(userInfo) ?: decode(userInfo)
            authority = "$credentials@${authority.substring(at + 1)}"
        }
        val at = authority.lastIndexOf('@')
        require(at > 0) { "Invalid Shadowsocks credentials" }
        val credentials = authority.substring(0, at)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        val hostPort = parseHostPort(authority.substring(at + 1))
        return VpnProfile(
            name = decode(fragment).ifBlank { "Shadowsocks · ${hostPort.first}" },
            protocol = VpnProtocol.SHADOWSOCKS,
            server = hostPort.first,
            port = hostPort.second,
            secret = password,
            method = method.ifBlank { "aes-256-gcm" },
            transport = Transport.TCP,
            security = Security.NONE
        )
    }

    private fun buildVless(profile: VpnProfile): String {
        val query = linkedMapOf(
            "encryption" to "none",
            "security" to profile.security.wireName,
            "type" to profile.transport.wireName
        )
        profile.flow.takeIf { it.isNotBlank() }?.let { query["flow"] = it }
        profile.sni.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        profile.host.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        profile.path.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        profile.serviceName.takeIf { it.isNotBlank() }?.let { query["serviceName"] = it }
        profile.fingerprint.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        profile.publicKey.takeIf { it.isNotBlank() }?.let { query["pbk"] = it }
        profile.shortId.takeIf { it.isNotBlank() }?.let { query["sid"] = it }
        profile.spiderX.takeIf { it.isNotBlank() && it != "/" }?.let { query["spx"] = it }
        if (profile.allowInsecure) query["allowInsecure"] = "1"
        return "vless://${encode(profile.secret)}@${formatHost(profile.server)}:${profile.port}?${queryString(query)}#${encode(profile.name)}"
    }

    private fun buildTrojan(profile: VpnProfile): String {
        val query = linkedMapOf("security" to profile.security.wireName, "type" to profile.transport.wireName)
        profile.sni.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        profile.host.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        profile.path.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        profile.serviceName.takeIf { it.isNotBlank() }?.let { query["serviceName"] = it }
        profile.fingerprint.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        profile.publicKey.takeIf { it.isNotBlank() }?.let { query["pbk"] = it }
        profile.shortId.takeIf { it.isNotBlank() }?.let { query["sid"] = it }
        if (profile.allowInsecure) query["allowInsecure"] = "1"
        return "trojan://${encode(profile.secret)}@${formatHost(profile.server)}:${profile.port}?${queryString(query)}#${encode(profile.name)}"
    }

    private fun buildVmess(profile: VpnProfile): String {
        val json = JSONObject()
            .put("v", "2")
            .put("ps", profile.name)
            .put("add", profile.server)
            .put("port", profile.port.toString())
            .put("id", profile.secret)
            .put("aid", profile.alterId.toString())
            .put("scy", "auto")
            .put("net", profile.transport.wireName)
            .put("type", "none")
            .put("host", profile.host)
            .put("path", profile.path)
            .put("tls", if (profile.security == Security.NONE) "" else profile.security.wireName)
            .put("sni", profile.sni)
            .put("fp", profile.fingerprint)
        return "vmess://${Base64Codec.encodeStandard(json.toString().toByteArray())}"
    }

    private fun buildShadowsocks(profile: VpnProfile): String {
        val credentials = "${profile.method}:${profile.secret}"
        val encoded = Base64Codec.encodeStandard(credentials.toByteArray())
        return "ss://$encoded@${formatHost(profile.server)}:${profile.port}#${encode(profile.name)}"
    }

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isBlank()) emptyMap() else query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }.toMap()

    private fun queryString(map: Map<String, String>): String =
        map.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    private fun parseHostPort(value: String): Pair<String, Int> {
        val clean = value.substringBefore('/')
        val host: String
        val portText: String
        if (clean.startsWith("[")) {
            host = clean.substringAfter('[').substringBefore(']')
            portText = clean.substringAfter("]:", "").ifBlank { clean.substringAfter(']').trimStart(':') }
        } else {
            host = clean.substringBeforeLast(':')
            portText = clean.substringAfterLast(':', "443")
        }
        require(host.isNotBlank()) { "Missing server host" }
        return host to (portText.toIntOrNull() ?: 443)
    }

    private fun transportFrom(value: String?): Transport = when (value?.lowercase()) {
        "ws", "websocket" -> Transport.WEBSOCKET
        "grpc" -> Transport.GRPC
        "h2", "http" -> Transport.HTTP2
        "httpupgrade" -> Transport.HTTP_UPGRADE
        "xhttp", "splithttp" -> Transport.XHTTP
        "kcp", "mkcp" -> Transport.MKCP
        else -> Transport.TCP
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val clean = value.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        Base64Codec.decode(padded)?.toString(Charsets.UTF_8)
    }.getOrNull()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun formatHost(host: String): String = if (host.contains(':')) "[$host]" else host
}
