package io.surprise.vpn.data

import android.content.Context
import io.surprise.vpn.data.model.AppSettings
import io.surprise.vpn.data.model.VpnProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AppRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("v2tun_prefs", Context.MODE_PRIVATE)
    private val profileFile = File(appContext.filesDir, "profiles.json")
    private val lock = Any()

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _selectedProfile = MutableStateFlow<VpnProfile?>(
        resolveSelected(_settings.value.lastProfileId, _profiles.value)
    )
    val selectedProfile: StateFlow<VpnProfile?> = _selectedProfile.asStateFlow()

    init {
        if (_profiles.value.isNotEmpty() && _settings.value.lastProfileId == null) {
            updateSettings { it.copy(lastProfileId = _profiles.value.first().id) }
        }
    }

    fun getProfile(id: String?): VpnProfile? =
        id?.let { profileId -> _profiles.value.firstOrNull { it.id == profileId } }

    fun upsert(profile: VpnProfile, select: Boolean = true): VpnProfile {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val normalized = profile.copy(
                name = profile.name.trim().ifBlank { "Untitled server" },
                server = profile.server.trim(),
                sni = profile.sni.trim(),
                host = profile.host.trim(),
                path = profile.path.trim().ifBlank { "/" },
                serviceName = profile.serviceName.trim(),
                sshPayload = profile.sshPayload.trim(),
                sshRemoteProxy = profile.sshRemoteProxy.trim(),
                sshPrivateKey = profile.sshPrivateKey.trim(),
                updatedAt = now
            )
            val current = _profiles.value.toMutableList()
            val index = current.indexOfFirst { it.id == normalized.id }
            if (index >= 0) current[index] = normalized else current.add(0, normalized)
            _profiles.value = current.sortedByDescending { it.updatedAt }
            persistProfiles()
            if (select) select(normalized.id) else refreshSelected()
            return normalized
        }
    }

    fun upsertAll(items: List<VpnProfile>, selectFirst: Boolean = true): List<VpnProfile> {
        if (items.isEmpty()) return emptyList()
        val saved = mutableListOf<VpnProfile>()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = _profiles.value.toMutableList()
            items.forEachIndexed { index, item ->
                val normalized = item.copy(
                    name = item.name.trim().ifBlank { "Imported server ${index + 1}" },
                    updatedAt = now + index
                )
                val existing = current.indexOfFirst { it.id == normalized.id }
                if (existing >= 0) current[existing] = normalized else current.add(0, normalized)
                saved.add(normalized)
            }
            _profiles.value = current.sortedByDescending { it.updatedAt }
            persistProfiles()
            if (selectFirst && saved.isNotEmpty()) select(saved.first().id) else refreshSelected()
        }
        return saved
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val current = _profiles.value
            if (current.none { it.id == id }) return false
            _profiles.value = current.filterNot { it.id == id }
            persistProfiles()
            val selected = _selectedProfile.value?.id
            if (selected == id) {
                val next = _profiles.value.firstOrNull()?.id
                if (next != null) select(next) else {
                    _selectedProfile.value = null
                    updateSettings { it.copy(lastProfileId = null) }
                }
            }
            return true
        }
    }

    fun duplicate(id: String, select: Boolean = true): VpnProfile? {
        val source = getProfile(id) ?: return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return upsert(copy, select = select)
    }

    fun select(id: String) {
        synchronized(lock) {
            val profile = getProfile(id) ?: return
            _selectedProfile.value = profile
            updateSettings { it.copy(lastProfileId = profile.id) }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val updated = transform(_settings.value).let {
                it.copy(
                    dnsPrimary = it.dnsPrimary.trim(),
                    dnsSecondary = it.dnsSecondary.trim(),
                    mtu = it.mtu.coerceIn(1200, 1500)
                )
            }
            _settings.value = updated
            prefs.edit().putString(KEY_SETTINGS, updated.toJson().toString()).apply()
            return updated
        }
    }

    fun exportBackup(): String {
        val root = JSONObject()
            .put("app", "V2-TUN")
            .put("version", 1)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            .put("settings", _settings.value.toJson())
            .put("profiles", JSONArray().apply {
                _profiles.value.forEach { put(it.toJson()) }
            })
        return root.toString(2)
    }

    fun restoreBackup(text: String, replace: Boolean = false): Result<Int> = runCatching {
        val root = JSONObject(text)
        val array = when {
            root.has("profiles") -> root.getJSONArray("profiles")
            root.has("configs") -> root.getJSONArray("configs")
            else -> throw IllegalArgumentException("This file does not contain a V2-TUN backup.")
        }
        val incoming = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val profile = runCatching { VpnProfile.fromJson(item) }.getOrNull() ?: continue
                if (profile.name.isNotBlank()) add(profile)
            }
        }
        if (incoming.isEmpty()) throw IllegalArgumentException("No valid configurations were found in the backup.")

        synchronized(lock) {
            if (replace) {
                _profiles.value = emptyList()
                _selectedProfile.value = null
            }
            upsertAll(incoming, selectFirst = _selectedProfile.value == null)
            root.optJSONObject("settings")?.let { settingsJson ->
                val importedSettings = AppSettings.fromJson(settingsJson)
                updateSettings {
                    importedSettings.copy(
                        lastProfileId = _selectedProfile.value?.id,
                        onboardingComplete = true
                    )
                }
            }
            incoming.size
        }
    }

    private fun refreshSelected() {
        _selectedProfile.value = resolveSelected(_settings.value.lastProfileId, _profiles.value)
    }

    private fun resolveSelected(lastId: String?, items: List<VpnProfile>): VpnProfile? =
        items.firstOrNull { it.id == lastId } ?: items.firstOrNull()

    private fun loadProfiles(): List<VpnProfile> {
        if (!profileFile.exists()) return emptyList()
        return runCatching {
            val text = profileFile.readText()
            val array = when {
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text).optJSONArray("profiles") ?: JSONArray()
            }
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { json ->
                        runCatching { VpnProfile.fromJson(json) }.getOrNull()?.let { add(it) }
                    }
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    private fun persistProfiles() {
        runCatching {
            val temp = File(profileFile.parentFile, "${profileFile.name}.tmp")
            val root = JSONObject()
                .put("version", 1)
                .put("profiles", JSONArray().apply {
                    _profiles.value.forEach { put(it.toJson()) }
                })
            temp.writeText(root.toString())
            if (!temp.renameTo(profileFile)) {
                profileFile.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching {
            AppSettings.fromJson(JSONObject(raw)).let { settings ->
                // Migrate the original untouched defaults to local/system DNS.
                if (settings.dnsPrimary == "1.1.1.1" && settings.dnsSecondary == "8.8.8.8") {
                    settings.copy(dnsPrimary = "local", dnsSecondary = "")
                } else {
                    settings
                }
            }
        }.getOrDefault(AppSettings())
    }

    companion object {
        private const val KEY_SETTINGS = "settings_json"

        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context).also { instance = it }
            }
    }
}
