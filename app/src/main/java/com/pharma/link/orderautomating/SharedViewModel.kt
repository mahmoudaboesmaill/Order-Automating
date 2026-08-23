package com.pharma.link.orderautomating

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedViewModel : ViewModel() {
    
    private val _ocrResponse = MutableStateFlow<OcrResponse?>(null)
    val ocrResponse: StateFlow<OcrResponse?> = _ocrResponse.asStateFlow()

    private val _ocrItems = MutableStateFlow<List<OcrItem>>(emptyList())
    val ocrItems: StateFlow<List<OcrItem>> = _ocrItems.asStateFlow()

    private val _supplierCode = MutableStateFlow("")
    val supplierCode: StateFlow<String> = _supplierCode.asStateFlow()

    private val _invoiceNumber = MutableStateFlow("")
    val invoiceNumber: StateFlow<String> = _invoiceNumber.asStateFlow()

    // Unknown-supplier dialogs must survive rotation while the user decides
    // which E-PLUS supplier code belongs to the invoice.
    private val _pendingSupplierResponse = MutableStateFlow<OcrResponse?>(null)
    val pendingSupplierResponse: StateFlow<OcrResponse?> = _pendingSupplierResponse.asStateFlow()

    fun setPendingSupplierResponse(response: OcrResponse?) {
        _pendingSupplierResponse.value = response
    }

    fun setOcrResult(supplierCode: String, invoiceNumber: String, response: OcrResponse) {
        _supplierCode.value = supplierCode
        _invoiceNumber.value = invoiceNumber
        _ocrResponse.value = response
        _ocrItems.value = response.items
    }

    fun updateMappedItems(items: List<OcrItem>) {
        _ocrItems.value = items
        val currentResp = _ocrResponse.value
        _ocrResponse.value = currentResp?.copy(items = items) ?: OcrResponse(
            supplierName = _supplierCode.value,
            invoiceNumber = _invoiceNumber.value,
            items = items
        )
    }

    suspend fun findSupplierCodeByName(context: android.content.Context, name: String): String? {
        val dao = AppDatabase.getDatabase(context).supplierDictionaryDao()
        dao.findByName(name)?.let { return it }

        // Older SQLite rows and OCR output can differ in Arabic letter forms or
        // spacing. Keep a normalized in-memory fallback so a known supplier is
        // not sent to the manual "unknown" dialog just because SQL LIKE missed
        // the spelling variant.
        val normalized = ArabicNormalizer.normalize(name)
        return dao.getAll().firstOrNull { supplier ->
            val arabic = ArabicNormalizer.normalize(supplier.arabicName)
            val english = supplier.englishName.lowercase()
            normalized == supplier.supplierCode ||
                normalized.contains(arabic) ||
                arabic.contains(normalized) ||
                normalized.contains(english)
        }?.supplierCode
    }
}
