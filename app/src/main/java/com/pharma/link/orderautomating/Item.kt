package com.pharma.link.orderautomating

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class Item(
    @PrimaryKey val itmCode: String,
    val quantity: Int,
    val price: Double,      // سعر الشراء
    val salePrice: Double,  // سعر البيع
    val taxes: Double = 0.0, // خانة الضريبة الجديدة
    val discount: Double = 0.0,
    val bonus: Int = 0,
    // دريم: يحدّث التكلفة فقط ولا يلمس سعر البيع في E-PLUS.
    val updateSalePrice: Boolean = true,
    // يحدد الحقل الذي يدخل سجل التغييرات على الكمبيوتر.
    val priceAlertKind: String = SupplierInvoiceRules.ALERT_SALE_PRICE,
    val invoiceName: String = ""
)
