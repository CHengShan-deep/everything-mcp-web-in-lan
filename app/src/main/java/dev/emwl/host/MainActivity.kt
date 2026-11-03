package dev.emwl.host

import android.Manifest
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleIntent(intent)
        restoreIfNeeded()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    HostScreen(
                        onStart = { sites -> HostService.start(this, sites) },
                        onStop = { HostService.stop(this) },
                        onBatteryExemption = { requestBatteryExemption() },
                        onOpenAdmin = {
                            openUrl("http://127.0.0.1:${HostState.runtime?.controlPort ?: Ports.CONTROL}/admin")
                        },
                        onOpenHub = {
                            openUrl("http://${Metrics.lanIpv4() ?: "127.0.0.1"}:${Ports.HUB}/")
                        },
                        console = { consoleText() },
                    )
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
            HostService.start(this, intent.getIntExtra("sites", HostState.desiredSites(this)))
        }
    }

    private fun restoreIfNeeded() {
        val sites = HostState.desiredSites(this)
        if (sites > 0 && !HostState.running) {
            HostService.start(this, sites)
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

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

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
            runCatching { appendLine(runtime.diagJson()) }
                .onFailure { appendLine("diagError=${it.message}") }
        }
    }
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
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onBatteryExemption: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenHub: () -> Unit,
    console: () -> String,
) {
    var consoleText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            consoleText = runCatching { console() }.getOrElse { "console error: ${it.message}" }
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
        HostNode("title", "text", "everything-mcp-web in LAN") {
            Text("everything-mcp-web in LAN", style = MaterialTheme.typography.headlineSmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-start", "button", "Start", onClick = { onStart(0) }) {
                Button(onClick = { onStart(0) }) { Text("Start") }
            }
            HostNode("btn-sites-8", "button", "8 sites", onClick = { onStart(8) }) {
                Button(onClick = { onStart(8) }) { Text("8 sites") }
            }
            HostNode("btn-sites-20", "button", "20 sites", onClick = { onStart(20) }) {
                Button(onClick = { onStart(20) }) { Text("20") }
            }
            HostNode("btn-sites-32", "button", "32 sites", onClick = { onStart(32) }) {
                Button(onClick = { onStart(32) }) { Text("32") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-stop", "button", "Stop", onClick = onStop) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            HostNode("btn-battery", "button", "Battery exemption", onClick = onBatteryExemption) {
                OutlinedButton(onClick = onBatteryExemption) { Text("Battery exemption") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostNode("btn-open-admin", "button", "Open admin", onClick = onOpenAdmin) {
                OutlinedButton(onClick = onOpenAdmin) { Text("Open admin") }
            }
            HostNode("btn-open-hub", "button", "Open hub", onClick = onOpenHub) {
                OutlinedButton(onClick = onOpenHub) { Text("Open hub") }
            }
        }

        Spacer(Modifier.height(8.dp))
        HostNode("status", "status", consoleText) {
            Text(consoleText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
}
