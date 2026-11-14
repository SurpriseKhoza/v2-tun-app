package io.surprise.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.surprise.vpn.MainActivity
import io.surprise.vpn.R
import io.surprise.vpn.data.AppRepository
import io.surprise.vpn.data.model.AppSettings
import io.surprise.vpn.data.model.AppRoutingMode
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libXray.DialerController
import libXray.LibXray
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class V2TunVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()
    private lateinit var repository: AppRepository
    private var startJob: Job? = null
    private var monitorJob: Job? = null
    private var logJob: Job? = null
    private var reconnectJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var currentProfile: VpnProfile? = null
    private var sshEngine: SshTunnelEngine? = null
    private var payloadEngine: PayloadTunnelEngine? = null
    private var currentSettings = AppSettings()
    private var coreLogFile: File? = null
    private var coreLogOffset = 0
    private var statsBaselineRx = 0L
    private var statsBaselineTx = 0L

    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean = runCatching {
            protect(fd.toInt())
        }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository.get(this)
        createNotificationChannel()
        TunnelLogs.app("V2-TUN service ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        val profile = repository.selectedProfile.value
        if (profile == null) {
            // startForegroundService() still requires a foreground notification,
            // even when there is no profile left to connect.
            showForeground(
                title = getString(R.string.notification_connecting),
                text = "Preparing secure tunnel",
                connectedAt = null
            )
            TunnelLogs.app("No configuration selected", "WARN")
            TunnelState.fail("Add a server configuration before connecting.")
            stopSelf()
            return START_NOT_STICKY
        }

        val snapshot = TunnelState.state.value
        if (snapshot.status == ConnectionStatus.DISCONNECTING) {
            showForeground(
                title = "Disconnecting",
                text = "The previous tunnel is still shutting down",
                connectedAt = null
            )
            return START_STICKY
        }
        if ((snapshot.status == ConnectionStatus.CONNECTED || snapshot.status == ConnectionStatus.CONNECTING) &&
            snapshot.profileId == profile.id
        ) {
            val connected = snapshot.status == ConnectionStatus.CONNECTED
            showForeground(
                title = getString(if (connected) R.string.notification_connected else R.string.notification_connecting),
                text = if (connected) {
                    "${profile.name} · ${snapshot.networkLabel.ifBlank { "Protected" }}"
                } else {
                    "${profile.name} · ${profile.endpoint}"
                },
                connectedAt = snapshot.connectedAt
            )
            return START_STICKY
        }

        showForeground(
            title = getString(R.string.notification_connecting),
            text = "${profile.name} · ${profile.endpoint}",
            connectedAt = null
        )
        startTunnel(profile)
        return START_STICKY
    }

    private fun startTunnel(profile: VpnProfile) {
        if (TunnelState.state.value.status == ConnectionStatus.CONNECTED &&
            TunnelState.state.value.profileId == profile.id
        ) return

        startJob?.cancel()
        startJob = scope.launch {
            tunnelMutex.withLock {
                try {
                    stopCoreInternal()
                    // Resolve carrier endpoints before installing the VPN route. Android may
                    // expose [::1]:53 to Go after establish(), so native payload dialing must
                    // receive an IP endpoint for the proxy socket. The original host remains
                    // available to the payload engine for Host/SNI rendering.
                    val runtimeProfile = resolveBootstrapEndpoints(profile)
                    TunnelState.update {
                        it.copy(
                            status = ConnectionStatus.CONNECTING,
                            profileId = profile.id,
                            profileName = profile.name,
                            endpoint = profile.endpoint,
                            protocolLabel = if (profile.isJsonBacked) "Xray JSON" else profile.protocol.label,
                            connectedAt = null,
                            downloadBytes = 0,
                            uploadBytes = 0,
                            latencyMs = null,
                            error = null,
                            networkLabel = activeNetworkLabel(),
                            coreVersion = XrayEngine.CORE_VERSION,
                            tunName = null
                        )
                    }
                    TunnelLogs.app("Connecting to ${profile.name} (${profile.endpoint})")
                    showForeground(
                        title = getString(R.string.notification_connecting),
                        text = "${profile.name} · ${profile.endpoint}",
                        connectedAt = null
                    )

                    currentSettings = repository.settings.value
                    val builder = Builder()
                        .setSession("V2-TUN · ${profile.name}")
                        .setMtu(currentSettings.mtu.coerceIn(1200, 1500))
                        .setBlocking(false)
                        .addAddress(TUN_IPV4_ADDRESS, 32)
                        .addRoute("0.0.0.0", 0)
                        .setConfigureIntent(configureIntent())
                    applyAppRouting(builder, currentSettings)
                    addDnsServers(builder, currentSettings)
                    if (currentSettings.ipv6Enabled) {
                        runCatching {
                            builder.addAddress(TUN_IPV6_ADDRESS, 128)
                            builder.addRoute("::", 0)
                        }.onFailure { TunnelLogs.app("IPv6 interface setup failed: ${it.message}", "WARN") }
                    }

                    val pfd = builder.establish()
                        ?: throw IllegalStateException("VPN permission was denied. Open V2-TUN and try again.")
                    tunInterface = pfd
                    currentProfile = profile
                    TunnelLogs.app("TUN interface established (fd ${pfd.fd})")

                    // Xray's Go sockets must be protected from the VPN route or they will loop.
                    LibXray.registerDialerController(dialerController)
                    // Xray resolves its own server hostname before the tunnel is up. Install a
                    // protected resolver so that bootstrap DNS cannot loop through the TUN.
                    runCatching {
                        val configured = currentSettings.dnsPrimary.trim()
                        val local = configured.isBlank() || configured.equals("local", ignoreCase = true)
                        val source = if (local) activeLocalDnsServers().firstOrNull() else configured
                        if (source.isNullOrBlank()) {
                            TunnelLogs.app("Active network did not expose a local DNS server; using Android resolver", "WARN")
                            return@runCatching
                        }
                        val resolver = runCatching {
                            InetAddress.getByName(source).hostAddress ?: source
                        }.getOrDefault(source)
                        val endpoint = if (resolver.contains(':') && !resolver.startsWith("[")) {
                            "[$resolver]:53"
                        } else {
                            "$resolver:53"
                        }
                        LibXray.setDNS(dialerController, endpoint)
                    }.onFailure {
                        TunnelLogs.app("Protected DNS resolver unavailable: ${it.message}", "WARN")
                    }

                    val logDirectory = File(filesDir, "logs").apply { mkdirs() }
                    coreLogFile = File(logDirectory, "xray-error.log")
                    runCatching { coreLogFile?.delete() }
                    coreLogOffset = 0
                    val sshProxyPort = if (runtimeProfile.protocol == io.surprise.vpn.data.model.VpnProtocol.SSH) {
                        SshTunnelEngine(runtimeProfile).also {
                            sshEngine = it
                            it.start()
                        }.localSocksPort
                    } else null
                    val payloadBridgePort = if (
                        runtimeProfile.protocol != io.surprise.vpn.data.model.VpnProtocol.SSH &&
                        !runtimeProfile.isJsonBacked && runtimeProfile.sshPayloadEnabled
                    ) {
                        PayloadTunnelEngine(runtimeProfile).also {
                            payloadEngine = it
                            it.start()
                        }.localPort
                    } else null
                    val xrayDnsServers = activeLocalDnsServers()
                    TunnelLogs.app(
                        if (xrayDnsServers.isNotEmpty()) {
                            "Xray DNS: active network (${xrayDnsServers.joinToString()})"
                        } else {
                            "Xray DNS: no active resolver reported; using safe public fallback"
                        },
                        "INFO"
                    )
                    val built = XrayConfigFactory.build(
                        runtimeProfile,
                        currentSettings,
                        pfd.fd,
                        logDirectory,
                        proxyPortOverride = sshProxyPort,
                        payloadBridgePortOverride = payloadBridgePort,
                        dnsServersOverride = xrayDnsServers
                    )
                    val response = XrayEngine.run(built.json)
                    if (!response.optBoolean("success", false)) {
                        throw IllegalStateException(XrayEngine.errorMessage(response))
                    }

                    statsBaselineRx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0)
                    statsBaselineTx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0)
                    val connectedAt = System.currentTimeMillis()
                    TunnelState.update {
                        it.copy(
                            status = ConnectionStatus.CONNECTED,
                            connectedAt = connectedAt,
                            socksPort = built.socksPort,
                            socksUser = built.socksUser,
                            socksPassword = built.socksPassword,
                            latencyMs = null,
                            error = null,
                            networkLabel = activeNetworkLabel(),
                            coreVersion = XrayEngine.CORE_VERSION
                        )
                    }
                    TunnelLogs.app("Tunnel secured through ${profile.endpoint}")
                    showForeground(
                        title = getString(R.string.notification_connected),
                        text = "${profile.name} · ${profile.endpoint}",
                        connectedAt = connectedAt
                    )
                    startCoreLogTail()
                    startMonitor()
                } catch (cancelled: CancellationException) {
                    closeTunnel()
                    throw cancelled
                } catch (t: Throwable) {
                    Log.e(TAG, "Tunnel start failed", t)
                    val message = friendlyError(t)
                    TunnelLogs.app("Connection failed: $message", "ERROR")
                    runCatching { LibXray.resetDNS() }
                    closeTunnel()
                    TunnelState.fail(message)
                    showForeground(
                        title = getString(R.string.notification_error),
                        text = message,
                        connectedAt = null
                    )
                    delay(1200)
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var tick = 0
            var lastNetwork = activeNetworkLabel()
            while (isActive) {
                val current = TunnelState.state.value
                if (current.status != ConnectionStatus.CONNECTED) break
                val profile = currentProfile
                val network = activeNetworkLabel()
                if (network != lastNetwork) {
                    TunnelLogs.app("Network changed from $lastNetwork to $network", "INFO")
                    lastNetwork = network
                    if (currentSettings.autoReconnect && profile != null) scheduleReconnect(profile)
                }
                val stats = readTunStats()
                val latency = if (tick % 8 == 0 && profile != null) {
                    tcpLatency(profile)
                } else current.latencyMs
                TunnelState.update {
                    it.copy(
                        downloadBytes = stats.first,
                        uploadBytes = stats.second,
                        latencyMs = latency,
                        networkLabel = network,
                        tunName = findTunInterfaceName()
                    )
                }
                if (tick % 6 == 0) {
                    val latest = TunnelState.state.value
                    updateForegroundNotification(latest)
                }
                tick++
                delay(1_000)
            }
        }
    }

    private fun startCoreLogTail() {
        logJob?.cancel()
        logJob = scope.launch {
            while (isActive) {
                runCatching {
                    val file = coreLogFile ?: return@runCatching
                    if (!file.exists()) return@runCatching
                    if (file.length() > MAX_CORE_LOG_BYTES) {
                        file.writeText("")
                        coreLogOffset = 0
                    }
                    val text = file.readText()
                    if (text.length < coreLogOffset) coreLogOffset = 0
                    if (text.length > coreLogOffset) {
                        text.substring(coreLogOffset)
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { TunnelLogs.core(it) }
                        coreLogOffset = text.length
                    }
                }
                delay(1500)
            }
        }
    }

    private fun readTunStats(): Pair<Long, Long> {
        val name = findTunInterfaceName()
        if (name != null) {
            val rx = readSysfsStat(name, "rx_bytes")
            val tx = readSysfsStat(name, "tx_bytes")
            if (rx != null && tx != null) return rx to tx
        }
        // Fallback for devices that hide per-interface counters from apps.
        val totalRx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val totalTx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        return (totalRx - statsBaselineRx).coerceAtLeast(0) to
            (totalTx - statsBaselineTx).coerceAtLeast(0)
    }

    private fun readSysfsStat(name: String, key: String): Long? = runCatching {
        File("/sys/class/net/$name/statistics/$key").readText().trim().toLong()
    }.getOrNull()

    private fun findTunInterfaceName(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        Collections.list(interfaces).firstOrNull { network ->
            Collections.list(network.inetAddresses).any { address ->
                address.hostAddress == TUN_IPV4_ADDRESS || address.hostAddress == TUN_IPV6_ADDRESS
            }
        }?.name
    }.getOrNull()

    private fun tcpLatency(profile: VpnProfile): Int? {
        if (profile.server.isBlank() || profile.port !in 1..65535) return null
        return runCatching {
            val start = SystemClock.elapsedRealtime()
            val socket = Socket()
            protect(socket)
            socket.use {
                it.connect(InetSocketAddress(profile.server, profile.port), 1800)
                (SystemClock.elapsedRealtime() - start).toInt()
            }
        }.getOrNull()
    }

    private fun activeNetworkLabel(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Network"
            }
        }
        "Network"
    }.getOrDefault("Network")

    private fun scheduleReconnect(profile: VpnProfile) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val attempts = currentSettings.reconnectAttempts.coerceIn(1, 10)
            repeat(attempts) { attempt ->
                if (!isActive) return@launch
                delay((attempt + 1) * 1_000L)
                tunnelMutex.withLock { stopCoreInternal() }
                TunnelLogs.app("Reconnecting after network change (${attempt + 1}/$attempts)", "INFO")
                startTunnel(profile)
                repeat(20) {
                    delay(500)
                    if (TunnelState.state.value.status == ConnectionStatus.CONNECTED) return@launch
                }
            }
            TunnelLogs.app("Automatic reconnect attempts exhausted", "ERROR")
        }
    }

    private fun resolveBootstrapEndpoints(profile: VpnProfile): VpnProfile {
        fun resolve(host: String): String {
            if (host.isBlank() || host.toDoubleOrNull() != null || host.contains(':')) return host
            return runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress ?: host }
                .onSuccess { resolved ->
                    if (resolved != host) TunnelLogs.app("Bootstrap DNS: $host → $resolved", "INFO")
                }
                .getOrDefault(host)
        }

        val proxy = profile.sshRemoteProxy.trim()
        if (proxy.isBlank()) return profile
        val scheme = when {
            proxy.startsWith("http://", true) -> "http://"
            proxy.startsWith("https://", true) -> "https://"
            else -> ""
        }
        val withoutScheme = proxy.removePrefix(scheme).substringBefore('/').substringAfterLast('@')
        val separator = withoutScheme.lastIndexOf(':')
        val host = if (separator > 0) withoutScheme.substring(0, separator) else withoutScheme
        val port = if (separator > 0) withoutScheme.substring(separator + 1) else "8080"
        val resolvedHost = resolve(host)
        val resolvedProxy = "$resolvedHost:$port"
        return profile.copy(
            sshRemoteProxy = resolvedProxy
        )
    }

    private fun addDnsServers(builder: Builder, settings: AppSettings) {
        val configured = listOf(settings.dnsPrimary, settings.dnsSecondary)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val servers = if (configured.any { it.equals("local", ignoreCase = true) }) {
            activeLocalDnsServers()
        } else {
            configured
        }.distinct()
        var added = false
        servers.forEach { server ->
            runCatching {
                builder.addDnsServer(server)
                added = true
            }
        }
        if (!added) {
            TunnelLogs.app("No local DNS server was available for the VPN interface", "WARN")
        }
    }

    private fun activeLocalDnsServers(): List<String> = runCatching {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return emptyList()
        connectivity.getLinkProperties(network)?.dnsServers
            ?.mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun applyAppRouting(builder: Builder, settings: AppSettings) {
        val packages = settings.appRoutingPackages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        when (settings.appRoutingMode) {
            AppRoutingMode.ALL -> runCatching { builder.addDisallowedApplication(packageName) }
            AppRoutingMode.ALLOWLIST -> packages.forEach { app ->
                runCatching { builder.addAllowedApplication(app) }
                    .onFailure { TunnelLogs.app("Skipping unavailable allowed app $app", "WARN") }
            }
            AppRoutingMode.BLOCKLIST -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                packages.forEach { app ->
                    runCatching { builder.addDisallowedApplication(app) }
                        .onFailure { TunnelLogs.app("Skipping unavailable blocked app $app", "WARN") }
                }
            }
        }
    }

    private fun configureIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "io.surprise.vpn.OPEN_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopTunnel() {
        if (TunnelState.state.value.status == ConnectionStatus.STOPPED) {
            stopSelf()
            return
        }
        TunnelState.update { it.copy(status = ConnectionStatus.DISCONNECTING, error = null) }
        TunnelLogs.app("Disconnecting tunnel")
        startJob?.cancel()
        reconnectJob?.cancel()
        scope.launch {
            tunnelMutex.withLock {
                stopCoreInternal()
                withContext(Dispatchers.Main) {
                    ServiceCompat.stopForeground(this@V2TunVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopCoreInternal() {
        monitorJob?.cancel()
        monitorJob = null
        logJob?.cancel()
        logJob = null
        runCatching { LibXray.resetDNS() }
        runCatching { XrayEngine.stop() }
        runCatching { sshEngine?.close() }
        sshEngine = null
        runCatching { payloadEngine?.close() }
        payloadEngine = null
        closeTunnel()
        TunnelState.clearConnection()
    }

    private fun closeTunnel() {
        runCatching { tunInterface?.close() }
        tunInterface = null
        currentProfile = null
    }

    override fun onRevoke() {
        TunnelLogs.app("VPN permission revoked by the system", "WARN")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        startJob?.cancel()
        monitorJob?.cancel()
        logJob?.cancel()
        reconnectJob?.cancel()
        runCatching { LibXray.resetDNS() }
        runCatching { XrayEngine.stop() }
        runCatching { sshEngine?.close() }
        sshEngine = null
        runCatching { payloadEngine?.close() }
        payloadEngine = null
        closeTunnel()
        TunnelState.clearConnection()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, connectedAt: Long?): Notification {
        val stopIntent = Intent(this, V2TunVpnService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(ContextCompat.getColor(this, R.color.v2tun_accent))
            .setContentTitle("V2-TUN · $title")
            .setContentText(text)
            .setContentIntent(configureIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_tile, getString(R.string.notification_disconnect), stopPending)
        if (connectedAt != null) {
            builder.setUsesChronometer(true)
            builder.setWhen(connectedAt)
            builder.setShowWhen(true)
        }
        return builder.build()
    }

    private fun showForeground(title: String, text: String, connectedAt: Long?) {
        val notification = buildNotification(title, text, connectedAt)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            Log.e(TAG, "startForeground failed", it)
            TunnelLogs.app("Could not start the foreground service: ${it.message}", "ERROR")
        }
    }

    private fun updateForegroundNotification(snapshot: TunnelSnapshot) {
        if (snapshot.status != ConnectionStatus.CONNECTED) return
        if (!currentSettings.notificationsEnabled) return
        val metrics = "${formatBytes(snapshot.downloadBytes)} down · ${formatBytes(snapshot.uploadBytes)} up"
        val text = "${snapshot.profileName} · ${snapshot.networkLabel} · $metrics"
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_connected), text, snapshot.connectedAt)
            )
        }
    }

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("permission", ignoreCase = true) -> "Android denied VPN permission. Try connecting again from the app."
            raw.contains("address already in use", ignoreCase = true) -> "A local tunnel port was already in use. Try again."
            raw.isBlank() -> "The tunnel could not start. Check the server configuration and try again."
            else -> raw.take(240)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format("%.1f %s", value, units[index.coerceAtLeast(0)])
    }

    companion object {
        private const val TAG = "V2TunVpnService"
        private const val CHANNEL_ID = "v2tun_connection"
        private const val NOTIFICATION_ID = 2048
        private const val TUN_IPV4_ADDRESS = "10.111.222.2"
        private const val TUN_IPV6_ADDRESS = "fd00:1:fd00:1:fd00:1:fd00:1"
        private const val MAX_CORE_LOG_BYTES = 512 * 1024L
        const val ACTION_START = "io.surprise.vpn.action.START"
        const val ACTION_STOP = "io.surprise.vpn.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, V2TunVpnService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    TunnelLogs.app("Could not start the VPN service: ${it.message}", "ERROR")
                    TunnelState.fail("Android blocked the VPN service from starting. Open V2-TUN and try again.")
                }
        }

        fun stop(context: Context) {
            val intent = Intent(context, V2TunVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }.onFailure {
                context.stopService(Intent(context, V2TunVpnService::class.java))
            }
        }
    }
}
