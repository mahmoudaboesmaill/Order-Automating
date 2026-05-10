package com.pharma.link.orderautomating

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

fun sendInvoice(
    supplierCode: String,
    invoiceNumber: String,
    items: List<Item>
): String {
    return try {
        val itemsArray = JSONArray()
        items.forEach { item ->
            itemsArray.put(JSONObject().apply {
                put("itm_code", item.itmCode)
                put("quantity", item.quantity)
                put("price", item.price)
            })
        }

        val body = JSONObject().apply {
            put("supplier_code", supplierCode)
            put("invoice_number", invoiceNumber)
            put("items", itemsArray)
        }.toString()

        val conn = URL("http://192.168.1.184:8080/invoice")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        if (conn.responseCode == 200) "✅ تم الإرسال بنجاح!"
        else "⚠️ خطأ: ${conn.responseCode}"

    } catch (e: Exception) {
        "❌ ${e.message}"
    }
}