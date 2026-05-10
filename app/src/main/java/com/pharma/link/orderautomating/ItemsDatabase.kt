package com.pharma.link.orderautomating

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PharmacyItem(
    val itmCode: String,
    val itmIntCode: String,
    val itmName: String
)

object ItemsDatabase {
    private const val SERVER_URL = "http://192.168.1.184:8080/items"
    private const val FILE_NAME = "items_full.csv"
    private var items: List<PharmacyItem> = emptyList()

    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                val data = URL(SERVER_URL).readText(Charsets.UTF_8)
                file.writeText(data, Charsets.UTF_8)
            }
            items = parseCSV(file.readText(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun forceRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        File(context.filesDir, FILE_NAME).delete()
        load(context)
    }

    fun search(query: String, limit: Int = 10): List<PharmacyItem> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase().trim()
        return items.filter {
            it.itmName.lowercase().contains(q) ||
                    it.itmIntCode.contains(q) ||
                    it.itmCode.contains(q)
        }.take(limit)
    }

    fun getByCode(itmCode: String): PharmacyItem? =
        items.find { it.itmCode == itmCode }

    fun isLoaded() = items.isNotEmpty()
    fun count() = items.size

    private fun parseCSV(text: String): List<PharmacyItem> {
        return text.lines()
            .drop(1)
            .mapNotNull { line ->
                val cols = line.split(",")
                if (cols.size >= 3)
                    PharmacyItem(
                        itmCode = cols[0].trim(),
                        itmIntCode = cols[1].trim(),
                        itmName = cols[2].trim()
                    )
                else null
            }
    }
}