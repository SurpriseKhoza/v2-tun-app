package io.surprise.vpn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.surprise.vpn.core.TunnelLogs
import io.surprise.vpn.core.TunnelState
import io.surprise.vpn.core.V2TunVpnService
import io.surprise.vpn.data.AppRepository
import io.surprise.vpn.data.Diagnostics
import io.surprise.vpn.data.DnsResult
import io.surprise.vpn.data.ExitInfo
import io.surprise.vpn.data.PingResult
import io.surprise.vpn.data.ShareLinkParser
import io.surprise.vpn.data.model.AppSettings
import io.surprise.vpn.data.model.VpnProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val isError: Boolean = false)

data class ImportSummary(val count: Int, val names: List<String>)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository.get(application)

    val profiles: StateFlow<List<VpnProfile>> = repository.profiles
    val selectedProfile: StateFlow<VpnProfile?> = repository.selectedProfile
    val settings: StateFlow<AppSettings> = repository.settings
    val tunnel = TunnelState.state
    val logs = TunnelLogs.entries

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _exitInfo = MutableStateFlow<ExitInfo?>(null)
    val exitInfo: StateFlow<ExitInfo?> = _exitInfo.asStateFlow()
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    private val _dnsResult = MutableStateFlow<DnsResult?>(null)
    val dnsResult: StateFlow<DnsResult?> = _dnsResult.asStateFlow()
    private val _pingResult = MutableStateFlow<PingResult?>(null)
    val pingResult: StateFlow<PingResult?> = _pingResult.asStateFlow()

    fun profile(id: String?): VpnProfile? = repository.getProfile(id)

    fun saveProfile(profile: VpnProfile): VpnProfile {
        val snapshot = TunnelState.state.value
        val isActiveProfile = snapshot.isActive && snapshot.profileId == profile.id
        val saved = repository.upsert(
            profile,
            select = !snapshot.isActive || isActiveProfile
        )
        TunnelLogs.app("Configuration saved: ${saved.name}")
        if (isActiveProfile) {
            V2TunVpnService.stop(getApplication())
            message("Profile updated. Reconnect to apply the changes.")
        } else {
            message("Configuration saved")
        }
        return saved
    }

    fun deleteProfile(id: String) {
        if (TunnelState.state.value.profileId == id && TunnelState.state.value.isActive) {
            V2TunVpnService.stop(getApplication())
        }
        if (repository.delete(id)) message("Configuration deleted")
    }

    fun duplicateProfile(id: String) {
        val copy = repository.duplicate(id, select = !TunnelState.state.value.isActive)
        if (copy != null) message("Created ${copy.name}")
    }

    fun selectProfile(id: String) {
        val snapshot = TunnelState.state.value
        if (snapshot.isActive && snapshot.profileId != id) {
            message("Disconnect the active tunnel before switching routes.", isError = true)
            return
        }
        repository.select(id)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        repository.updateSettings(transform)
    }

    fun startTunnel() {
        V2TunVpnService.start(getApplication())
    }

    fun stopTunnel() {
        V2TunVpnService.stop(getApplication())
    }

    fun importText(text: String): ImportSummary {
        val parsed = runCatching { ShareLinkParser.parse(text) }.getOrElse {
            message("Could not read that configuration.", isError = true)
            TunnelLogs.app("Configuration import failed: ${it.message ?: it::class.simpleName}", "WARN")
            return ImportSummary(0, emptyList())
        }
        if (parsed.isEmpty()) {
            message("No supported configuration was found in that text.", isError = true)
            return ImportSummary(0, emptyList())
        }
        val saved = runCatching {
            repository.upsertAll(
                parsed,
                selectFirst = !TunnelState.state.value.isActive || repository.selectedProfile.value == null
            )
        }.getOrElse {
            message("Could not save the imported configuration.", isError = true)
            TunnelLogs.app("Configuration save failed: ${it.message ?: it::class.simpleName}", "WARN")
            return ImportSummary(0, emptyList())
        }
        TunnelLogs.app("Imported ${saved.size} configuration(s)")
        message(if (saved.size == 1) "Configuration imported" else "${saved.size} configurations imported")
        return ImportSummary(saved.size, saved.map { it.name })
    }

    fun backupJson(): String = repository.exportBackup()

    fun restoreBackup(text: String, replace: Boolean): Int {
        if (replace && TunnelState.state.value.isActive) {
            V2TunVpnService.stop(getApplication())
        }
        val result = repository.restoreBackup(text, replace)
        return result.fold(
            onSuccess = {
                message(if (replace) "Backup restored" else "Backup merged")
                it
            },
            onFailure = {
                message(it.message ?: "Could not restore that backup.", isError = true)
                0
            }
        )
    }

    fun testExitInfo() {
        if (_isTesting.value) return
        _isTesting.value = true
        viewModelScope.launch {
            runCatching { Diagnostics.fetchExitInfo() }
                .onSuccess { _exitInfo.value = it }
                .onFailure { message(it.message ?: "Network check failed.", isError = true) }
            _isTesting.value = false
        }
    }

    fun lookupDns(host: String) {
        viewModelScope.launch {
            runCatching { Diagnostics.dnsLookup(host) }
                .onSuccess { _dnsResult.value = it }
                .onFailure { message(it.message ?: "DNS lookup failed.", isError = true) }
        }
    }

    fun pingServer() {
        viewModelScope.launch {
            runCatching { Diagnostics.tcpPing(selectedProfile.value) }
                .onSuccess { _pingResult.value = it }
                .onFailure { message(it.message ?: "Server check failed.", isError = true) }
        }
    }

    fun clearDiagnostics() {
        _exitInfo.value = null
        _dnsResult.value = null
        _pingResult.value = null
    }

    fun notify(text: String, isError: Boolean = false) {
        message(text, isError)
    }

    private fun message(text: String, isError: Boolean = false) {
        _messages.tryEmit(UiMessage(text, isError))
    }
}
