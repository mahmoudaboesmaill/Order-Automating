package com.pharma.link.orderautomating

import androidx.room.*

@Entity(tableName = "supplier_dictionary")
data class SupplierDictionary(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val arabicName: String,      // "ابن سينا"
    val englishName: String,     // "ibnsinapharma"  
    val supplierCode: String     // "29"
)

@Dao
interface SupplierDictionaryDao {
    @Query("SELECT * FROM supplier_dictionary")
    suspend fun getAll(): List<SupplierDictionary>

    @Query("SELECT supplierCode FROM supplier_dictionary WHERE LOWER(arabicName) LIKE '%' || LOWER(:name) || '%' OR LOWER(englishName) LIKE '%' || LOWER(:name) || '%' LIMIT 1")
    suspend fun findByName(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(supplier: SupplierDictionary)

    @Delete
    suspend fun delete(supplier: SupplierDictionary)
}
