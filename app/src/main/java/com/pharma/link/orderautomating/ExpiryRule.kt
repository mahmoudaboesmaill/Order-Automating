package com.pharma.link.orderautomating

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Per-product expiry policy. Dream can mix products with and without expiry. */
object ExpiryMode {
    const val REQUIRED = "required"
    const val NOT_REQUIRED = "not_required"
    const val UNKNOWN = "unknown"
}

@Entity(tableName = "expiry_rules", primaryKeys = ["supplierCode", "itmCode"])
data class ExpiryRule(
    val supplierCode: String,
    val itmCode: String,
    val mode: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ExpiryRuleDao {
    @Query("SELECT * FROM expiry_rules WHERE supplierCode = :supplierCode")
    suspend fun getForSupplier(supplierCode: String): List<ExpiryRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(rule: ExpiryRule)
}
