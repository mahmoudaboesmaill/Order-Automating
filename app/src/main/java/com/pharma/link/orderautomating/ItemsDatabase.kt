package com.pharma.link.orderautomating

import android.content.Context
import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import java.nio.charset.Charset

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Entity(tableName = "pharmacy_items")
data class PharmacyItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val itmCode: String,
    val nameAr: String,
    val nameEn: String,
    val barcode: String
)

object ItemsDatabase {
    private const val FILE_NAME = "items_full.csv"
    
    private val _importProgress = MutableStateFlow(1f) // 1f means finished or not started
    val importProgress: StateFlow<Float> = _importProgress

    suspend fun load(context: Context): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.pharmacyItemDao()
        val currentCount = dao.getCount()
        
        // تحميل الموردين الافتراضيين
        seedDefaultSuppliers(context)
        seedDefaultProfiles(context)

        // إذا كانت فارغة، حمل البيانات
        if (currentCount < 1000) { 
            _importProgress.value = 0f
            importFromAssets(context)
            _importProgress.value = 1f
        }
        dao.getCount()
    }

    private suspend fun seedDefaultSuppliers(context: Context) {
        val dao = AppDatabase.getDatabase(context).supplierDictionaryDao()
        val defaults = listOf(
            SupplierDictionary(arabicName = "ابن سينا", englishName = "ibnsinapharma", supplierCode = "29"),
            SupplierDictionary(arabicName = "ibn sina", englishName = "ibnsina", supplierCode = "29"),
            SupplierDictionary(arabicName = "فارما أوفر سيز", englishName = "pharmaoverseas", supplierCode = "38"),
            SupplierDictionary(arabicName = "pharma overseas", englishName = "pharmaoverseas", supplierCode = "38"),
            SupplierDictionary(arabicName = "تبارك", englishName = "tabarak", supplierCode = "218"),
            // OCR may return the brand name in several Arabic/English forms.
            // Keep these aliases tied to the same E-PLUS supplier code.
            SupplierDictionary(arabicName = "تبارك فارما", englishName = "tabark pharma", supplierCode = "218"),
            SupplierDictionary(arabicName = "تبارك مالتي ستورز فارما", englishName = "tabark multistores pharma", supplierCode = "218"),
            SupplierDictionary(arabicName = "tabark", englishName = "tabark", supplierCode = "218"),
            SupplierDictionary(arabicName = "مالتي ستورز", englishName = "multi stores", supplierCode = "218"),
            SupplierDictionary(arabicName = "يونايتد جروب", englishName = "united group", supplierCode = "198"),
            SupplierDictionary(arabicName = "يونايتد جروب فارما", englishName = "united group pharm", supplierCode = "198"),
            SupplierDictionary(arabicName = "يونايتد", englishName = "united", supplierCode = "198"),
            SupplierDictionary(arabicName = "الجمهورية", englishName = "elgomhoria", supplierCode = "198"),
            SupplierDictionary(arabicName = "دريم", englishName = "dream", supplierCode = "175"),
            SupplierDictionary(arabicName = "دريم لمستحضرات التجميل", englishName = "dream cosmetics", supplierCode = "175")
        )
        // Older installations may already have some dictionary rows, so an
        // `isEmpty()` guard would permanently miss newly added aliases such as
        // United Group. Insert only missing exact aliases; Room replaces a row
        // when the same generated id is supplied and otherwise keeps data safe.
        val existing = dao.getAll()
        defaults.filter { candidate ->
            existing.none {
                it.arabicName == candidate.arabicName &&
                    it.englishName == candidate.englishName &&
                    it.supplierCode == candidate.supplierCode
            }
        }.forEach { dao.insert(it) }
    }

    private suspend fun seedDefaultProfiles(context: Context) {
        val dao = AppDatabase.getDatabase(context).supplierProfileDao()
        // Profiles use supplierCode as their primary key, so upserting on every
        // startup also repairs older databases that predate code 198/218.
        dao.insertAll(listOf(
                SupplierProfile(
                    supplierCode = "29",
                    priceFormula = PriceFormula.UNIT_PLUS_EXTRA,
                    taxMode      = TaxMode.PER_ITEM,
                    hasSalePrice = true,
                    hasBonus     = true,
                    columnHint   = "ابن سينا: الاسم | الكمية فوق والبونص تحت | إجمالي الكمية | كود المورد | الصلاحية والتشغيلة | سعر الجمهور (سعر البيع) | سعر الصيدلي | خصم الصيدلي (لا يدخل الحساب) | هامش صيدلي وموزع (خذ رقم الصيدلي فقط) | ضريبة ق.م (إجمالي الصف) | الإجمالي بدون ضريبة"
                ),
                SupplierProfile(
                    supplierCode = "38",
                    priceFormula = PriceFormula.UNIT_PLUS_EXTRA,
                    taxMode      = TaxMode.PER_INVOICE,
                    hasSalePrice = true,
                    hasBonus     = true,
                    columnHint   = "فارما أوفر سيز: الاسم | الشركة | الكمية + البونص | التشغيلة | الصلاحية | سعر الجمهور | ضريبة لكل علبة | الخصم (لا يدخل الحساب) | سعر الصيدلي | إجمالي القيمة | إجمالي ضريبة الصف | الهامش الثابت للصيدلي فقط"
                ),
                SupplierProfile(
                    supplierCode = "175",
                    priceFormula = PriceFormula.UNIT_PRICE,
                    taxMode      = TaxMode.PER_INVOICE,
                    hasSalePrice = false,   // دريم: تجاهل سعر البيع
                    hasBonus     = false,
                    columnHint   = "دريم: الاسم | الكمية | سعر البيع المطبوع = تكلفة الشراء | الإجمالي | سعر المستهلك (مرجع فقط ولا يغير سعر E-PLUS)"
                ),
                SupplierProfile(
                    supplierCode = "198",
                    priceFormula = PriceFormula.LINE_TOTAL_DIVIDED,
                    taxMode      = TaxMode.PER_INVOICE,
                    hasSalePrice = true,
                    hasBonus     = false,
                    columnHint   = "يونايتد: لا يوجد بونص ولا هامش. المسلسل | الصلاحية والتشغيلة | الموقع | الاسم | الكمية | سعر الجمهور (سعر البيع) | الخصم | الإجمالي (إجمالي الشراء). سعر الشراء = الإجمالي ÷ الكمية ويُقارن بسعر الجمهور بعد الخصم."
                ),
                SupplierProfile(
                    supplierCode = "218",
                    priceFormula = PriceFormula.UNIT_PRICE,
                    taxMode      = TaxMode.PER_INVOICE,
                    hasSalePrice = true,
                    hasBonus     = false,
                    columnHint   = "تبارك: لا يوجد بونص ولا هامش. اقرأ الجدول من اليمين لليسار: م/المسلسل (تجاهل) | وزن أو زون (تجاهل) | الشركة (تجاهل) | ك = كود الصنف (ليس سعراً إطلاقاً) | اسم الصنف | الكمية | الوحدة | الرصيد الحالي (تجاهل) | س. بيع = سعر البيع المطبوع | الخصم | الإجمالي/إجمالي التكلفة = إجمالي الشراء. سعر الشراء = الإجمالي ÷ الكمية ويُقارن بسعر البيع بعد الخصم."
                )
            ))
    }

    suspend fun forceReload(context: Context): Int = withContext(Dispatchers.IO) {
        _importProgress.value = 0f
        importFromAssets(context)
        _importProgress.value = 1f
        AppDatabase.getDatabase(context).pharmacyItemDao().getCount()
    }

    suspend fun importFromUri(context: Context, uri: android.net.Uri): Int = withContext(Dispatchers.IO) {
        _importProgress.value = 0f
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                importFromStream(context, input)
            }
        } catch (e: Exception) {
            Log.e("ItemsDatabase", "Error importing from URI: ${e.message}")
        } finally {
            _importProgress.value = 1f
        }
        AppDatabase.getDatabase(context).pharmacyItemDao().getCount()
    }

    private suspend fun importFromAssets(context: Context) {
        try {
            context.assets.open(FILE_NAME).use { input ->
                importFromStream(context, input)
            }
        } catch (e: Exception) {
            Log.e("ItemsDatabase", "Error: ${e.message}")
            _importProgress.value = 1f
        }
    }

    private suspend fun importFromStream(context: Context, inputStream: InputStream) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).pharmacyItemDao()
        dao.deleteAll() 

        // قراءة الملف بترميز Windows-1256 مع دعم UTF-8
        val bytes = inputStream.readBytes()
        val text1256 = try { String(bytes, Charset.forName("Windows-1256")) } catch (e: Exception) { "" }
        val textUtf8 = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { "" }

        // تحديد الترميز الأنسب (لو العربي علامات استفهام في UTF-8 نستخدم 1256)
        val textToUse = if (text1256.count { it in '\u0600'..'\u06FF' } >= textUtf8.count { it in '\u0600'..'\u06FF' }) {
            text1256
        } else {
            textUtf8
        }

        val totalEstimatedLines = 75000
        val batch = mutableListOf<PharmacyItem>()
        var lineCount = 0
        
        textToUse.lineSequence().forEach { line ->
            val item = parseLine(line)
            if (item != null) {
                batch.add(item)
            }
            lineCount++

            if (batch.size >= 1000) {
                dao.insertAll(batch.toList())
                batch.clear()
                _importProgress.value = (lineCount.toFloat() / totalEstimatedLines).coerceAtMost(0.99f)
            }
        }
        if (batch.isNotEmpty()) dao.insertAll(batch)
        _importProgress.value = 1f
    }

    fun parseLine(line: String): PharmacyItem? {
        if (line.isBlank()) return null
        
        // تنظيف السطر من أي رموز غريبة في البداية والنهاية
        val cleanLine = line.trim().replace("\uFEFF", "")
        
        // تقسيم السطر (دعم الفواصل أو التاب)
        val parts = cleanLine.split(Regex("[,\\t;]")).map { it.trim().removeSurrounding("\"") }
        if (parts.size < 2) return null

        val itmCode = parts[0]
        if (itmCode.isEmpty()) return null

        // تجاهل سطر الهيدر لو موجود
        val lowerCode = itmCode.lowercase()
        if (lowerCode in listOf("code", "كود", "itmcode", "item_code", "id", "كود الصنف")) return null

        val nameAr = if (parts.size >= 2) parts[1] else ""
        val nameEn = if (parts.size >= 3) parts[2] else ""
        
        // البحث عن الباركود
        val barcode = parts.find { p -> 
            p.length in 8..15 && p.all { it.isDigit() } && p != itmCode
        } ?: parts.getOrNull(3)?.takeIf { it.all { c -> c.isDigit() } } ?: ""

        return PharmacyItem(itmCode = itmCode, nameAr = nameAr, nameEn = nameEn, barcode = barcode)
    }

    suspend fun search(context: Context, query: String, limit: Int = 100): List<PharmacyItem> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val dao = AppDatabase.getDatabase(context).pharmacyItemDao()

        // 1. إذا كان باركود أو كود صنف صريح
        if (trimmed.length >= 8 && trimmed.all { it.isDigit() }) {
            val direct = dao.searchItems(trimmed, limit)
            if (direct.isNotEmpty()) return direct
        }

        // 2. تقسيم الكلمات والمقاطع بناءً على المسافات
        val rawTokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return emptyList()

        // تجهيز الشروط لكل مقطع مع دعم علامة % كـ Wildcard
        val tokenArgs = mutableListOf<Any>()
        val whereClauses = mutableListOf<String>()

        for (token in rawTokens) {
            val normToken = ArabicNormalizer.normalize(token)
            val pattern1 = if (normToken.contains("%")) "%$normToken%" else "%$normToken%"
            val pattern2 = if (token.contains("%")) "%$token%" else "%$token%"

            whereClauses.add("((nameEn LIKE ? OR nameAr LIKE ?) OR (nameEn LIKE ? OR nameAr LIKE ?))")
            tokenArgs.add(pattern1)
            tokenArgs.add(pattern1)
            tokenArgs.add(pattern2)
            tokenArgs.add(pattern2)
        }

        // بناء استعلام SQL مرن وسريع
        val sql = StringBuilder()
        sql.append("SELECT * FROM pharmacy_items WHERE (")
        sql.append(whereClauses.joinToString(" AND "))
        sql.append(") OR itmCode = ? OR barcode = ? ")
        tokenArgs.add(trimmed)
        tokenArgs.add(trimmed)

        val firstToken = rawTokens.first()
        sql.append(" ORDER BY ")
        sql.append(" CASE ")
        sql.append(" WHEN itmCode = ? THEN 1 ")
        sql.append(" WHEN barcode = ? THEN 2 ")
        sql.append(" WHEN nameEn LIKE ? THEN 3 ")
        sql.append(" WHEN nameAr LIKE ? THEN 4 ")
        sql.append(" ELSE 5 ")
        sql.append(" END ")
        sql.append(" LIMIT ? ")

        tokenArgs.add(trimmed)
        tokenArgs.add(trimmed)
        tokenArgs.add("$firstToken%")
        tokenArgs.add("$firstToken%")
        tokenArgs.add(limit)

        return try {
            val simpleQuery = androidx.sqlite.db.SimpleSQLiteQuery(sql.toString(), tokenArgs.toTypedArray())
            dao.searchRaw(simpleQuery)
        } catch (e: Exception) {
            dao.searchItems(trimmed.replace(" ", "%"), limit)
        }
    }

    suspend fun getByCode(context: Context, itmCode: String): PharmacyItem? =
        AppDatabase.getDatabase(context).pharmacyItemDao().getByCode(itmCode)

    suspend fun count(context: Context) = AppDatabase.getDatabase(context).pharmacyItemDao().getCount()

    suspend fun addNewItem(context: Context, item: PharmacyItem) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).pharmacyItemDao().insertItem(item)
    }

    suspend fun updateItem(context: Context, item: PharmacyItem) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).pharmacyItemDao().updateItem(item)
    }
}
