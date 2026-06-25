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

    @Query("""
        SELECT supplierCode FROM supplier_dictionary
        WHERE
            REPLACE(REPLACE(REPLACE(REPLACE(LOWER(arabicName),'أ','ا'),'إ','ا'),'آ','ا'),'ة','ه')
            LIKE '%' || :name || '%'
            OR LOWER(englishName) LIKE '%' || :name || '%'
            OR supplierCode = :name
        LIMIT 1
    """)
    suspend fun findByName(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(supplier: SupplierDictionary)

    @Delete
    suspend fun delete(supplier: SupplierDictionary)
}
