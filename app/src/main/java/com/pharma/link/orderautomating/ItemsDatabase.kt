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
        if (dao.getAll().isEmpty()) {
            val defaults = listOf(
                SupplierDictionary(arabicName = "ابن سينا", englishName = "ibnsinapharma", supplierCode = "29"),
                SupplierDictionary(arabicName = "ibn sina", englishName = "ibnsina", supplierCode = "29"),
                SupplierDictionary(arabicName = "فارما أوفر سيز", englishName = "pharmaoverseas", supplierCode = "38"),
                SupplierDictionary(arabicName = "pharma overseas", englishName = "pharmaoverseas", supplierCode = "38"),
                SupplierDictionary(arabicName = "تبارك", englishName = "tabarak", supplierCode = "218"),
                SupplierDictionary(arabicName = "مالتي ستورز", englishName = "multi stores", supplierCode = "218"),
                SupplierDictionary(arabicName = "يونايتد جروب", englishName = "united group", supplierCode = "198"),
                SupplierDictionary(arabicName = "دريم", englishName = "dream", supplierCode = "175"),
                SupplierDictionary(arabicName = "دريم لمستحضرات التجميل", englishName = "dream cosmetics", supplierCode = "175")
            )
            defaults.forEach { dao.insert(it) }
        }
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

        val reader = inputStream.bufferedReader(Charset.forName("Windows-1256"))
        
        // تقدير عدد السطور (تقريبي للـ Progress)
        val totalEstimatedLines = 37000 
        
        val batch = mutableListOf<PharmacyItem>()
        var lineCount = 0
        
        while (true) {
            val line = reader.readLine() ?: break
            if (lineCount > 0) { // Skip header
                val item = parseLine(line)
                if (item != null) batch.add(item)
            }
            lineCount++

            if (batch.size >= 1000) {
                dao.insertAll(batch.toList())
                batch.clear()
                // تحديث الـ Progress كل 1000 صنف
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

        val nameAr = if (parts.size >= 2) parts[1] else ""
        val nameEn = if (parts.size >= 3) parts[2] else ""
        
        // ذكاء اصطناعي: نبحث عن أي رقم طوله من 8 لـ 15 رقم في السطر كله ونعتبره باركود
        val barcode = parts.find { p -> 
            p.length in 8..15 && p.all { it.isDigit() } 
        } ?: ""

        return PharmacyItem(itmCode = itmCode, nameAr = nameAr, nameEn = nameEn, barcode = barcode)
    }

    suspend fun search(context: Context, query: String, limit: Int = 100): List<PharmacyItem> {
        val q = ArabicNormalizer.normalize(query)
        if (q.length < 2) return emptyList()
        
        val dao = AppDatabase.getDatabase(context).pharmacyItemDao()
        
        // إذا كان باركود كامل (مثل 13 رقم)، نبحث عنه فوراً
        if (q.length >= 10 && q.all { it.isDigit() }) {
            return dao.searchItems(q, limit)
        }

        // للأسماء: دعم الكلمات المتقطعة
        val words = q.split(" ").filter { it.length >= 2 }
        val searchPattern = if (words.size > 1) words.joinToString("%") { it } else q
        
        val results = dao.searchItems(searchPattern, limit).toMutableList()

        // بحث إضافي بالنص الأصلي لو النتائج قليلة
        if (results.size < 5 && query.trim() != q) {
            val fallback = dao.searchItems(query.trim(), limit)
            fallback.forEach { item ->
                if (results.none { it.itmCode == item.itmCode }) results.add(item)
            }
        }

        // بحث عربي محسّن
        if (q.any { it.code in 0x0600..0x06FF }) {
            val arabicResults = dao.searchByNormalizedArabic(q, 50)
            arabicResults.forEach { item ->
                if (results.none { it.itmCode == item.itmCode }) results.add(item)
            }
        }

        return results.take(limit)
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
