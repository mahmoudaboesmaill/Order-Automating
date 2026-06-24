package com.pharma.link.orderautomating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MappingViewModel : ViewModel() {

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _mappedItems = MutableStateFlow<List<OcrItem>>(emptyList())
    val mappedItems: StateFlow<List<OcrItem>> = _mappedItems.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PharmacyItem>>(emptyList())
    val searchResults: StateFlow<List<PharmacyItem>> = _searchResults.asStateFlow()

    // قائمة بالأصناف التي تم تخطيها يدوياً
    private val skippedIndices = mutableSetOf<Int>()

    fun loadMappings(context: Context, supplierCode: String, ocrItems: List<OcrItem>) {
        viewModelScope.launch {
            skippedIndices.clear()
            val db = AppDatabase.getDatabase(context)
            val mappingDao = db.smartMappingDao()
            val pharmacyDao = db.pharmacyItemDao()
            val sCode = supplierCode.trim().lowercase()

            val list = ocrItems.map { item ->
                val mappingKey = ArabicNormalizer.normalize(item.invoiceName)
                val learnedCode = mappingDao.getMappedCode(sCode, mappingKey)
                if (learnedCode != null) {
                    item.copy(itmCode = learnedCode, matched = true)
                } else {
                    val normalizedName = ArabicNormalizer.normalize(item.invoiceName)
                    val exactMatch = pharmacyDao.getByName(normalizedName)
                                  ?: pharmacyDao.getByName(item.invoiceName.trim())
                    if (exactMatch != null) {
                        item.copy(itmCode = exactMatch.itmCode, matched = true)
                    } else {
                        item
                    }
                }
            }
            _mappedItems.value = list
            val firstUnmapped = list.indexOfFirst { !it.matched }
            _currentIndex.value = if (firstUnmapped != -1) firstUnmapped else list.size
        }
    }

    fun search(context: Context, query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            _searchResults.value = ItemsDatabase.search(context, query)
        }
    }

    fun selectItem(context: Context, supplierCode: String, pharmacyItem: PharmacyItem) {
        viewModelScope.launch {
            val idx = _currentIndex.value
            if (idx < 0 || idx >= _mappedItems.value.size) return@launch

            val currentItem = _mappedItems.value[idx]
            val mappingKey = ArabicNormalizer.normalize(currentItem.invoiceName)
            val sCode = supplierCode.trim().lowercase()

            AppDatabase.getDatabase(context).smartMappingDao().insertMapping(
                SmartMapping(sCode, mappingKey, pharmacyItem.itmCode)
            )

            val newList = _mappedItems.value.toMutableList()
            newList[idx] = currentItem.copy(itmCode = pharmacyItem.itmCode, matched = true)
            _mappedItems.value = newList
            
            _searchQuery.value = ""
            _searchResults.value = emptyList()

            moveToNext()
        }
    }

    fun skipCurrent() {
        val idx = _currentIndex.value
        if (idx < 0 || idx >= _mappedItems.value.size) return
        
        skippedIndices.add(idx)
        moveToNext()
    }

    private fun moveToNext() {
        val items = _mappedItems.value
        val idx = _currentIndex.value
        
        // 1. ابحث عن أول صنف "أمامك" لم يتم مطابقتة ولم يتم تخطيه
        var next = items.indices.firstOrNull { i -> i > idx && !items[i].matched && i !in skippedIndices }
        
        // 2. إذا وصلنا للنهاية، ابحث عن أي صنف غير مطابق (بما فيهم اللي اتعملهم تخطي)
        if (next == null) {
            next = items.indices.firstOrNull { i -> !items[i].matched }
            // إذا وجدنا صنفاً كان متخطياً، نزيله من قائمة التخطي لأننا سنعالجه الآن
            if (next != null) skippedIndices.remove(next)
        }
        
        _currentIndex.value = next ?: items.size
    }

    fun goBack(onCancel: () -> Unit) {
        if (_currentIndex.value > 0) {
            _currentIndex.value--
            // عند الرجوع، نمسح الصنف الحالي من قائمة التخطي لو كان فيها
            skippedIndices.remove(_currentIndex.value)
        } else {
            onCancel()
        }
    }
}
