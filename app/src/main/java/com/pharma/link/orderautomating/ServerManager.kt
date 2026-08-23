package com.pharma.link.orderautomating

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ServerConfig(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 8080,
    val token: String = ""
) {
    val url get() = "http://$ip:$port"
}

object ServerManager {
    private const val PREFS_NAME = "servers_prefs"
    private const val KEY_SERVERS = "servers"
    private const val KEY_SELECTED = "selected_id"
    private const val KEY_OCR_PROVIDER = "ocr_provider"

    // سيرفرات افتراضية
    private val defaultServers = emptyList<ServerConfig>()

    fun getServers(context: Context): List<ServerConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SERVERS, null) ?: return defaultServers
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                ServerConfig(
                    id   = obj.getString("id"),
                    name = obj.getString("name"),
                    ip   = obj.getString("ip"),
                    port = obj.optInt("port", 8080),
                    token = obj.optString("token", "")
                )
            }
        } catch (e: Exception) { defaultServers }
    }

    fun saveServers(context: Context, servers: List<ServerConfig>) {
        val arr = JSONArray()
        servers.forEach {
            arr.put(JSONObject().apply {
                put("id",   it.id)
                put("name", it.name)
                put("ip",   it.ip)
                put("port", it.port)
                put("token", it.token)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun getSelectedServer(context: Context): ServerConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_SELECTED, null) ?: return getServers(context).firstOrNull()
        return getServers(context).find { it.id == id }
    }

    fun setSelectedServer(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    fun addServer(context: Context, name: String, ip: String, port: Int = 8080, token: String = "") {
        val servers = getServers(context).toMutableList()
        servers.add(ServerConfig(
            id   = System.currentTimeMillis().toString(),
            name = name,
            ip   = ip,
            port = port,
            token = token.trim()
        ))
        saveServers(context, servers)
    }

    fun deleteServer(context: Context, id: String) {
        val servers = getServers(context).filter { it.id != id }
        saveServers(context, servers)
    }

    fun getSelectedSupplierCode(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("selected_supplier_code", null)
    }

    fun saveSelectedSupplierCode(context: Context, code: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("selected_supplier_code", code).apply()
    }

    fun getSelectedUrl(context: Context): String =
        getSelectedServer(context)?.url ?: ""

    fun getSelectedToken(context: Context): String =
        getSelectedServer(context)?.token.orEmpty()

    /**
     * The provider is sent with each OCR request, so changing it here does not
     * require editing Windows variables or restarting the server.
     */
    fun getOcrProvider(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OCR_PROVIDER, "auto")
            ?.lowercase()
            ?.takeIf { it in setOf("auto", "gemini", "mistral") }
            ?: "auto"

    fun saveOcrProvider(context: Context, provider: String) {
        val safeProvider = provider.lowercase().takeIf {
            it in setOf("auto", "gemini", "mistral")
        } ?: "auto"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_OCR_PROVIDER, safeProvider).apply()
    }
}
