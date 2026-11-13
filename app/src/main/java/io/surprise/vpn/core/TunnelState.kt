package io.surprise.vpn.core

import io.surprise.vpn.data.model.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TunnelSnapshot(
    val status: ConnectionStatus = ConnectionStatus.STOPPED,
    val profileId: String? = null,
    val profileName: String = "",
    val endpoint: String = "",
    val protocolLabel: String = "",
    val connectedAt: Long? = null,
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    val latencyMs: Int? = null,
    val socksPort: Int? = null,
    val socksUser: String? = null,
    val socksPassword: String? = null,
    val error: String? = null,
    val networkLabel: String = "",
    val coreVersion: String = "26.11.9",
    val tunName: String? = null
) {
    val isBusy: Boolean
        get() = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING
    val isActive: Boolean
        get() = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CONNECTING
}

object TunnelState {
    private val _state = MutableStateFlow(TunnelSnapshot())
    val state: StateFlow<TunnelSnapshot> = _state.asStateFlow()

    fun update(transform: (TunnelSnapshot) -> TunnelSnapshot) {
        _state.update(transform)
    }

    fun clearConnection() {
        _state.update { current ->
            current.copy(
                status = ConnectionStatus.STOPPED,
                connectedAt = null,
                downloadBytes = 0L,
                uploadBytes = 0L,
                latencyMs = null,
                socksPort = null,
                socksUser = null,
                socksPassword = null,
                error = null,
                tunName = null
            )
        }
    }

    fun fail(message: String) {
        _state.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                connectedAt = null,
                error = message,
                socksPort = null,
                socksUser = null,
                socksPassword = null,
                tunName = null
            )
        }
    }
}
