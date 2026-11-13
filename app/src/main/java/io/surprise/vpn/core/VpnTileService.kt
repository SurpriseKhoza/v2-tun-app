package io.surprise.vpn.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.surprise.vpn.MainActivity
import io.surprise.vpn.data.AppRepository
import io.surprise.vpn.data.model.ConnectionStatus

class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val status = TunnelState.state.value.status
        if (status == ConnectionStatus.DISCONNECTING) return
        if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CONNECTING) {
            V2TunVpnService.stop(this)
            updateTile()
            return
        }

        val repository = AppRepository.get(this)
        if (repository.selectedProfile.value == null || VpnService.prepare(this) != null) {
            openApp()
            return
        }
        runCatching { V2TunVpnService.start(this) }
            .onFailure { TunnelLogs.app("Quick Settings could not start the tunnel: ${it.message}", "WARN") }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val status = TunnelState.state.value.status
        tile.state = when (status) {
            ConnectionStatus.CONNECTED -> Tile.STATE_ACTIVE
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "V2-TUN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                ConnectionStatus.CONNECTED -> TunnelState.state.value.networkLabel.ifBlank { "Protected" }
                ConnectionStatus.CONNECTING -> "Connecting"
                ConnectionStatus.DISCONNECTING -> "Disconnecting"
                else -> "Tap to connect"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
