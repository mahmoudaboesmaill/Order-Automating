package com.pharma.link.orderautomating

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

data class PharmacyItem(
    val itmCode: String,
    val nameEn: String,
    val barcode: String
)

object ItemsDatabase {
    private const val FILE_NAME = "items_full.csv"
    private var items: List<PharmacyItem> = emptyList()

    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)

        if (file.exists()) {
            items = parseCSV(file.readText(Charset.forName("Windows-1256")))
        } else {
            loadFromAssets(context, file)
        }

        syncFromServer(context, file)

        items.isNotEmpty()
    }

    private fun loadFromAssets(context: Context, file: File) {
        try {
            val data = context.assets.open(FILE_NAME)
                .bufferedReader(Charset.forName("Windows-1256")).readText()
            file.writeText(data, Charset.forName("Windows-1256"))
            items = parseCSV(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun syncFromServer(context: Context, file: File) {
        try {
            val url = "${ServerManager.getSelectedUrl(context)}/items"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val newData = conn.inputStream.bufferedReader(Charset.forName("Windows-1256")).readText()

                val oldSize = if (file.exists()) file.length() else 0
                val newSize = newData.length.toLong()

                if (newSize != oldSize) {
                    file.writeText(newData, Charset.forName("Windows-1256"))
                    items = parseCSV(newData)
                }
            }
        } catch (e: Exception) {
            // السيرفر مش شغال — هيفضل على المحلي
        }
    }

    suspend fun forceRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            val url = "${ServerManager.getSelectedUrl(context)}/items"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            if (conn.responseCode == 200) {
                val data = conn.inputStream.bufferedReader(Charset.forName("Windows-1256")).readText()
                file.writeText(data, Charset.forName("Windows-1256"))
                items = parseCSV(data)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    fun search(query: String, limit: Int = 10): List<PharmacyItem> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase().trim()
        return items.filter {
            it.nameEn.lowercase().contains(q) ||
                    it.barcode.contains(q) ||
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
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                val parts = trimmed.split(",")
                    .map { it.trim() }
                    .dropLastWhile { it.isEmpty() }

                if (parts.size < 2) return@mapNotNull null

                val itmCode = parts[0]
                if (itmCode.isEmpty()) return@mapNotNull null

                val lastPart = parts.last()
                val barcode = if (lastPart.all { it.isDigit() }) lastPart else ""
                val nameEn = if (barcode.isNotEmpty() && parts.size >= 3)
                    parts[parts.size - 2]
                else if (barcode.isEmpty())
                    lastPart
                else ""

                PharmacyItem(itmCode = itmCode, nameEn = nameEn, barcode = barcode)
            }
    }
}