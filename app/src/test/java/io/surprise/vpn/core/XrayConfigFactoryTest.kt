package io.surprise.vpn.core

import io.surprise.vpn.data.model.AppSettings
import io.surprise.vpn.data.model.Security
import io.surprise.vpn.data.model.Transport
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigFactoryTest {

    private fun sshProfile() = VpnProfile(
        name = "SSH tunnel",
        protocol = VpnProtocol.SSH,
        server = "ssh.example.com",
        port = 22,
        host = "tunnel-user",
        secret = "secret",
        sni = "cdn.example.com",
        sshPayloadEnabled = true,
        sshTlsEnabled = true,
        sshPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
    )

    @Test
    fun buildsSshProfileThroughLocalSocksBridge() {
        val built = XrayConfigFactory.build(sshProfile(), AppSettings(), tunFd = 9, proxyPortOverride = 41001)
        val root = JSONObject(built.json)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", outbound.getString("protocol"))
        assertEquals("127.0.0.1", outbound.getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getString("address"))
        assertEquals(41001, outbound.getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getInt("port"))
        assertTrue(built.socksPort in 1..65535)
        assertTrue(built.socksPort != 41001)
        assertEquals(built.socksPort, root.getJSONArray("inbounds")
            .getJSONObject(1).getInt("port"))
    }

    @Test
    fun validatesSshDependencies() {
        val noAuth = sshProfile().copy(secret = "", sshPrivateKey = "")
        assertFalse(XrayConfigFactory.validate(noAuth, AppSettings()).isValid)
        val noSni = sshProfile().copy(sni = "")
        assertFalse(XrayConfigFactory.validate(noSni, AppSettings()).isValid)
        val noPayload = sshProfile().copy(sshPayload = "")
        assertFalse(XrayConfigFactory.validate(noPayload, AppSettings()).isValid)
    }

    private fun vlessProfile() = VpnProfile(
        name = "Test node",
        protocol = VpnProtocol.VLESS,
        server = "vpn.example.com",
        port = 443,
        secret = "11111111-1111-1111-1111-111111111111",
        transport = Transport.WEBSOCKET,
        security = Security.TLS,
        sni = "cdn.example.com",
        host = "cdn.example.com",
        path = "/ws"
    )

    @Test
    fun buildsTunAndAuthenticatedLocalProxy() {
        val built = XrayConfigFactory.build(vlessProfile(), AppSettings(), tunFd = 42)
        val root = JSONObject(built.json)
        val inbounds = root.getJSONArray("inbounds")
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals("socks", inbounds.getJSONObject(1).getString("protocol"))
        assertEquals("127.0.0.1", inbounds.getJSONObject(1).getString("listen"))
        assertEquals("password", inbounds.getJSONObject(1).getJSONObject("settings").getString("auth"))
        assertEquals("v2tun", inbounds.getJSONObject(1).getJSONObject("settings")
            .getJSONArray("users").getJSONObject(0).getString("user"))
        assertEquals("42", root.getJSONObject("env").getString("xray.tun.fd"))
        assertTrue(built.socksPort in 1..65535)
        assertTrue(built.socksPassword.isNotBlank())
    }

    @Test
    fun buildsVlessTlsAndRouting() {
        val root = JSONObject(XrayConfigFactory.build(vlessProfile(), AppSettings(), 7).json)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("tls", outbound.getJSONObject("streamSettings").getString("security"))
        assertEquals("cdn.example.com", outbound.getJSONObject("streamSettings")
            .getJSONObject("tlsSettings").getString("serverName"))
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals("v2tun-dns", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("proxy", rules.getJSONObject(2).getString("outboundTag"))
    }

    @Test
    fun usesActiveDnsOverrideInsteadOfLocalhost() {
        val root = JSONObject(
            XrayConfigFactory.build(
                vlessProfile(),
                AppSettings(dnsPrimary = "local", dnsSecondary = "local"),
                tunFd = 7,
                dnsServersOverride = listOf("10.123.0.1", "2001:db8::53")
            ).json
        )
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        assertEquals("10.123.0.1", servers.getString(0))
        assertEquals("2001:db8::53", servers.getString(1))
        assertTrue((0 until servers.length()).none { servers.getString(it).contains("localhost") })
    }

    @Test
    fun localDnsFallsBackWithoutLoopbackResolver() {
        val root = JSONObject(XrayConfigFactory.build(vlessProfile(), AppSettings(dnsPrimary = "local"), 7).json)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        assertTrue((0 until servers.length()).none { servers.getString(it).equals("localhost", ignoreCase = true) })
        assertTrue(servers.length() > 0)
    }

    @Test
    fun buildsRealitySettings() {
        val profile = vlessProfile().copy(
            security = Security.REALITY,
            publicKey = "public-key",
            shortId = "12ab",
            fingerprint = "firefox"
        )
        val root = JSONObject(XrayConfigFactory.build(profile, AppSettings(), 7).json)
        val reality = root.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("public-key", reality.getString("publicKey"))
        assertEquals("12ab", reality.getString("shortId"))
        assertEquals("firefox", reality.getString("fingerprint"))
    }

    @Test
    fun injectsTunAndProxyIntoCustomJsonWithFreedomFirst() {
        val raw = """
            {
              "outbounds": [
                {"tag": "direct", "protocol": "freedom"},
                {"tag": "node", "protocol": "vless", "settings": {"vnext": [{"address": "x.example.com", "port": 443, "users": [{"id": "id"}]}]}}
              ]
            }
        """.trimIndent()
        val profile = vlessProfile().copy(jsonConfig = raw)
        val root = JSONObject(XrayConfigFactory.build(profile, AppSettings(), 11).json)
        assertEquals("tun", root.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        assertEquals("socks", root.getJSONArray("inbounds").getJSONObject(1).getString("protocol"))
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals("node", rules.getJSONObject(2).getString("outboundTag"))
        assertEquals("11", root.getJSONObject("env").getString("xray.tun.fd"))
        assertEquals(3, root.getJSONArray("outbounds").length())
    }

    @Test
    fun rejectsBlankGuidedProfile() {
        val result = XrayConfigFactory.validate(VpnProfile(), AppSettings())
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("name", ignoreCase = true) })
        assertTrue(result.errors.any { it.contains("Server", ignoreCase = true) })
        assertTrue(result.errors.any { it.contains("UUID", ignoreCase = true) })
    }

    @Test
    fun rejectsCustomJsonWithoutProxyOutbound() {
        val profile = vlessProfile().copy(jsonConfig = """{"outbounds":[{"protocol":"freedom"}]}""")
        val result = XrayConfigFactory.validate(profile, AppSettings())
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("proxy", ignoreCase = true) })
    }

    @Test
    fun acceptsValidGuidedProfile() {
        val result = XrayConfigFactory.validate(vlessProfile(), AppSettings())
        assertTrue(result.isValid)
        assertNotNull(result.warnings)
    }
}
