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
    var consumerPrice: Double = 0.0,      // مرجع فقط لدريم؛ لا يدخل E-PLUS تلقائياً
    var referenceDiscountPercent: Double? = null, // مرجع حسابي فقط لتبارك ويونايتد
    var purchasePriceFromPharmacistColumn: Double = 0.0,
    var purchasePriceFromLineTotal: Double = 0.0,
    var purchasePriceMethodsMatch: Boolean = true,
    var rawPharmacistPrice: Double = 0.0,
    var rawPharmacistMargin: Double = 0.0,
    var rawTaxTotal: Double = 0.0,
    var pharmaValidationWarnings: List<String> = emptyList(),
    var expiryMonth: String = "",         // شهر الصلاحية (MM) بعد تأكيد المستخدم
    var expiryYear: String = "",          // آخر رقميْن من سنة الصلاحية (YY)
    var expiryMode: String = ExpiryMode.REQUIRED,
    var shouldUpdateSalePrice: Boolean = true,
    var priceAlertKind: String = SupplierInvoiceRules.ALERT_SALE_PRICE,
    var itmCode: String = "",
    var matched: Boolean = false,
    var fuzzyScore: Double = 0.0          // 0 = no suggestion, 0.7-0.9 = suggestion
)

data class OcrResponse(
    val supplierName: String,
    val invoiceNumber: String,
    val invoiceDate: String = "",
    val invoiceTotalAsPrinted: Double = 0.0,  // إجمالي الفاتورة كما هو مطبوع
    val items: List<OcrItem>,
    val sourceType: String = "unknown"
)

/** A Room-free send envelope that carries the manually confirmed expiry. */
data class InvoiceSendLine(
    val item: Item,
    val expiryMonth: String = "",
    val expiryYear: String = ""
)

data class PdfInvoiceCandidate(
    val invoiceNumber: String,
    val pageStart: Int,
    val pageEnd: Int,
    val itemCount: Int = 0,
    val printedTotal: Double = 0.0,
    val duplicateCopies: Int = 1,
    val side: String = "full"
)

/**
 * Live comparison shown on the review screen. The printed value is the total
 * extracted from the invoice header/summary; the calculated value is rebuilt
 * from the currently edited rows, so deleting or correcting an item updates it.
 */
data class InvoiceTotalCheck(
    val printedTotal: Double = 0.0,
    val calculatedTotal: Double = 0.0,
    val difference: Double = 0.0,
    val hasPrintedTotal: Boolean = false
) {
    // Green is reserved for an effectively exact monetary match. A difference
    // up to one pound is reviewable (yellow); anything above one pound is red.
    val matches: Boolean
        get() = hasPrintedTotal && difference <= 0.01

    val withinOnePound: Boolean
        get() = hasPrintedTotal && difference <= 1.0

    val percentDifference: Double
        get() = if (hasPrintedTotal && printedTotal > 0.0) {
            (difference / printedTotal) * 100.0
        } else {
            0.0
        }

    companion object {
        fun from(printedTotal: Double, items: List<OcrItem>): InvoiceTotalCheck {
            val calculated = items.sumOf { item ->
                (item.price + item.taxes) * item.quantity
            }
            // OCR occasionally drops a decimal separator in the invoice summary
            // (for example 6516.90 becomes 651690). Repair only an obvious
            // ten/one-hundred/one-thousand scale error when it nearly matches
            // the independently calculated item total; real differences remain
            // visible to the pharmacist.
            val correctedPrinted = repairObviousDecimalShift(printedTotal, calculated)
            val hasPrinted = correctedPrinted > 0.0
            return InvoiceTotalCheck(
                printedTotal = correctedPrinted,
                calculatedTotal = calculated,
                difference = if (hasPrinted) kotlin.math.abs(calculated - correctedPrinted) else 0.0,
                hasPrintedTotal = hasPrinted
            )
        }

        private fun repairObviousDecimalShift(printed: Double, calculated: Double): Double {
            if (!printed.isFinite() || !calculated.isFinite() || printed <= 0.0 || calculated <= 0.0) {
                return printed
            }
            val tolerance = maxOf(0.05, calculated * 0.005)
            val candidates = listOf(
                printed / 10.0,
                printed / 100.0,
                printed / 1000.0,
                printed * 10.0,
                printed * 100.0
            )
            return candidates.firstOrNull { candidate ->
                candidate > 0.0 && kotlin.math.abs(candidate - calculated) <= tolerance
            } ?: printed
        }
    }
}

sealed class ValidationResult {
    object NoPrintedTotal : ValidationResult()
    data class Match(val calculated: Double, val printed: Double) : ValidationResult()
    data class SmallDiff(val calculated: Double, val printed: Double, val diff: Double) : ValidationResult()
    data class BigDiff(val calculated: Double, val printed: Double, val diff: Double) : ValidationResult()
}

class InvoiceRepository(private val context: Context) {

    fun validateInvoice(response: OcrResponse): ValidationResult {
        val check = InvoiceTotalCheck.from(response.invoiceTotalAsPrinted, response.items)
        if (!check.hasPrintedTotal) return ValidationResult.NoPrintedTotal

        return when {
            check.matches            -> ValidationResult.Match(check.calculatedTotal, check.printedTotal)
            check.withinOnePound     -> ValidationResult.SmallDiff(check.calculatedTotal, check.printedTotal, check.difference)
            else                -> ValidationResult.BigDiff(check.calculatedTotal, check.printedTotal, check.difference)
        }
    }

    suspend fun analyzeImage(bitmap: Bitmap): OcrResponse? = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)
        analyzeInvoice(base64, "image/jpeg", sourceType = "image")
    }

    suspend fun inspectPdf(bytes: ByteArray): List<PdfInvoiceCandidate> = withContext(Dispatchers.IO) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val baseUrl = ServerManager.getSelectedUrl(context)
        if (baseUrl.isBlank()) {
            throw Exception("لم يتم إعداد سيرفر المعالجة. أضف عنوان السيرفر من الإعدادات أولاً.")
        }

        val db = AppDatabase.getDatabase(context)
        val selectedCode = ServerManager.getSelectedSupplierCode(context).orEmpty()
        val supplierProfile = if (selectedCode.isNotEmpty())
            db.supplierProfileDao().getByCode(selectedCode)
        else null
        val body = JSONObject().apply {
            put("data", base64)
            put("mime_type", "application/pdf")
            if (selectedCode.isNotBlank()) put("supplier_code", selectedCode)
            supplierProfile?.columnHint?.takeIf { it.isNotBlank() }?.let {
                put("column_hint", it)
            }
        }.toString()

        val response = postJson(baseUrl, "/pdf-inspect", body)
        val root = JSONObject(response)
        val array = root.optJSONArray("invoices")
            ?: throw Exception("السيرفر لم يرجع قائمة الفواتير داخل PDF")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PdfInvoiceCandidate(
                invoiceNumber = item.optString("invoice_number", ""),
                pageStart = item.optInt("page_start", 1),
                pageEnd = item.optInt("page_end", item.optInt("page_start", 1)),
                itemCount = item.optInt("item_count", 0),
                printedTotal = item.optDouble("printed_total", 0.0),
                duplicateCopies = item.optInt("duplicate_copies", 1),
                side = item.optString("side", "full")
            )
        }
    }

    suspend fun analyzePdf(
        bytes: ByteArray,
        candidate: PdfInvoiceCandidate? = null
    ): OcrResponse? = withContext(Dispatchers.IO) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        analyzeInvoice(base64, "application/pdf", candidate, sourceType = "pdf")
    }

    private suspend fun analyzeInvoice(
        base64Data: String,
        mimeType: String,
        pdfCandidate: PdfInvoiceCandidate? = null,
        sourceType: String = "unknown"
    ): OcrResponse? {
        val baseUrl = ServerManager.getSelectedUrl(context)
        if (baseUrl.isBlank()) {
            throw Exception("لم يتم إعداد سيرفر المعالجة. أضف عنوان السيرفر من الإعدادات أولاً.")
        }
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
                put("ocr_provider", ServerManager.getOcrProvider(context))
                if (selectedCode.isNotBlank()) {
                    put("supplier_code", selectedCode)
                }
                if (supplierProfile?.columnHint?.isNotEmpty() == true) {
                    put("column_hint", supplierProfile.columnHint)
                }
                pdfCandidate?.let { candidate ->
                    put("pdf_page_start", candidate.pageStart)
                    put("pdf_page_end", candidate.pageEnd)
                    put("pdf_side", candidate.side)
                }
            }.toString()

            val response = postJson(baseUrl, "/gemini", body)

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
            // Keep the latest raw OCR response privately on the phone. It is
            // overwritten for every analysis and lets us inspect exact column
            // values over ADB instead of guessing from calculated cards.
            runCatching {
                context.filesDir.resolve("last_ocr_response.json").writeText(
                    root.toString(2),
                    Charsets.UTF_8
                )
            }
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

                // Deterministic safety net for common OCR variants. This keeps a
                // known supplier from falling into the generic/unknown layout
                // while the local dictionary is being upgraded on an old phone.
                if (autoDetectedCode.isBlank() &&
                    (normalizedRawName.contains("تبارك") ||
                        normalizedRawName.contains("tabark") ||
                        normalizedRawName.contains("tabarak"))
                ) {
                    autoDetectedCode = "218"
                }
            }

            // إذا اختار المستخدم مورداً من شاشة الإعدادات، فهذا هو مصدر الحقيقة
            // لقواعد الحساب. لا نسمح لاسم OCR عابر أن يغيّر معادلة الفاتورة.
            val ruleSupplierCode = selectedCode.ifBlank { autoDetectedCode }
            val itemsArray = root.getJSONArray("items")
            val items = (0 until itemsArray.length()).map { i ->
                itemsArray.getJSONObject(i).let { obj ->
                    val qty      = obj.optDouble("quantity", 1.0).let { if (it <= 0) 1.0 else it }
                    val extractedBonus = obj.optDouble("bonus", 0.0)
                    val rawUnitP  = obj.optDouble("unit_price", 0.0)
                    val discPct   = obj.optDouble("discount_percent", 0.0)
                    val lineTotal = obj.optDouble("line_total_as_printed", 0.0)
                    val rawSaleP  = obj.optDouble("sale_p", 0.0)
                    val rawPharmacistPrice = obj.optDouble("pharmacist_price", 0.0)
                    val rawPharmacistMargin = obj.optDouble("pharmacist_margin", 0.0)
                    val rawDistributorMargin = obj.optDouble("distributor_margin", 0.0)
                    val rawTaxPerItem = obj.optDouble("tax_per_item", 0.0)
                    val rawTaxTotal = obj.optDouble("tax_total", 0.0)
                    val supplierItemCode = obj.optString("supplier_item_code", "").trim()
                    val codeNumber = supplierItemCode.toDoubleOrNull()
                    val codeWasUsedAsPrice = ruleSupplierCode in setOf("198", "218") &&
                        codeNumber != null && codeNumber > 0.0 &&
                        (kotlin.math.abs(rawUnitP - codeNumber) <= 0.0005 ||
                            kotlin.math.abs(rawSaleP - codeNumber) <= 0.0005)
                    // Never let a value copied from Tabark's narrow «ك» code
                    // column reach the review screen as a sale price. The server
                    // normally repairs this with a second visual pass; this is
                    // the local fail-safe if that retry is unavailable.
                    val unitP = if (codeWasUsedAsPrice) 0.0 else rawUnitP
                    val saleP = if (codeWasUsedAsPrice) 0.0 else rawSaleP
                    // The actual Pharma response shows a repeatable narrow-column
                    // shift. On the first taxed row, the genuine pharmacist price
                    // (66.67) lands in line_total while the discount (20) lands in
                    // pharmacist_price. A row total for qty > 1 cannot be below
                    // the public unit price. Recover the pharmacist price and the
                    // VAT-inclusive row total deterministically from those cells.
                    val pharmaTaxedPriceShift = ruleSupplierCode == "38" &&
                        qty > 1.0 && rawTaxPerItem > 0.0 &&
                        lineTotal > 0.0 && saleP > 0.0 && lineTotal < saleP &&
                        rawPharmacistPrice in setOf(18.0, 20.0, 25.0)
                    val normalizedPharmacistPrice = if (pharmaTaxedPriceShift) {
                        lineTotal
                    } else rawPharmacistPrice
                    val normalizedLineTotal = if (pharmaTaxedPriceShift) {
                        (normalizedPharmacistPrice + rawTaxPerItem) * qty
                    } else lineTotal
                    // In the captured Pharma payload, non-tax fixed pharmacist
                    // margins (2.5, 1.5, 1) consistently land in the adjacent
                    // distributor field while pharmacist_margin is zero. Taxed
                    // rows never receive a margin.
                    val normalizedPharmacistMargin = when {
                        ruleSupplierCode != "38" -> rawPharmacistMargin
                        rawTaxPerItem > 0.0 -> 0.0
                        rawPharmacistMargin > 0.0 -> rawPharmacistMargin
                        rawDistributorMargin > 0.0 -> rawDistributorMargin
                        else -> 0.0
                    }
                    // United and Tabark invoices do not have a bonus field.
                    // Never allow a shifted neighbouring value to reach E-PLUS.
                    val bonus = if (ruleSupplierCode in setOf("198", "218")) 0.0 else extractedBonus
                    val calculated = SupplierInvoiceRules.calculate(
                        supplierCode = ruleSupplierCode,
                        item = ExtractedSupplierItem(
                            quantity = qty,
                            bonus = bonus,
                            unitPrice = unitP,
                            discountPercent = discPct,
                            lineTotal = normalizedLineTotal,
                            pharmacistPrice = normalizedPharmacistPrice,
                            pharmacistMargin = normalizedPharmacistMargin,
                            distributorMargin = rawDistributorMargin,
                            taxPerItem = rawTaxPerItem,
                            taxTotal = rawTaxTotal,
                            suggestedSalePrice = saleP,
                            consumerPrice = obj.optDouble("consumer_price", 0.0)
                        )
                    )
                    val referenceDiscount = SupplierInvoiceRules.referenceDiscountPercent(
                        supplierCode = ruleSupplierCode,
                        purchasePrice = calculated.purchasePrice,
                        salePrice = calculated.salePrice
                    )

                    OcrItem(
                        invoiceName        = obj.optString("name", "غير معروف"),
                        quantity           = qty,
                        bonus              = bonus,
                        unitPrice          = unitP,
                        discountPercent    = discPct,
                        lineTotalAsPrinted = normalizedLineTotal,
                        taxes              = calculated.taxPerItem,
                        price              = calculated.purchasePrice,
                        salePrice          = calculated.salePrice,
                        consumerPrice      = calculated.consumerPrice,
                        referenceDiscountPercent = referenceDiscount,
                        purchasePriceFromPharmacistColumn = calculated.purchasePriceFromPharmacistColumn,
                        purchasePriceFromLineTotal = calculated.purchasePriceFromLineTotal,
                        purchasePriceMethodsMatch = calculated.purchasePriceMethodsMatch,
                        rawPharmacistPrice = rawPharmacistPrice,
                        rawPharmacistMargin = normalizedPharmacistMargin,
                        rawTaxTotal = rawTaxTotal,
                        pharmaValidationWarnings = calculated.pharmaValidationWarnings,
                        expiryMode         = if (ruleSupplierCode == "175") ExpiryMode.UNKNOWN else ExpiryMode.REQUIRED,
                        shouldUpdateSalePrice = calculated.shouldUpdateSalePrice,
                        priceAlertKind     = calculated.priceAlertKind,
                        matched            = false
                    )
                }
            }
            val invoiceTotalAsPrinted = root.optDouble("invoice_total_as_printed", 0.0)
            if (ruleSupplierCode == "38") {
                val recovery = SupplierInvoiceRules.recoverPharmaMargins(
                    invoiceTotal = invoiceTotalAsPrinted,
                    rows = items.map { item ->
                        PharmaMarginRow(
                            matchKey = listOf(
                                ArabicNormalizer.normalize(item.invoiceName),
                                item.rawPharmacistPrice.toString(),
                                item.salePrice.toString()
                            ).joinToString("|"),
                            quantity = item.quantity,
                            lineTotal = item.lineTotalAsPrinted,
                            taxPerItem = item.taxes,
                            pharmacistMargin = item.rawPharmacistMargin
                        )
                    }
                )
                recovery.margins.forEachIndexed { index, recoveredMargin ->
                    val item = items[index]
                    if (item.taxes <= 0.0 && recoveredMargin > item.rawPharmacistMargin + 0.0005) {
                        val recalculated = SupplierInvoiceRules.calculate(
                            supplierCode = "38",
                            item = ExtractedSupplierItem(
                                quantity = item.quantity,
                                bonus = item.bonus,
                                unitPrice = item.unitPrice,
                                discountPercent = item.discountPercent,
                                lineTotal = item.lineTotalAsPrinted,
                                pharmacistPrice = item.rawPharmacistPrice,
                                pharmacistMargin = recoveredMargin,
                                taxPerItem = item.taxes,
                                taxTotal = item.rawTaxTotal,
                                suggestedSalePrice = item.salePrice,
                                consumerPrice = item.consumerPrice
                            )
                        )
                        item.rawPharmacistMargin = recoveredMargin
                        item.price = recalculated.purchasePrice
                        item.purchasePriceFromPharmacistColumn =
                            recalculated.purchasePriceFromPharmacistColumn
                        item.purchasePriceFromLineTotal = recalculated.purchasePriceFromLineTotal
                        item.purchasePriceMethodsMatch = recalculated.purchasePriceMethodsMatch
                        item.pharmaValidationWarnings = recalculated.pharmaValidationWarnings
                    }
                }

                if (!recovery.reconciled) {
                    val recoveredTotal = items.sumOf { it.rawPharmacistMargin * it.quantity }
                    val expectedTotal = invoiceTotalAsPrinted - items.sumOf { it.lineTotalAsPrinted }
                    if (expectedTotal - recoveredTotal > 0.10) {
                        items.filter { it.taxes <= 0.0 && it.rawPharmacistMargin <= 0.0 }
                            .forEach { item ->
                                item.purchasePriceMethodsMatch = false
                                item.pharmaValidationWarnings =
                                    item.pharmaValidationWarnings + "هامش الصيدلي غير مقروء"
                            }
                    }
                }
            }
            return OcrResponse(
                supplierName           = if (ruleSupplierCode.isNotEmpty()) ruleSupplierCode else rawSupName,
                invoiceNumber          = root.optString("invoice_number", ""),
                invoiceDate            = root.optString("date", ""),
                invoiceTotalAsPrinted  = invoiceTotalAsPrinted,
                items                  = items,
                sourceType             = sourceType
            )
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val friendlyError = when {
                msg.contains("failed to connect", true) || msg.contains("Connection refused", true) -> 
                    "❌ تعذر الاتصال بالسيرفر! تأكد أن:\n1. الموبايل على نفس الواي فاي.\n2. برنامج السيرفر يعمل.\n3. الـ IP ($baseUrl) صحيح."
                msg.contains("timeout", true) -> "⚠️ انتهى وقت معالجة الفاتورة. أعد المحاولة وتأكد أن السيرفر ما زال يعمل."
                msg.contains("Software caused connection abort", true) ||
                    msg.contains("Connection reset", true) ||
                    msg.contains("unexpected end of stream", true) ->
                    "⚠️ انقطع الاتصال أثناء معالجة الفاتورة. قد تكون قراءة OCR استغرقت وقتاً طويلاً؛ أعد المحاولة وتأكد من ثبات الشبكة وتشغيل السيرفر."
                msg.contains("ResourceExhausted", true) ||
                    msg.contains("quota", true) ||
                    msg.contains("rate limit", true) ||
                    msg.contains("HTTP 429", true) ->
                    "⚠️ حصة Gemini انتهت أو تم تجاوز حد الطلبات. أضف مفتاح Gemini آخر للسيرفر أو انتظر حتى تتجدد الحصة."
                else -> "❌ خطأ في السيرفر: $msg"
            }
            throw Exception(friendlyError)
        }
    }

    private fun postJson(baseUrl: String, path: String, body: String): String {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            ServerManager.getSelectedToken(context).takeIf { it.isNotBlank() }?.let {
                setRequestProperty("X-Order-Robot-Token", it)
            }
            doOutput = true
            connectTimeout = 60000
            readTimeout = 60000
            OutputStreamWriter(outputStream).use { it.write(body) }
        }

        val responseCode = conn.responseCode
        return if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            throw Exception("HTTP $responseCode: $errBody")
        }
    }

    suspend fun sendInvoice(
        supplierCode: String,
        invoiceNumber: String,
        items: List<InvoiceSendLine>
    ): String = withContext(Dispatchers.IO) {
        try {
            val itemsArray = JSONArray()
            items.forEach { line ->
                val item = line.item
                itemsArray.put(JSONObject().apply {
                    put("itm_code", item.itmCode)
                    put("quantity", item.quantity)
                    put("price", item.price)
                    put("sale_price", item.salePrice)
                    put("update_sale_price", item.updateSalePrice)
                    put("price_alert_kind", item.priceAlertKind)
                    put("invoice_name", item.invoiceName)
                    put("taxes", item.taxes)
                    put("discount", item.discount)
                    put("bonus", item.bonus)
                    put("expiry_month", line.expiryMonth)
                    put("expiry_year", line.expiryYear)
                })
            }

            val body = JSONObject().apply {
                put("supplier_code", supplierCode)
                put("invoice_number", invoiceNumber)
                put("items", itemsArray)
            }.toString()

            val baseUrl = ServerManager.getSelectedUrl(context)
            if (baseUrl.isBlank()) {
                return@withContext "❌ لم يتم إعداد سيرفر المعالجة. أضف عنوان السيرفر من الإعدادات أولاً."
            }
            val conn = URL("$baseUrl/invoice")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            ServerManager.getSelectedToken(context).takeIf { it.isNotBlank() }?.let {
                conn.setRequestProperty("X-Order-Robot-Token", it)
            }
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                "✅ تم الإرسال بنجاح!"
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = if (errorBody.isBlank()) "" else " - $errorBody"
                "⚠️ خطأ: $responseCode$detail"
            }

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
