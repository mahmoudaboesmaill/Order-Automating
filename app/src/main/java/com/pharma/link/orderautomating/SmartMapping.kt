package com.pharma.link.orderautomating

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "smart_mapping", primaryKeys = ["supplierCode", "invoiceName"])
data class SmartMapping(
    val supplierCode: String,
    val invoiceName: String,
    val itmCode: String // كود برنامج E-Plus
)

@Dao
interface SmartMappingDao {
    @Query("SELECT itmCode FROM smart_mapping WHERE supplierCode = :supplierCode AND invoiceName = :invoiceName LIMIT 1")
    suspend fun getMappedCode(supplierCode: String, invoiceName: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: SmartMapping)

    @Query("SELECT COUNT(*) FROM smart_mapping")
    suspend fun getCount(): Int
}
