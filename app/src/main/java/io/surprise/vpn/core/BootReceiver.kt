package io.surprise.vpn.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.UserManager
import io.surprise.vpn.data.AppRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) return

        val repository = AppRepository.get(context)
        if (!repository.settings.value.autoConnectOnBoot) return
        if (repository.selectedProfile.value == null) return

        runCatching {
            // A VPN profile still needs the one-time system consent after a fresh install.
            if (VpnService.prepare(context) != null) {
                TunnelLogs.app("Auto-connect skipped until VPN permission is granted", "WARN")
                return@runCatching
            }
            TunnelLogs.app("Auto-connect started from system broadcast")
            V2TunVpnService.start(context)
        }.onFailure {
            TunnelLogs.app("Auto-connect could not start: ${it.message}", "WARN")
        }
    }
}
