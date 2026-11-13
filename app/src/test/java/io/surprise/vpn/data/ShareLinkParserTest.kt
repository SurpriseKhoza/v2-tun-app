package io.surprise.vpn.data

import io.surprise.vpn.data.model.Security
import io.surprise.vpn.data.model.Transport
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareLinkParserTest {

    @Test
    fun roundTripsSshEditorProfile() {
        val original = VpnProfile(
            name = "Payload edge",
            protocol = VpnProtocol.SSH,
            server = "ssh.example.com",
            port = 2222,
            host = "tunnel-user",
            secret = "secret",
            sni = "cdn.example.com",
            sshPayloadEnabled = true,
            sshTlsEnabled = true,
            sshPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]",
            sshRemoteProxy = "proxy.example.com:8080"
        )
        val parsed = ShareLinkParser.parse(ShareLinkParser.toShareLink(original)).single()
        assertEquals(VpnProtocol.SSH, parsed.protocol)
        assertEquals(original.server, parsed.server)
        assertEquals(original.port, parsed.port)
        assertEquals(original.host, parsed.host)
        assertEquals(original.secret, parsed.secret)
        assertTrue(parsed.sshPayloadEnabled)
        assertTrue(parsed.sshTlsEnabled)
        assertEquals(original.sshRemoteProxy, parsed.sshRemoteProxy)
    }

    @Test
    fun parsesVlessRealityWebSocketLink() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?encryption=none&security=reality&sni=cdn.example.com&fp=chrome" +
            "&pbk=public-key&sid=12ab&spx=%2F&type=ws&path=%2Fws&host=cdn.example.com#Tokyo%20Edge"
        val profile = ShareLinkParser.parse(link).single()

        assertEquals(VpnProtocol.VLESS, profile.protocol)
        assertEquals("example.com", profile.server)
        assertEquals(443, profile.port)
        assertEquals("11111111-1111-1111-1111-111111111111", profile.secret)
        assertEquals(Security.REALITY, profile.security)
        assertEquals(Transport.WEBSOCKET, profile.transport)
        assertEquals("cdn.example.com", profile.sni)
        assertEquals("cdn.example.com", profile.host)
        assertEquals("/ws", profile.path)
        assertEquals("public-key", profile.publicKey)
        assertEquals("12ab", profile.shortId)
        assertEquals("Tokyo Edge", profile.name)
    }

    @Test
    fun roundTripsVlessProfile() {
        val original = VpnProfile(
            name = "Round trip",
            protocol = VpnProtocol.VLESS,
            server = "vpn.example.com",
            port = 8443,
            secret = "11111111-1111-1111-1111-111111111111",
            transport = Transport.WEBSOCKET,
            security = Security.TLS,
            sni = "cdn.example.com",
            host = "cdn.example.com",
            path = "/socket"
        )
        val parsed = ShareLinkParser.parse(ShareLinkParser.toShareLink(original)).single()
        assertEquals(original.name, parsed.name)
        assertEquals(original.server, parsed.server)
        assertEquals(original.port, parsed.port)
        assertEquals(original.secret, parsed.secret)
        assertEquals(original.security, parsed.security)
        assertEquals(original.transport, parsed.transport)
        assertEquals(original.path, parsed.path)
    }

    @Test
    fun parsesVmessAndShadowsocks() {
        val vmessJson = JSONObject()
            .put("v", "2")
            .put("ps", "VMess node")
            .put("add", "vm.example.com")
            .put("port", "443")
            .put("id", "22222222-2222-2222-2222-222222222222")
            .put("aid", "0")
            .put("net", "ws")
            .put("host", "cdn.example.com")
            .put("path", "/vm")
            .put("tls", "tls")
            .put("sni", "cdn.example.com")
        val vmess = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toString().toByteArray())
        val vmessProfile = ShareLinkParser.parse(vmess).single()
        assertEquals(VpnProtocol.VMESS, vmessProfile.protocol)
        assertEquals("vm.example.com", vmessProfile.server)
        assertEquals("22222222-2222-2222-2222-222222222222", vmessProfile.secret)
        assertEquals(Security.TLS, vmessProfile.security)
        assertEquals(Transport.WEBSOCKET, vmessProfile.transport)

        val ssCredentials = Base64.getEncoder().encodeToString("aes-256-gcm:secret".toByteArray())
        val ss = "ss://$ssCredentials@ss.example.com:8388#SS%20node"
        val ssProfile = ShareLinkParser.parse(ss).single()
        assertEquals(VpnProtocol.SHADOWSOCKS, ssProfile.protocol)
        assertEquals("aes-256-gcm", ssProfile.method)
        assertEquals("secret", ssProfile.secret)
        assertEquals(Security.NONE, ssProfile.security)
    }

    @Test
    fun parsesTrojanGrpcLink() {
        val link = "trojan://secret@trojan.example.com:443?security=tls&type=grpc&serviceName=grpc-svc&sni=trojan.example.com#Trojan"
        val profile = ShareLinkParser.parse(link).single()
        assertEquals(VpnProtocol.TROJAN, profile.protocol)
        assertEquals(Transport.GRPC, profile.transport)
        assertEquals("grpc-svc", profile.serviceName)
        assertEquals(Security.TLS, profile.security)
    }

    @Test
    fun parsesXrayJsonOutboundAndKeepsRawConfig() {
        val json = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": {"vnext": [{"address": "json.example.com", "port": 443,
                  "users": [{"id": "33333333-3333-3333-3333-333333333333"}]}]},
                "streamSettings": {"network": "ws", "security": "tls",
                  "tlsSettings": {"serverName": "cdn.example.com"},
                  "wsSettings": {"path": "/json"}}
              }]
            }
        """.trimIndent()
        val profile = ShareLinkParser.parseJsonConfig(json)
        assertNotNull(profile)
        assertEquals(VpnProtocol.VLESS, profile!!.protocol)
        assertEquals("json.example.com", profile.server)
        assertEquals("33333333-3333-3333-3333-333333333333", profile.secret)
        assertEquals(Transport.WEBSOCKET, profile.transport)
        assertEquals("/json", profile.path)
        assertTrue(profile.isJsonBacked)
    }
}
