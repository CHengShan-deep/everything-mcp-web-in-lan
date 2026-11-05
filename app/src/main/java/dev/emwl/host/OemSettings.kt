package dev.emwl.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Autostart after reboot is an OEM extension with no standard API. Try the known settings
 * screens and fall back to the app details page, where the same switch usually lives.
 */
object OemSettings {

    private val AUTOSTART_SCREENS = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    )

    fun vendor(): String = Build.MANUFACTURER.orEmpty().ifEmpty { Build.BRAND.orEmpty() }

    fun openAutostartSettings(context: Context) {
        for ((pkg, activity) in AUTOSTART_SCREENS) {
            val intent = Intent().setComponent(ComponentName(pkg, activity))
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                    return
                }
            }
        }
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
