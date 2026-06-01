package com.pharma.link.orderautomating

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedViewModel : ViewModel() {
    
    private val _ocrItems = MutableStateFlow<List<OcrItem>>(emptyList())
    val ocrItems: StateFlow<List<OcrItem>> = _ocrItems.asStateFlow()

    private val _supplierCode = MutableStateFlow("")
    val supplierCode: StateFlow<String> = _supplierCode.asStateFlow()

    private val _invoiceNumber = MutableStateFlow("")
    val invoiceNumber: StateFlow<String> = _invoiceNumber.asStateFlow()

    fun setOcrResult(supplierCode: String, invoiceNumber: String, items: List<OcrItem>) {
        _supplierCode.value = supplierCode
        _invoiceNumber.value = invoiceNumber
        _ocrItems.value = items
    }

    fun updateMappedItems(items: List<OcrItem>) {
        _ocrItems.value = items
    }

    suspend fun findSupplierCodeByName(context: android.content.Context, name: String): String? {
        return AppDatabase.getDatabase(context).supplierDictionaryDao().findByName(name)
    }
}
