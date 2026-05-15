package com.pharma.link.orderautomating

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ServerConfig(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 8080
) {
    val url get() = "http://$ip:$port"
}

object ServerManager {
    private const val PREFS_NAME = "servers_prefs"
    private const val KEY_SERVERS = "servers"
    private const val KEY_SELECTED = "selected_id"

    // سيرفرات افتراضية
    private val defaultServers = listOf(
        ServerConfig("home",     "البيت",       "192.168.1.4"),
        ServerConfig("pharma1",  "الصيدلية 1",  "192.168.1.184"),
        ServerConfig("pharma2",  "الصيدلية 2",  "192.168.1.185")
    )

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
                    port = obj.optInt("port", 8080)
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

    fun addServer(context: Context, name: String, ip: String, port: Int = 8080) {
        val servers = getServers(context).toMutableList()
        servers.add(ServerConfig(
            id   = System.currentTimeMillis().toString(),
            name = name,
            ip   = ip,
            port = port
        ))
        saveServers(context, servers)
    }

    fun deleteServer(context: Context, id: String) {
        val servers = getServers(context).filter { it.id != id }
        saveServers(context, servers)
    }

    fun getSelectedUrl(context: Context): String =
        getSelectedServer(context)?.url ?: "http://192.168.1.184:8080"
}