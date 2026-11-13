package io.surprise.vpn.data.model

enum class VpnProtocol(val label: String, val shortLabel: String) {
    SSH("SSH", "SSH"),
    VLESS("VLESS", "VLESS"),
    VMESS("VMess", "VMESS"),
    TROJAN("Trojan", "TROJAN"),
    SHADOWSOCKS("Shadowsocks", "SS");

    companion object {
        fun from(value: String?): VpnProtocol =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: VLESS
    }
}

enum class Transport(val label: String, val wireName: String) {
    TCP("TCP", "tcp"),
    WEBSOCKET("WebSocket", "ws"),
    GRPC("gRPC", "grpc"),
    HTTP2("HTTP/2", "h2"),
    HTTP_UPGRADE("HTTP Upgrade", "httpupgrade"),
    XHTTP("XHTTP", "xhttp"),
    MKCP("mKCP", "kcp");

    companion object {
        fun from(value: String?): Transport =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.wireName == value }
                ?: TCP
    }
}

enum class Security(val label: String, val wireName: String) {
    NONE("None", "none"),
    TLS("TLS", "tls"),
    REALITY("REALITY", "reality");

    companion object {
        fun from(value: String?): Security =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.wireName == value }
                ?: TLS
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light");

    companion object {
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}

enum class ThemePalette(val label: String, val description: String) {
    CYBER_TRUST("Cyber Trust", "Electric blue with emerald protection feedback"),
    STEALTH_SPEED("Stealth & Speed", "Indigo and cyan for a futuristic performance feel"),
    MINIMALIST("Minimalist", "Deep teal with restrained coral alerts"),
    OCEANIC("Oceanic", "Deep ocean blue with aqua connection highlights"),
    FOREST("Forest", "Pine green with fresh lime status feedback"),
    SUNSET("Sunset", "Warm violet and amber with a confident glow"),
    MONOCHROME("Monochrome", "Neutral graphite with crisp white contrast"),
    CUSTOM("Custom", "Build your own palette with six color controls");

    companion object {
        fun from(value: String?): ThemePalette =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CYBER_TRUST
    }
}

enum class ConnectionStatus {
    STOPPED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

enum class AppRoutingMode(val label: String) {
    ALL("All apps"),
    ALLOWLIST("Only selected apps"),
    BLOCKLIST("Exclude selected apps");

    companion object {
        fun from(value: String?): AppRoutingMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ALL
    }
}

enum class LogLevel(val label: String, val wireName: String) {
    WARNING("Warning", "warning"),
    INFO("Info", "info"),
    DEBUG("Debug", "debug"),
    NONE("Silent", "none");

    companion object {
        fun from(value: String?): LogLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WARNING
    }
}
