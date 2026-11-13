package io.surprise.vpn.data.model

import org.json.JSONObject
import org.json.JSONArray

data class CustomPalette(
    val primary: String = "#2563EB",
    val secondary: String = "#10B981",
    val success: String = "#10B981",
    val alert: String = "#F43F5E",
    val lightCanvas: String = "#F8FAFC",
    val darkCanvas: String = "#0F172A"
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val themePalette: ThemePalette = ThemePalette.CYBER_TRUST,
    val customPalette: CustomPalette = CustomPalette(),
    val dynamicColor: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val autoConnectOnBoot: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val mtu: Int = 1500,
    val ipv6Enabled: Boolean = false,
    val logLevel: LogLevel = LogLevel.WARNING,
    val appRoutingMode: AppRoutingMode = AppRoutingMode.ALL,
    val appRoutingPackages: Set<String> = emptySet(),
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    val lastProfileId: String? = null,
    val onboardingComplete: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("themeMode", themeMode.name)
        put("themePalette", themePalette.name)
        put("customPalette", JSONObject().apply {
            put("primary", customPalette.primary)
            put("secondary", customPalette.secondary)
            put("success", customPalette.success)
            put("alert", customPalette.alert)
            put("lightCanvas", customPalette.lightCanvas)
            put("darkCanvas", customPalette.darkCanvas)
        })
        put("dynamicColor", dynamicColor)
        put("hapticsEnabled", hapticsEnabled)
        put("autoConnectOnBoot", autoConnectOnBoot)
        put("notificationsEnabled", notificationsEnabled)
        put("dnsPrimary", dnsPrimary)
        put("dnsSecondary", dnsSecondary)
        put("mtu", mtu)
        put("ipv6Enabled", ipv6Enabled)
        put("logLevel", logLevel.name)
        put("appRoutingMode", appRoutingMode.name)
        put("appRoutingPackages", JSONArray(appRoutingPackages.toList()))
        put("autoReconnect", autoReconnect)
        put("reconnectAttempts", reconnectAttempts)
        lastProfileId?.let { put("lastProfileId", it) }
        put("onboardingComplete", onboardingComplete)
    }

    companion object {
        fun fromJson(json: JSONObject): AppSettings = AppSettings(
            themeMode = ThemeMode.from(json.optString("themeMode")),
            themePalette = ThemePalette.from(json.optString("themePalette")),
            customPalette = json.optJSONObject("customPalette")?.let { colors ->
                CustomPalette(
                    primary = colors.optString("primary", "#2563EB"),
                    secondary = colors.optString("secondary", "#10B981"),
                    success = colors.optString("success", "#10B981"),
                    alert = colors.optString("alert", "#F43F5E"),
                    lightCanvas = colors.optString("lightCanvas", "#F8FAFC"),
                    darkCanvas = colors.optString("darkCanvas", "#0F172A")
                )
            } ?: CustomPalette(),
            dynamicColor = json.optBoolean("dynamicColor", false),
            hapticsEnabled = json.optBoolean("hapticsEnabled", true),
            autoConnectOnBoot = json.optBoolean("autoConnectOnBoot", false),
            notificationsEnabled = json.optBoolean("notificationsEnabled", true),
            dnsPrimary = json.optString("dnsPrimary", "1.1.1.1"),
            dnsSecondary = json.optString("dnsSecondary", "8.8.8.8"),
            mtu = json.optInt("mtu", 1500).coerceIn(1200, 1500),
            ipv6Enabled = json.optBoolean("ipv6Enabled", false),
            logLevel = LogLevel.from(json.optString("logLevel")),
            appRoutingMode = AppRoutingMode.from(json.optString("appRoutingMode")),
            appRoutingPackages = buildSet {
                val packages = json.optJSONArray("appRoutingPackages") ?: JSONArray()
                for (index in 0 until packages.length()) {
                    packages.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            },
            autoReconnect = json.optBoolean("autoReconnect", true),
            reconnectAttempts = json.optInt("reconnectAttempts", 3).coerceIn(0, 10),
            lastProfileId = json.optString("lastProfileId").takeIf { it.isNotBlank() },
            onboardingComplete = json.optBoolean("onboardingComplete", false)
        )
    }
}
