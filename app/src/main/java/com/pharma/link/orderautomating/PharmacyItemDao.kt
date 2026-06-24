package com.pharma.link.orderautomating

import androidx.room.*

@Dao
interface PharmacyItemDao {
    // البحث الشامل والذكي
    @Query("""
        SELECT * FROM pharmacy_items 
        WHERE (nameEn LIKE '%' || :query || '%' 
        OR nameAr LIKE '%' || :query || '%' 
        OR itmCode LIKE '%' || :query || '%' 
        OR barcode LIKE '%' || :query || '%')
        ORDER BY 
            CASE 
                WHEN itmCode = :query THEN 1 
                WHEN barcode = :query THEN 2
                WHEN nameEn LIKE :query || '%' THEN 3
                ELSE 4 
            END
        LIMIT :limit
    """)
    suspend fun searchItems(query: String, limit: Int = 100): List<PharmacyItem>

    @Query("""
        SELECT * FROM pharmacy_items
        WHERE REPLACE(REPLACE(REPLACE(REPLACE(LOWER(nameAr),'أ','ا'),'إ','ا'),'آ','ا'),'ة','ه')
              LIKE '%' || :normalizedQuery || '%'
        ORDER BY
            CASE
                WHEN LOWER(nameAr) = :normalizedQuery THEN 1
                WHEN LOWER(nameEn) = :normalizedQuery THEN 2
                WHEN LOWER(nameAr) LIKE :normalizedQuery || '%' THEN 3
                ELSE 4
            END
        LIMIT :limit
    """)
    suspend fun searchByNormalizedArabic(normalizedQuery: String, limit: Int = 50): List<PharmacyItem>

    @Query("SELECT * FROM pharmacy_items WHERE nameEn = :name LIMIT 1")
    suspend fun getByName(name: String): PharmacyItem?

    @Query("SELECT * FROM pharmacy_items WHERE itmCode = :code LIMIT 1")
    suspend fun getByCode(code: String): PharmacyItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PharmacyItem>)

    @Query("DELETE FROM pharmacy_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pharmacy_items")
    suspend fun getCount(): Int

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: PharmacyItem)

    @androidx.room.Update
    suspend fun updateItem(item: PharmacyItem)
}
