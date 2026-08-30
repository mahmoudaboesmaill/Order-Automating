package com.pharma.link.orderautomating

import android.content.Context
import androidx.room.withTransaction

data class LearnedMapping(
    val supplierCode: String,
    val invoiceName: String,
    val itmCode: String,
    val itemName: String,
    val usageCount: Int = 0,
    val lastUsed: Long = 0L,
    val isOrphaned: Boolean = false
)

internal data class MappingWrite(
    val smartMapping: SmartMapping,
    val correction: OcrCorrectionCache
)

internal fun buildMappingWrite(
    supplierCode: String,
    invoiceName: String,
    item: PharmacyItem,
    usageCount: Int = 1
): MappingWrite {
    val normalizedSupplier = supplierCode.trim().lowercase()
    val normalizedName = ArabicNormalizer.normalize(invoiceName)
    require(normalizedSupplier.isNotBlank()) { "كود المورد مطلوب" }
    require(normalizedName.isNotBlank()) { "اسم الصنف في الفاتورة مطلوب" }
    require(item.itmCode.isNotBlank()) { "كود صنف E-PLUS مطلوب" }
    return MappingWrite(
        smartMapping = SmartMapping(normalizedSupplier, normalizedName, item.itmCode),
        correction = OcrCorrectionCache(
            supplierCode = normalizedSupplier,
            ocrRawText = normalizedName,
            correctedItmCode = item.itmCode,
            correctedName = item.nameAr.ifBlank { item.nameEn },
            usageCount = usageCount.coerceAtLeast(1),
            lastUsed = System.currentTimeMillis()
        )
    )
}

internal fun deduplicateTrainingNames(names: List<String>): List<String> {
    val unique = linkedMapOf<String, String>()
    names.forEach { rawName ->
        val displayName = rawName.trim()
        val key = ArabicNormalizer.normalize(displayName)
        if (key.isNotBlank() && key !in unique) unique[key] = displayName
    }
    return unique.values.toList()
}

class MappingLearningRepository(context: Context) {
    private val database = AppDatabase.getDatabase(context.applicationContext)

    suspend fun getAll(): List<LearnedMapping> {
        val smartMappings = database.smartMappingDao().getAll()
        val corrections = database.ocrCorrectionCacheDao().getAll()
        val smartByKey = smartMappings.associateBy { it.supplierCode to it.invoiceName }
        val correctionByKey = corrections.associateBy { it.supplierCode to it.ocrRawText }
        val keys = (smartByKey.keys + correctionByKey.keys).distinct()
        val itemsByCode = mutableMapOf<String, PharmacyItem?>()

        return keys.map { key ->
            val smart = smartByKey[key]
            val correction = correctionByKey[key]
            val code = correction?.correctedItmCode ?: smart?.itmCode.orEmpty()
            val pharmacyItem = itemsByCode.getOrPut(code) {
                if (code.isBlank()) null else database.pharmacyItemDao().getByCode(code)
            }
            LearnedMapping(
                supplierCode = key.first,
                invoiceName = key.second,
                itmCode = code,
                itemName = pharmacyItem?.nameAr?.ifBlank { pharmacyItem.nameEn }
                    ?: correction?.correctedName.orEmpty(),
                usageCount = correction?.usageCount ?: 0,
                lastUsed = correction?.lastUsed ?: 0L,
                isOrphaned = code.isBlank() || pharmacyItem == null
            )
        }.sortedWith(compareBy<LearnedMapping> { it.supplierCode }.thenBy { it.invoiceName })
    }

    suspend fun save(supplierCode: String, invoiceName: String, item: PharmacyItem) {
        val normalizedSupplier = supplierCode.trim().lowercase()
        val normalizedName = ArabicNormalizer.normalize(invoiceName)
        val existingUsage = database.ocrCorrectionCacheDao()
            .findCorrection(normalizedSupplier, normalizedName)
            ?.usageCount ?: 1
        val write = buildMappingWrite(supplierCode, invoiceName, item, existingUsage)
        database.withTransaction {
            database.smartMappingDao().insertMapping(write.smartMapping)
            database.ocrCorrectionCacheDao().insertOrReplace(write.correction)
        }
    }

    suspend fun forget(supplierCode: String, invoiceName: String) {
        val normalizedSupplier = supplierCode.trim().lowercase()
        val normalizedName = ArabicNormalizer.normalize(invoiceName)
        database.withTransaction {
            // Imported legacy backups may contain unnormalised keys, so delete
            // both the exact row shown to the user and the current lookup key.
            database.smartMappingDao().deleteMapping(supplierCode, invoiceName)
            database.ocrCorrectionCacheDao().deleteCorrection(supplierCode, invoiceName)
            database.smartMappingDao().deleteMapping(normalizedSupplier, normalizedName)
            database.ocrCorrectionCacheDao().deleteCorrection(normalizedSupplier, normalizedName)
        }
    }
}
