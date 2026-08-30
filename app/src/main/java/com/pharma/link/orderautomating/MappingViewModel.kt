package com.pharma.link.orderautomating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            val cacheDao = db.ocrCorrectionCacheDao()
            val sCode = supplierCode.trim().lowercase()

            val list = coroutineScope {
                ocrItems.map { item ->
                    async(Dispatchers.IO) {
                        // كل صف مستقل؛ تنفيذ البحث بالتوازي يقلل زمن ظهور شاشة المطابقة.
                        val rawText = ArabicNormalizer.normalize(item.invoiceName)
                        val cached = cacheDao.findCorrection(sCode, rawText)
                        if (cached != null) {
                            cacheDao.incrementUsage(sCode, rawText)
                            return@async item.copy(itmCode = cached.correctedItmCode, matched = true)
                        }

                        val learnedCode = mappingDao.getMappedCode(sCode, rawText)
                        if (learnedCode != null) {
                            return@async item.copy(itmCode = learnedCode, matched = true)
                        }

                        val exactMatch = pharmacyDao.getByName(rawText)
                            ?: pharmacyDao.getByName(item.invoiceName.trim())
                        if (exactMatch != null) {
                            return@async item.copy(itmCode = exactMatch.itmCode, matched = true)
                        }

                        val allItems = pharmacyDao.searchItems(rawText.take(4), 50)
                        when (val fuzzy = FuzzyMatcher.findBestMatch(item.invoiceName, allItems)) {
                            is FuzzyMatcher.MatchResult.AutoMatch -> item.copy(
                                itmCode = fuzzy.item.itmCode,
                                matched = true
                            )
                            is FuzzyMatcher.MatchResult.Suggestion -> item.copy(
                                itmCode = fuzzy.item.itmCode,
                                matched = false,
                                fuzzyScore = fuzzy.score
                            )
                            else -> item
                        }
                    }
                }.awaitAll()
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

    /**
     * وظيفة ذكية: تبحث عن الباركود، وإذا وجدت صنفاً واحداً فقط مطابقاً تماماً، تختاره وتنتقل للتالي
     */
    fun selectByBarcode(context: Context, supplierCode: String, barcode: String) {
        _searchQuery.value = barcode
        viewModelScope.launch {
            val results = ItemsDatabase.search(context, barcode)
            _searchResults.value = results
            
            // إذا وجدنا صنفاً واحداً فقط مطابقاً تماماً للباركود
            if (results.size == 1 && results[0].barcode == barcode) {
                selectItem(context, supplierCode, results[0])
            }
        }
    }

    fun selectItem(context: Context, supplierCode: String, pharmacyItem: PharmacyItem) {
        viewModelScope.launch {
            val idx = _currentIndex.value
            if (idx < 0 || idx >= _mappedItems.value.size) return@launch

            val currentItem = _mappedItems.value[idx]
            MappingLearningRepository(context).save(
                supplierCode = supplierCode,
                invoiceName = currentItem.invoiceName,
                item = pharmacyItem
            )

            val newList = _mappedItems.value.toMutableList()
            newList[idx] = currentItem.copy(itmCode = pharmacyItem.itmCode, matched = true)
            _mappedItems.value = newList
            skippedIndices.remove(idx)
            
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
        
        // 1. ابحث عن أول صنف "أمامك" لم يتم مطابقته ولم يتم تخطيه
        val next = items.indices.firstOrNull { i -> i > idx && !items[i].matched && i !in skippedIndices }
        
        if (next != null) {
            _currentIndex.value = next
        } else {
            // 2. إذا لم نجد في الأمام، ابحث عن أي صنف سابق لم يتم مطابقته ولم يتم تخطيه
            val wrapAround = items.indices.firstOrNull { i -> !items[i].matched && i !in skippedIndices }
            if (wrapAround != null) {
                _currentIndex.value = wrapAround
            } else {
                // 3. كل الأصناف إما تمت مطابقتها أو تم تخطيها صراحة -> انتقل للمراجعة
                _currentIndex.value = items.size
            }
        }
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
