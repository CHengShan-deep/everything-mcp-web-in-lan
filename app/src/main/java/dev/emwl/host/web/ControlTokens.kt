package dev.emwl.host.web

import android.content.Context
import dev.emwl.host.domain.Tokens

/**
 * Control-plane credentials. The admin token drives the web console; the MCP token
 * is the one handed to on-device agents. Both live only in app-private storage.
 */
class ControlTokens(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("control", Context.MODE_PRIVATE)

    val admin: String
        get() = prefs.getString(KEY_ADMIN, null) ?: rotate(KEY_ADMIN)

    val mcp: String
        get() = prefs.getString(KEY_MCP, null) ?: rotate(KEY_MCP)

    fun rotateAdmin(): String = rotate(KEY_ADMIN)

    fun rotateMcp(): String = rotate(KEY_MCP)

    private fun rotate(key: String): String {
        val token = Tokens.generate()
        prefs.edit().putString(key, token).apply()
        return token
    }

    fun adminHash(): String = Tokens.sha256Hex(admin)

    fun mcpHash(): String = Tokens.sha256Hex(mcp)

    private companion object {
        const val KEY_ADMIN = "adminToken"
        const val KEY_MCP = "mcpToken"
    }
}
