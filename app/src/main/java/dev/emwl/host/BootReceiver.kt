package dev.emwl.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val sites = HostState.desiredSites(context)
        if (sites <= 0) return

        runCatching { HostService.start(context, sites) }
            .onFailure { HostState.lastServiceError = "autostart failed: ${it.message}" }
    }
}
