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
    var unitPrice: Double = 0.0,          // سعر الوحدة كما هو مطبوع
    var discountPercent: Double = 0.0,    // نسبة الخصم % كما هي مطبوعة
    var lineTotalAsPrinted: Double = 0.0, // إجمالي السطر كما هو مطبوع
    var taxes: Double = 0.0,
    var price: Double = 0.0,              // سعر الشراء الصافي (محسوب في Kotlin)
    var salePrice: Double = 0.0,
    var itmCode: String = "",
    var matched: Boolean = false,
    var fuzzyScore: Double = 0.0          // 0 = no suggestion, 0.7-0.9 = suggestion
)

data class OcrResponse(
    val supplierName: String,
    val invoiceNumber: String,
    val invoiceDate: String = "",
    val invoiceTotalAsPrinted: Double = 0.0,  // إجمالي الفاتورة كما هو مطبوع
    val items: List<OcrItem>
)

sealed class ValidationResult {
    object NoPrintedTotal : ValidationResult()
    data class Match(val calculated: Double, val printed: Double) : ValidationResult()
    data class SmallDiff(val calculated: Double, val printed: Double, val diff: Double) : ValidationResult()
    data class BigDiff(val calculated: Double, val printed: Double, val diff: Double) : ValidationResult()
}

class InvoiceRepository(private val context: Context) {

    fun validateInvoice(response: OcrResponse): ValidationResult {
        val printedTotal = response.invoiceTotalAsPrinted
        if (printedTotal <= 0.0) return ValidationResult.NoPrintedTotal

        val calculatedTotal = response.items.sumOf { item ->
            item.unitPrice * item.quantity * (1.0 - item.discountPercent / 100.0)
        }

        val difference = Math.abs(calculatedTotal - printedTotal)
        val percentDiff = if (printedTotal > 0) (difference / printedTotal) * 100 else 0.0

        return when {
            percentDiff <= 1.0  -> ValidationResult.Match(calculatedTotal, printedTotal)
            percentDiff <= 5.0  -> ValidationResult.SmallDiff(calculatedTotal, printedTotal, difference)
            else                -> ValidationResult.BigDiff(calculatedTotal, printedTotal, difference)
        }
    }

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
            // جلب profile المورد المحدد حالياً (لو متاح)
            val db = AppDatabase.getDatabase(context)
            val selectedCode = ServerManager.getSelectedSupplierCode(context) ?: ""
            val supplierProfile = if (selectedCode.isNotEmpty())
                db.supplierProfileDao().getByCode(selectedCode)
            else null

            val body = JSONObject().apply {
                put("data", base64Data)
                put("mime_type", mimeType)
                if (supplierProfile?.columnHint?.isNotEmpty() == true) {
                    put("supplier_code", selectedCode)
                    put("column_hint", supplierProfile.columnHint)
                }
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
                    val qty      = obj.optDouble("quantity", 1.0).let { if (it <= 0) 1.0 else it }
                    val bonus    = obj.optDouble("bonus", 0.0)
                    val unitP    = obj.optDouble("unit_price", 0.0)
                    val discPct  = obj.optDouble("discount_percent", 0.0)
                    val lineTotal= obj.optDouble("line_total_as_printed", 0.0)

                    // جلب Profile المورد من الـ DB (أو الافتراضي لو مش موجود)
                    val profile = AppDatabase.getDatabase(context)
                        .supplierProfileDao()
                        .getByCode(autoDetectedCode)
                        ?: SupplierProfile(supplierCode = autoDetectedCode)

                    // حساب سعر الشراء الصافي في Kotlin (deterministic)
                    val rawPrice = when (profile.priceFormula) {
                        PriceFormula.UNIT_PLUS_EXTRA    -> unitP + (unitP * discPct / 100.0).let { unitP - it }
                        PriceFormula.LINE_TOTAL_DIVIDED -> if (lineTotal > 0 && qty > 0) lineTotal / qty else unitP
                        PriceFormula.UNIT_PRICE         -> unitP * (1 - discPct / 100.0)
                    }

                    val pPrice = Math.round(rawPrice * 1000).toDouble() / 1000.0

                    // سعر البيع حسب Profile المورد
                    val finalSalePrice = if (profile.hasSalePrice)
                        obj.optDouble("sale_p", 0.0)
                    else
                        -1.0  // -1 = تجاهل (دريم وغيره)

                    OcrItem(
                        invoiceName        = obj.optString("name", "غير معروف"),
                        quantity           = qty,
                        bonus              = bonus,
                        unitPrice          = unitP,
                        discountPercent    = discPct,
                        lineTotalAsPrinted = lineTotal,
                        taxes              = 0.0,
                        price              = pPrice,
                        salePrice          = finalSalePrice,
                        matched            = false
                    )
                }
            }
            return OcrResponse(
                supplierName           = if (autoDetectedCode.isNotEmpty()) autoDetectedCode else rawSupName,
                invoiceNumber          = root.optString("invoice_number", ""),
                invoiceDate            = root.optString("date", ""),
                invoiceTotalAsPrinted  = root.optDouble("invoice_total_as_printed", 0.0),
                items                  = items
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
