package com.pharma.link.orderautomating

import androidx.room.*

// كيفية احتساب سعر الشراء لكل مورد
enum class PriceFormula {
    UNIT_PRICE,           // pPrice = unit_p (الافتراضي)
    UNIT_PLUS_EXTRA,      // pPrice = unit_p + extra  (ابن سينا، أوفر سيز)
    LINE_TOTAL_DIVIDED    // pPrice = line_total / qty
}

// كيفية احتساب الضريبة
enum class TaxMode {
    PER_INVOICE,   // الضريبة على مستوى الفاتورة (الافتراضي)
    PER_ITEM       // الضريبة تُقسَّم على الكمية (ابن سينا)
}

@Entity(tableName = "supplier_profiles")
data class SupplierProfile(
    @PrimaryKey val supplierCode: String,    // "29", "38", "175"...
    val priceFormula: PriceFormula = PriceFormula.UNIT_PRICE,
    val taxMode: TaxMode = TaxMode.PER_INVOICE,
    val hasSalePrice: Boolean = true,        // false = دريم (تجاهل سعر البيع)
    val hasBonus: Boolean = true,            // هل الفاتورة فيها بونص؟
    val columnHint: String = ""              // وصف ترتيب أعمدة الفاتورة (للبرومبت)
)

@Dao
interface SupplierProfileDao {
    @Query("SELECT * FROM supplier_profiles WHERE supplierCode = :code")
    suspend fun getByCode(code: String): SupplierProfile?

    @Query("SELECT * FROM supplier_profiles")
    suspend fun getAll(): List<SupplierProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: SupplierProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<SupplierProfile>)
}
