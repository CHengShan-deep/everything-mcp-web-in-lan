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
        context.getSharedPreferences("host", Context.MODE_PRIVATE).edit()
            .putLong("lastAutostartAt", System.currentTimeMillis())
            .putString("lastAutostartAction", intent.action)
            .apply()
        runCatching { HostService.start(context) }
            .onFailure { HostState.lastServiceError = "autostart failed: ${it.message}" }
    }
}
