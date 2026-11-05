package dev.emwl.host

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import dev.emwl.host.ui.AppPrefs
import dev.emwl.host.ui.Lang
import dev.emwl.host.ui.Strings
import kotlinx.coroutines.delay

private enum class AppScreen { MAIN, SETTINGS, GUIDE }

private data class SiteRow(
    val id: String,
    val slug: String,
    val port: Int,
    val enabled: Boolean,
    val exposure: String,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleIntent(intent)
        restoreIfNeeded()

        setContent {
            var lang by remember { mutableStateOf(AppPrefs.lang(this)) }
            var screen by remember {
                mutableStateOf(if (AppPrefs.guideSeen(this)) AppScreen.MAIN else AppScreen.GUIDE)
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        AppScreen.GUIDE -> GuideScreen(
                            lang = lang,
                            onDone = {
                                AppPrefs.markGuideSeen(this)
                                screen = AppScreen.MAIN
                            },
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            lang = lang,
                            onLanguage = {
                                AppPrefs.setLang(this, it)
                                lang = it
                            },
                            onBack = { screen = AppScreen.MAIN },
                            onGuide = { screen = AppScreen.GUIDE },
                            onBatteryExemption = { requestBatteryExemption() },
                            onAutostartSettings = { OemSettings.openAutostartSettings(this) },
                            onOpenAdmin = {
                                openUrl("http://127.0.0.1:${HostState.runtime?.controlPort ?: Ports.CONTROL}/admin")
                            },
                            onOpenHub = {
                                openUrl("http://${Metrics.lanIpv4() ?: "127.0.0.1"}:${Ports.HUB}/")
                            },
                            onCopyAdmin = { copyToken(HostState.runtime?.tokens?.admin ?: "") },
                            onCopyMcp = { copyToken(HostState.runtime?.tokens?.mcp ?: "") },
                            version = versionName(),
                        )

                        AppScreen.MAIN -> HostScreen(
                            lang = lang,
                            onSettings = { screen = AppScreen.SETTINGS },
                            onStart = { HostService.start(this) },
                            onStop = { HostService.stop(this) },
                            onToggleSite = { id, enabled -> toggleSite(id, enabled) },
                            onBatteryExemption = { requestBatteryExemption() },
                            sites = { siteRows() },
                            console = { consoleText() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("stop", false)) {
            HostService.stop(this)
            return
        }
        if (intent.getBooleanExtra("autostart", false)) {
            HostService.start(this)
        }
    }

    private fun restoreIfNeeded() {
        if (!HostState.running) {
            HostService.start(this)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun copyToken(token: String) {
        if (token.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("control token", token))
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun toggleSite(id: String, enabled: Boolean) {
        val service = HostState.runtime?.service ?: return
        Thread { runCatching { service.setEnabled(id, enabled) } }.start()
    }

    private fun siteRows(): List<SiteRow> =
        HostState.runtime?.service?.listSites()?.map { site ->
            SiteRow(site.id, site.slug, site.port, site.enabled, site.exposure)
        } ?: emptyList()

    private fun versionName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    }.getOrNull() ?: "-"

    private fun consoleText(): String = buildString {
        val power = getSystemService(PowerManager::class.java)
        appendLine("sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        appendLine("lan=${Metrics.lanIpv4() ?: "-"}")
        appendLine("running=${HostState.running} sites=${HostState.siteCount}")
        appendLine("control=127.0.0.1:${HostState.runtime?.controlPort ?: -1} hub=:${Ports.HUB}")
        appendLine("error=${HostState.lastServiceError ?: "-"}")
        appendLine("batteryExempt=${power.isIgnoringBatteryOptimizations(packageName)} doze=${power.isDeviceIdleMode}")
        appendLine("pssKb=${Metrics.pssKb()} threads=${Metrics.threadCount()} fds=${Metrics.fileDescriptors()}")
        HostState.runtime?.let { runtime ->
            runCatching {
                appendLine("adminToken=${runtime.tokens.admin}")
                appendLine("mcpToken=${runtime.tokens.mcp}")
                appendLine(runtime.service.listSites().joinToString("\n") { site ->
                    "  ${site.slug} :${site.port} ${site.exposure} ${if (site.enabled) "on" else "off"} ${site.authRead}/${site.authWrite}"
                })
            }.onFailure { appendLine("diagError=${it.message}") }
        }
    }
}

/**
 * Reports what happened to the last system-triggered start. A service start shortly after the
 * boot broadcast means autostart worked; otherwise an OEM blocked it and the user had to open
 * the app, which is exactly the caveat the UI has to surface.
 */
private fun autostartSummary(context: android.content.Context, lang: Lang): String {
    val prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE)
    val attempt = prefs.getLong("lastAutostartAt", 0L)
    if (attempt == 0L) return ""
    val started = prefs.getLong("lastServiceStartAt", 0L)
    val at = android.text.format.DateFormat.format("MM-dd HH:mm", attempt)
    val source = prefs.getString("lastAutostartAction", "").orEmpty().substringAfterLast('.')
    val template = if (started in attempt..(attempt + 90_000L)) {
        Strings.autostartWorked.of(lang)
    } else {
        Strings.autostartBlocked.of(lang).replace("your vendor", OemSettings.vendor())
            .replace("厂商系统", OemSettings.vendor())
    }
    return template.replace("%1\$s", source).replace("%2\$s", at.toString())
}

@Composable
private fun HostNode(
    id: String,
    role: String,
    text: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            UiBridge.update(
                UiBridge.Node(
                    id = id,
                    role = role,
                    text = text,
                    enabled = enabled,
                    bounds = listOf(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
                    actions = if (onClick != null) listOf("press") else emptyList(),
                )
            )
            if (onClick != null) UiBridge.register(id) { onClick() }
        }
    ) { content() }
}

@Composable
private fun HostScreen(
    lang: Lang,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleSite: (String, Boolean) -> Unit,
    onBatteryExemption: () -> Unit,
    sites: () -> List<SiteRow>,
    console: () -> String,
) {
    var consoleText by remember { mutableStateOf("") }
    var batteryExempt by remember { mutableStateOf(true) }
    var autostartNote by remember { mutableStateOf("") }
    var siteList by remember { mutableStateOf(emptyList<SiteRow>()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val power = context.getSystemService(PowerManager::class.java)
        while (true) {
            consoleText = runCatching { console() }.getOrElse { "console error: ${it.message}" }
            batteryExempt = runCatching {
                power.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(true)
            autostartNote = runCatching { autostartSummary(context, lang) }.getOrDefault("")
            siteList = runCatching { sites() }.getOrDefault(emptyList())
            delay(1000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HostNode("title", "text", Strings.title.of(lang)) {
                Text(Strings.title.of(lang), style = MaterialTheme.typography.headlineSmall)
            }
            HostNode("btn-settings", "button", Strings.settings.of(lang), onClick = onSettings) {
                TextButton(onClick = onSettings) {
                    Text("?", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-start", "button", Strings.startHosting.of(lang), onClick = onStart) {
                Button(onClick = onStart) { Text(Strings.startHosting.of(lang)) }
            }
            HostNode("btn-stop", "button", Strings.stopHosting.of(lang), onClick = onStop) {
                OutlinedButton(onClick = onStop) { Text(Strings.stopHosting.of(lang)) }
            }
        }

        if (!batteryExempt) {
            HostNode("warn-battery", "status", Strings.warningBattery.of(lang), onClick = onBatteryExemption) {
                Text(
                    Strings.warningBattery.of(lang),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (autostartNote.isNotEmpty()) {
            HostNode("note-autostart", "status", autostartNote) {
                Text(autostartNote, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))
        HostNode("sites-header", "text", Strings.sites.of(lang)) {
            Text(Strings.sites.of(lang), style = MaterialTheme.typography.titleMedium)
        }

        if (siteList.isEmpty()) {
            HostNode("sites-empty", "text", Strings.noSites.of(lang)) {
                Text(Strings.noSites.of(lang), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            for (site in siteList) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    HostNode("site-${site.slug}", "text", "${site.slug} :${site.port}") {
                        Text(
                            "${site.slug} :${site.port} · ${site.exposure} · " +
                                if (site.enabled) Strings.running.of(lang) else Strings.stopped.of(lang),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HostNode(
                        id = "site-toggle-${site.slug}",
                        role = "switch",
                        text = site.slug,
                        onClick = { onToggleSite(site.id, !site.enabled) },
                    ) {
                        Switch(
                            checked = site.enabled,
                            onCheckedChange = { onToggleSite(site.id, it) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HostNode("status", "status", consoleText) {
            Text(consoleText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SettingsScreen(
    lang: Lang,
    onLanguage: (Lang) -> Unit,
    onBack: () -> Unit,
    onGuide: () -> Unit,
    onBatteryExemption: () -> Unit,
    onAutostartSettings: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenHub: () -> Unit,
    onCopyAdmin: () -> Unit,
    onCopyMcp: () -> Unit,
    version: String,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HostNode("settings-title", "text", Strings.settings.of(lang)) {
                Text(Strings.settings.of(lang), style = MaterialTheme.typography.headlineSmall)
            }
            HostNode("btn-back", "button", Strings.back.of(lang), onClick = onBack) {
                TextButton(onClick = onBack) { Text(Strings.back.of(lang)) }
            }
        }

        HostNode("settings-language", "text", Strings.language.of(lang)) {
            Text(Strings.language.of(lang), style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-lang-en", "button", Strings.english.of(lang), onClick = { onLanguage(Lang.EN) }) {
                if (lang == Lang.EN) {
                    Button(onClick = { onLanguage(Lang.EN) }) { Text(Strings.english.of(lang)) }
                } else {
                    OutlinedButton(onClick = { onLanguage(Lang.EN) }) { Text(Strings.english.of(lang)) }
                }
            }
            HostNode("btn-lang-zh", "button", Strings.chinese.of(lang), onClick = { onLanguage(Lang.ZH) }) {
                if (lang == Lang.ZH) {
                    Button(onClick = { onLanguage(Lang.ZH) }) { Text(Strings.chinese.of(lang)) }
                } else {
                    OutlinedButton(onClick = { onLanguage(Lang.ZH) }) { Text(Strings.chinese.of(lang)) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HostNode("btn-open-guide", "button", Strings.openGuide.of(lang), onClick = onGuide) {
            OutlinedButton(onClick = onGuide) { Text(Strings.openGuide.of(lang)) }
        }
        HostNode("btn-battery", "button", Strings.batteryExemption.of(lang), onClick = onBatteryExemption) {
            OutlinedButton(onClick = onBatteryExemption) { Text(Strings.batteryExemption.of(lang)) }
        }
        HostNode("btn-autostart", "button", Strings.autostartSettings.of(lang), onClick = onAutostartSettings) {
            OutlinedButton(onClick = onAutostartSettings) { Text(Strings.autostartSettings.of(lang)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-open-admin", "button", Strings.openAdmin.of(lang), onClick = onOpenAdmin) {
                OutlinedButton(onClick = onOpenAdmin) { Text(Strings.openAdmin.of(lang)) }
            }
            HostNode("btn-open-hub", "button", Strings.openHub.of(lang), onClick = onOpenHub) {
                OutlinedButton(onClick = onOpenHub) { Text(Strings.openHub.of(lang)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-copy-admin", "button", Strings.copyAdminToken.of(lang), onClick = onCopyAdmin) {
                OutlinedButton(onClick = onCopyAdmin) { Text(Strings.copyAdminToken.of(lang)) }
            }
            HostNode("btn-copy-mcp", "button", Strings.copyMcpToken.of(lang), onClick = onCopyMcp) {
                OutlinedButton(onClick = onCopyMcp) { Text(Strings.copyMcpToken.of(lang)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        HostNode("settings-version", "text", "${Strings.version.of(lang)} $version") {
            Text("${Strings.version.of(lang)} $version", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GuideScreen(lang: Lang, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HostNode("guide-title", "text", Strings.guideTitle.of(lang)) {
            Text(Strings.guideTitle.of(lang), style = MaterialTheme.typography.headlineSmall)
        }
        HostNode("guide-intro", "text", Strings.guideIntro.of(lang)) {
            Text(Strings.guideIntro.of(lang), style = MaterialTheme.typography.bodyMedium)
        }
        Strings.guideSteps.forEachIndexed { index, step ->
            HostNode("guide-step-$index", "text", step.of(lang)) {
                Text(step.of(lang), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HostNode("guide-hint", "text", Strings.guideHint.of(lang)) {
            Text(Strings.guideHint.of(lang), style = MaterialTheme.typography.bodySmall)
        }
        HostNode("btn-guide-done", "button", Strings.guideDone.of(lang), onClick = onDone) {
            Button(onClick = onDone) { Text(Strings.guideDone.of(lang)) }
        }
    }
}
