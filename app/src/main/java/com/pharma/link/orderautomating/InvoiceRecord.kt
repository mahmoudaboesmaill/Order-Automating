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
    val status: String = "success",                 // "success" / "failed"
    // بيانات إضافية للمتابعة داخل سجل الفواتير. لها قيم افتراضية حتى تظل
    // السجلات القديمة قابلة للقراءة بعد ترقية قاعدة البيانات.
    val printedTotal: Double = 0.0,
    val difference: Double = 0.0,
    val matchStatus: String = "unknown",            // match / small_diff / big_diff / missing
    val priceChangesCount: Int = 0,
    val expiryPendingCount: Int = 0,
    val ocrProvider: String = "auto",
    val sourceType: String = "unknown",
    val itemsJson: String = ""
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
