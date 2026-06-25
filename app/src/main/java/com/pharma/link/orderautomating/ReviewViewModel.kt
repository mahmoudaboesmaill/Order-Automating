package com.pharma.link.orderautomating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewViewModel : ViewModel() {

    private lateinit var repository: InvoiceRepository

    private val _supplierCode = MutableStateFlow("")
    val supplierCode: StateFlow<String> = _supplierCode.asStateFlow()

    private val _invoiceNumber = MutableStateFlow("")
    val invoiceNumber: StateFlow<String> = _invoiceNumber.asStateFlow()

    private val _editableItems = MutableStateFlow<List<OcrItem>>(emptyList())
    val editableItems: StateFlow<List<OcrItem>> = _editableItems.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _itemToRemapIndex = MutableStateFlow(-1)
    val itemToRemapIndex: StateFlow<Int> = _itemToRemapIndex.asStateFlow()

    private fun ensureRepository(context: Context) {
        if (!::repository.isInitialized) {
            repository = InvoiceRepository(context.applicationContext)
        }
    }

    fun init(supplierCode: String, invoiceNumber: String, items: List<OcrItem>) {
        if (_editableItems.value.isEmpty()) {
            _supplierCode.value = supplierCode
            _invoiceNumber.value = invoiceNumber
            _editableItems.value = items
        }
    }

    fun updateSupplierCode(code: String) {
        _supplierCode.value = code
    }

    fun updateInvoiceNumber(number: String) {
        _invoiceNumber.value = number
    }

    fun updateItem(index: Int, updatedItem: OcrItem) {
        val newList = _editableItems.value.toMutableList()
        if (index in newList.indices) {
            newList[index] = updatedItem
            _editableItems.value = newList
        }
    }

    fun deleteItem(index: Int) {
        val newList = _editableItems.value.toMutableList()
        if (index in newList.indices) {
            newList.removeAt(index)
            _editableItems.value = newList
        }
    }

    fun setItemToRemap(index: Int) {
        _itemToRemapIndex.value = index
    }

    fun remapItem(context: Context, index: Int, newItem: PharmacyItem) {
        viewModelScope.launch {
            val items = _editableItems.value.toMutableList()
            if (index in items.indices) {
                val currentItem = items[index]
                val mappingKey = ArabicNormalizer.normalize(currentItem.invoiceName)
                val sCode = ArabicNormalizer.normalize(_supplierCode.value)

                AppDatabase.getDatabase(context).smartMappingDao().insertMapping(
                    SmartMapping(sCode, mappingKey, newItem.itmCode)
                )

                items[index] = currentItem.copy(itmCode = newItem.itmCode)
                _editableItems.value = items
                _itemToRemapIndex.value = -1
            }
        }
    }

    fun sendInvoice(context: Context) {
        ensureRepository(context)
        val readyItems = _editableItems.value.filter { it.itmCode.isNotEmpty() }
        if (readyItems.isEmpty()) {
            _status.value = "⚠️ مفيش أصناف مطابقة"
            return
        }
        if (_supplierCode.value.isBlank() || _invoiceNumber.value.isBlank()) {
            _status.value = "⚠️ البيانات الأساسية ناقصة"
            return
        }

        _loading.value = true
        _status.value = "جاري الإرسال..."

        viewModelScope.launch {
            try {
                val invoiceItems = readyItems.map {
                    Item(
                        itmCode = it.itmCode,
                        quantity = it.quantity.toInt(),
                        price = it.price,
                        salePrice = it.salePrice,
                        taxes = it.taxes,
                        discount = 0.0,
                        bonus = it.bonus.toInt()
                    )
                }
                val result = repository.sendInvoice(_supplierCode.value, _invoiceNumber.value, invoiceItems)
                _loading.value = false
                _status.value = result
            } catch (e: Exception) {
                _loading.value = false
                _status.value = "❌ خطأ: ${e.message}"
            }
        }
    }

    fun setStatus(msg: String) {
        _status.value = msg
    }
}
