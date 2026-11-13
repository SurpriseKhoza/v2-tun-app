package io.surprise.vpn.data

import io.surprise.vpn.core.TunnelState
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

data class ExitInfo(
    val ip: String,
    val country: String,
    val city: String,
    val organization: String,
    val viaTunnel: Boolean,
    val latencyMs: Long
)

data class DnsResult(
    val host: String,
    val addresses: List<String>,
    val latencyMs: Long
)

data class PingResult(
    val host: String,
    val port: Int,
    val latencyMs: Int
)

object Diagnostics {

    suspend fun fetchExitInfo(): ExitInfo = withContext(Dispatchers.IO) {
        val snapshot = TunnelState.state.value
        val viaTunnel = snapshot.status == ConnectionStatus.CONNECTED && snapshot.socksPort != null
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
        if (viaTunnel) {
            val port = snapshot.socksPort ?: error("The tunnel proxy is not ready.")
            val user = snapshot.socksUser ?: "v2tun"
            val password = snapshot.socksPassword ?: error("The tunnel proxy is not ready.")
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            builder.proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(user, password))
                    .build()
            }
        }
        val client = builder.build()
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url("https://ipwho.is/")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Network check returned HTTP ${response.code}.")
            response.body?.string().orEmpty()
        }
        val latency = System.currentTimeMillis() - start
        val json = runCatching { JSONObject(body) }.getOrElse {
            // A small, privacy-respecting fallback if the richer endpoint is unavailable.
            val fallback = Request.Builder().url("https://api.ipify.org?format=json").build()
            val fallbackBody = client.newCall(fallback).execute().use { it.body?.string().orEmpty() }
            JSONObject(fallbackBody)
        }
        val ip = json.optString("ip").ifBlank {
            json.optString("ip_address").ifBlank { "Unknown" }
        }
        ExitInfo(
            ip = ip,
            country = json.optString("country"),
            city = json.optString("city"),
            organization = json.optJSONObject("connection")?.optString("isp")
                ?: json.optString("org"),
            viaTunnel = viaTunnel,
            latencyMs = latency
        )
    }

    suspend fun dnsLookup(host: String): DnsResult = withContext(Dispatchers.IO) {
        val clean = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
        require(clean.isNotBlank()) { "Enter a hostname to resolve." }
        val start = System.currentTimeMillis()
        val addresses = InetAddress.getAllByName(clean).mapNotNull { it.hostAddress }.distinct()
        DnsResult(clean, addresses, System.currentTimeMillis() - start)
    }

    suspend fun tcpPing(profile: VpnProfile?): PingResult = withContext(Dispatchers.IO) {
        val target = profile ?: error("Select a configuration first.")
        require(target.server.isNotBlank() && target.port in 1..65535) {
            "This configuration does not expose a TCP endpoint."
        }
        val start = System.currentTimeMillis()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.server, target.port), 2500)
        }
        PingResult(target.server, target.port, (System.currentTimeMillis() - start).toInt())
    }
}
