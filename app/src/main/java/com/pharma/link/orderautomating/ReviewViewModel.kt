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

    private val _validationWarning = MutableStateFlow<String>("")
    val validationWarning: StateFlow<String> = _validationWarning.asStateFlow()

    private fun ensureRepository(context: Context) {
        if (!::repository.isInitialized) {
            repository = InvoiceRepository(context.applicationContext)
        }
    }

    fun init(context: Context, supplierCode: String, invoiceNumber: String, response: OcrResponse?) {
        if (_editableItems.value.isEmpty() && response != null) {
            _supplierCode.value = supplierCode
            _invoiceNumber.value = invoiceNumber
            _editableItems.value = response.items

            val repo = InvoiceRepository(context)
            when (val v = repo.validateInvoice(response)) {
                is ValidationResult.Match ->
                    _validationWarning.value = ""
                is ValidationResult.SmallDiff ->
                    _validationWarning.value =
                        "⚠️ فرق بسيط: محسوب ${String.format("%.2f", v.calculated)} / مطبوع ${String.format("%.2f", v.printed)}"
                is ValidationResult.BigDiff ->
                    _validationWarning.value =
                        "🔴 فرق كبير! محسوب ${String.format("%.2f", v.calculated)} / مطبوع ${String.format("%.2f", v.printed)}\nتحقق من أسعار الفاتورة."
                is ValidationResult.NoPrintedTotal ->
                    _validationWarning.value = ""
            }
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

                items[index] = currentItem.copy(itmCode = newItem.itmCode, matched = true)
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
                    val calculatedPrice = it.unitPrice * (1 - it.discountPercent / 100.0)
                    Item(
                        itmCode = it.itmCode,
                        quantity = it.quantity.toInt(),
                        price = if (it.price != 0.0) it.price else calculatedPrice,
                        salePrice = it.salePrice,
                        taxes = it.taxes,
                        discount = 0.0,
                        bonus = it.bonus.toInt()
                    )
                }
                val result = repository.sendInvoice(_supplierCode.value, _invoiceNumber.value, invoiceItems)
                
                // حفظ السجل بعد الإرسال - استخدام المعادلة الـ deterministic للإجمالي
                val totalPrice = readyItems.sumOf { it.unitPrice * it.quantity * (1 - it.discountPercent / 100.0) }
                AppDatabase.getDatabase(context).invoiceRecordDao().insert(
                    InvoiceRecord(
                        supplierCode  = _supplierCode.value,
                        supplierName  = _supplierCode.value,   // هيتحسن لما نضيف اسم المورد
                        invoiceNumber = _invoiceNumber.value,
                        itemsCount    = invoiceItems.size,
                        totalPrice    = totalPrice,
                        status        = if (result.startsWith("✅")) "success" else "failed"
                    )
                )

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
