package com.pharma.link.orderautomating

import androidx.room.*

@Entity(tableName = "ocr_correction_cache",
        primaryKeys = ["supplierCode", "ocrRawText"])
data class OcrCorrectionCache(
    val supplierCode: String,
    val ocrRawText: String,    // النص الخام اللي طلعه Gemini
    val correctedItmCode: String,  // الكود الصحيح اللي اختاره المستخدم
    val correctedName: String,     // الاسم الصحيح للعرض
    val usageCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

@Dao
interface OcrCorrectionCacheDao {
    @Query("""SELECT * FROM ocr_correction_cache
              WHERE supplierCode = :supplierCode
              AND ocrRawText = :rawText LIMIT 1""")
    suspend fun findCorrection(supplierCode: String, rawText: String): OcrCorrectionCache?

    @Query("SELECT * FROM ocr_correction_cache")
    suspend fun getAll(): List<OcrCorrectionCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(cache: OcrCorrectionCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(caches: List<OcrCorrectionCache>)

    @Query("DELETE FROM ocr_correction_cache")
    suspend fun deleteAll()

    @Query("""UPDATE ocr_correction_cache
              SET usageCount = usageCount + 1, lastUsed = :now
              WHERE supplierCode = :supplierCode AND ocrRawText = :rawText""")
    suspend fun incrementUsage(supplierCode: String, rawText: String, now: Long = System.currentTimeMillis())
}
