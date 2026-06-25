package com.pharma.link.orderautomating

import androidx.room.*

@Entity(tableName = "invoice_records")
data class InvoiceRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val supplierCode: String,
    val supplierName: String,       // اسم المورد للعرض
    val invoiceNumber: String,
    val itemsCount: Int,
    val totalPrice: Double,
    val sentAt: Long = System.currentTimeMillis(),  // timestamp
    val status: String = "success"                  // "success" / "failed"
)

@Dao
interface InvoiceRecordDao {
    @Query("SELECT * FROM invoice_records ORDER BY sentAt DESC")
    suspend fun getAll(): List<InvoiceRecord>

    @Query("SELECT * FROM invoice_records ORDER BY sentAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<InvoiceRecord>

    @Query("SELECT COUNT(*) FROM invoice_records WHERE status = 'success'")
    suspend fun getSuccessCount(): Int

    @Insert
    suspend fun insert(record: InvoiceRecord)

    @Query("DELETE FROM invoice_records WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM invoice_records")
    suspend fun deleteAll()
}
