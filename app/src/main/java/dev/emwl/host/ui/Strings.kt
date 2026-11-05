package dev.emwl.host.ui

import android.content.Context

enum class Lang {
    EN,
    ZH;

    companion object {
        fun parse(raw: String?): Lang = if (raw == "zh") ZH else EN
    }
}

/** Every user-facing string is defined in both languages; English is the default. */
class Text(val en: String, val zh: String) {
    fun of(lang: Lang): String = if (lang == Lang.ZH) zh else en
}

object Strings {
    val title = Text("PocketServe", "PocketServe")
    val settings = Text("Settings", "设置")
    val back = Text("Back", "返回")

    val startHosting = Text("Start hosting", "开始托管")
    val stopHosting = Text("Stop", "停止")
    val batteryExemption = Text("Battery exemption", "电池豁免")
    val autostartSettings = Text("Autostart settings", "自启动设置")
    val openAdmin = Text("Open admin", "打开管理台")
    val openHub = Text("Open hub", "打开 hub")
    val copyAdminToken = Text("Copy admin token", "复制管理令牌")
    val copyMcpToken = Text("Copy MCP token", "复制 MCP 令牌")

    val warningBattery = Text(
        "Battery optimization is on: LAN access stops when the phone sleeps. Tap \"Battery exemption\" and allow it.",
        "电池优化已开启：手机休眠后局域网将不可访问。请点击「电池豁免」并允许。",
    )

    val sites = Text("Sites", "站点")
    val noSites = Text("No sites yet. Create one over MCP or in the admin console.", "还没有站点。可通过 MCP 或管理台创建。")
    val running = Text("on", "运行中")
    val stopped = Text("off", "已停止")

    val language = Text("Language", "语言")
    val english = Text("English", "English")
    val chinese = Text("中文", "中文")
    val openGuide = Text("Open guide", "打开新手引导")
    val version = Text("Version", "版本")

    val guideTitle = Text("Welcome to PocketServe", "欢迎使用 PocketServe")
    val guideIntro = Text(
        "PocketServe hosts websites from this phone on your local network, managed by an agent over MCP.",
        "PocketServe 用这台手机在局域网内托管网站，并可由代理通过 MCP 管理。",
    )
    val guideSteps = listOf(
        Text(
            "1. Tap \"Start hosting\". The hosting service keeps running in the background.",
            "1. 点击「开始托管」，托管服务会在后台常驻。",
        ),
        Text(
            "2. Allow the battery exemption. Without it, LAN access stops when the phone sleeps.",
            "2. 允许电池豁免；没有它，手机休眠后局域网将不可访问。",
        ),
        Text(
            "3. Allow autostart in your vendor settings (MIUI/ColorOS need this). Otherwise open the app once after a reboot.",
            "3. 在厂商设置里允许自启动（MIUI/ColorOS 必须）；否则重启后需要打开一次 App。",
        ),
        Text(
            "4. Create sites over MCP on 127.0.0.1 with the MCP token, or in the admin console.",
            "4. 用 MCP 令牌在 127.0.0.1 上通过 MCP 建站，或在管理台操作。",
        ),
        Text(
            "5. Sites are reachable by port, and by name through the hub (port 47101) behind a reverse proxy.",
            "5. 站点可通过端口访问，也可经反代指到 hub（47101）后用域名访问。",
        ),
    )
    val guideDone = Text("Got it", "我知道了")
    val guideHint = Text("You can reopen this from Settings.", "之后可在「设置」里重新打开。")

    val autostartWorked = Text(
        "System start (%1\$s) worked at %2\$s.",
        "系统自启（%1\$s）已于 %2\$s 生效。",
    )
    val autostartBlocked = Text(
        "System start (%1\$s) at %2\$s did not take effect: your vendor blocks it. Open the app once after each reboot, or allow autostart in settings.",
        "系统自启（%1\$s）在 %2\$s 未生效：厂商系统拦截了它。重启后请打开一次 App，或在设置里允许自启动。",
    )
}

object AppPrefs {

    fun lang(context: Context): Lang = Lang.parse(prefs(context).getString("lang", "en"))

    fun setLang(context: Context, lang: Lang) {
        prefs(context).edit().putString("lang", if (lang == Lang.ZH) "zh" else "en").apply()
    }

    fun guideSeen(context: Context): Boolean = prefs(context).getBoolean("guideSeen", false)

    fun markGuideSeen(context: Context) {
        prefs(context).edit().putBoolean("guideSeen", true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("ui", Context.MODE_PRIVATE)
}
