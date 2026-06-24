package com.pharma.link.orderautomating

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// نقل الـ data classes هنا لتكون مركزية
data class OcrItem(
    val invoiceName: String,
    var quantity: Double,
    var bonus: Double = 0.0,
    var taxes: Double=0.0,
    var price: Double,       // هذا هو سعر الشراء (الصافي)
    var salePrice: Double = 0.0, // سعر البيع (الجمهور)
    var discount: Double = 0.0,
    var itmCode: String = "",
    var matched: Boolean = false
)

data class OcrResponse(
    val supplierName: String,
    val invoiceNumber: String,
    val items: List<OcrItem>
)

class InvoiceRepository(private val context: Context) {

    suspend fun analyzeImage(bitmap: Bitmap): OcrResponse? = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)
        analyzeInvoice(base64, "image/jpeg")
    }

    suspend fun analyzePdf(bytes: ByteArray): OcrResponse? = withContext(Dispatchers.IO) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        analyzeInvoice(base64, "application/pdf")
    }

    private suspend fun analyzeInvoice(base64Data: String, mimeType: String): OcrResponse? {
        val baseUrl = ServerManager.getSelectedUrl(context)
        try {
            val body = JSONObject().apply {
                put("data", base64Data)
                put("mime_type", mimeType)
            }.toString()

            val conn = URL("$baseUrl/gemini").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 60000 
                readTimeout = 60000
                OutputStreamWriter(outputStream).use { it.write(body) }
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200)
                conn.inputStream.bufferedReader().readText()
            else {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
                throw Exception("HTTP $responseCode: $errBody")
            }

            Log.d("InvoiceRepository", "Server response: $response")

            // Parsing logic
            var text = ""
            try {
                val rootJson = JSONObject(response)
                if (rootJson.has("candidates")) {
                    text = rootJson
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                } else if (rootJson.has("supplier_name") || rootJson.has("items")) {
                    text = response
                } else {
                    throw Exception("تنسيق JSON غير مدعوم من السيرفر")
                }
            } catch (e: Exception) {
                // محاولة استخراج JSON إذا كان هناك نص إضافي
                val start = response.indexOf("{")
                val end = response.lastIndexOf("}")
                if (start != -1 && end != -1 && end > start) {
                    text = response.substring(start, end + 1)
                } else {
                    throw Exception("فشل في تحليل رد السيرفر: ${e.message}")
                }
            }

            // تنظيف شامل للـ Markdown
            text = text.replace("```json", "").replace("```", "").trim()
            Log.d("GEMINI_DEBUG", "البيانات المستخرجة: $text")

            val root = JSONObject(text)
            val rawSupName = root.optString("supplier_name", "غير معروف")
            
            // جلب الموردين من القاعدة للمقارنة الذكية
            val db = AppDatabase.getDatabase(context)
            val supplierDao = db.supplierDictionaryDao()
            val allSuppliers = supplierDao.getAll()
            
            // محاولة إيجاد كود المورد تلقائياً
            var autoDetectedCode = ""
            val normalizedRawName = ArabicNormalizer.normalize(rawSupName)
            
            // تعديل: إذا كان الاسم المكتشف هو رقم أصلاً (مثل 198)، نعتبره الكود فوراً
            if (rawSupName.trim().all { it.isDigit() }) {
                autoDetectedCode = rawSupName.trim()
            } else {
                val matchedSupplier = allSuppliers.find { 
                    normalizedRawName.contains(ArabicNormalizer.normalize(it.arabicName)) || 
                    normalizedRawName.contains(it.englishName.lowercase()) 
                }
                if (matchedSupplier != null) autoDetectedCode = matchedSupplier.supplierCode
            }

            val itemsArray = root.getJSONArray("items")
            val items = (0 until itemsArray.length()).map { i ->
                itemsArray.getJSONObject(i).let { obj ->
                    val qty = obj.optDouble("qty", 1.0).let { if (it <= 0) 1.0 else it }
                    val bonus = obj.optDouble("bns", 0.0)
                    val unitP = obj.optDouble("unit_p", 0.0)
                    val lineTotal = obj.optDouble("line_total", 0.0)
                    val rawTax = obj.optDouble("tax", 0.0)
                    val extra = obj.optDouble("extra", 0.0)
                    
                    var pPrice = 0.0
                    var finalTax = rawTax
                    var finalSalePrice = obj.optDouble("sale_p", 0.0)

                    // منطق الحساب بناءً على كود المورد المكتشف أو الاسم
                    when {
                        autoDetectedCode == "29" || normalizedRawName.contains("سينا") -> { // ابن سينا
                            pPrice = unitP + extra
                            if (qty > 0) finalTax = rawTax / qty
                        }
                        autoDetectedCode == "38" || normalizedRawName.contains("سيز") -> { // أوفر سيز
                            pPrice = unitP + extra
                        }
                        autoDetectedCode == "175" || normalizedRawName.contains("دريم") -> { // دريم
                            finalSalePrice = -1.0
                            pPrice = unitP
                        }
                        lineTotal > 0 -> pPrice = lineTotal / qty
                        else -> pPrice = unitP
                    }

                    val formattedPrice = (Math.round(pPrice * 1000).toDouble() / 1000.0)
                    val formattedTax = (Math.round(finalTax * 1000).toDouble() / 1000.0)

                    OcrItem(
                        invoiceName = obj.optString("name", "غير معروف"),
                        quantity    = qty,
                        bonus       = bonus,
                        taxes       = formattedTax,
                        price       = formattedPrice,
                        salePrice   = finalSalePrice,
                        matched     = false
                    )
                }
            }
            return OcrResponse(
                supplierName = if (autoDetectedCode.isNotEmpty()) autoDetectedCode else rawSupName,
                invoiceNumber = root.optString("invoice_number", ""),
                items = items
            )
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val friendlyError = when {
                msg.contains("failed to connect", true) || msg.contains("Connection refused", true) -> 
                    "❌ تعذر الاتصال بالسيرفر! تأكد أن:\n1. الموبايل على نفس الواي فاي.\n2. برنامج السيرفر يعمل.\n3. الـ IP ($baseUrl) صحيح."
                msg.contains("timeout", true) -> "⚠️ انتهى وقت المحاولة! السيرفر لا يرد."
                else -> "❌ خطأ في السيرفر: $msg"
            }
            throw Exception(friendlyError)
        }
    }

    suspend fun sendInvoice(
        supplierCode: String,
        invoiceNumber: String,
        items: List<Item>
    ): String = withContext(Dispatchers.IO) {
        try {
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

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxW = 1600; val maxH = 2400
        val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(), true
            )
        else bitmap
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
