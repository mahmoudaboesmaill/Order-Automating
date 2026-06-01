package com.pharma.link.orderautomating

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * @deprecated استخدم [InvoiceRepository.sendInvoice] بدلاً من هذا الملف.
 * تم نقل المنطق البرمجي للمستودع لاتباع مبادئ Clean Architecture.
 */
@Deprecated("استخدم InvoiceRepository.sendInvoice", ReplaceWith("InvoiceRepository(context).sendInvoice(supplierCode, invoiceNumber, items)"))
fun sendInvoice(
    context: android.content.Context,
    supplierCode: String,
    invoiceNumber: String,
    items: List<Item>
): String {
    // تم الاحتفاظ بالكود هنا مؤقتاً للتوافق مع الأجزاء القديمة إن وجدت
    return try {
        val itemsArray = JSONArray()
        items.forEach { item ->
            itemsArray.put(JSONObject().apply {
                put("itm_code", item.itmCode)
                put("quantity", item.quantity)
                put("price", item.price)
                put("sale_price", item.salePrice)
                put("taxes", item.taxes)
                put("discount", item.discount)
                put("bonus", item.bonus)
            })
        }

        val body = JSONObject().apply {
            put("supplier_code", supplierCode)
            put("invoice_number", invoiceNumber)
            put("items", itemsArray)
        }.toString()

        val baseUrl = ServerManager.getSelectedUrl(context)
        val conn = URL("$baseUrl/invoice")
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
