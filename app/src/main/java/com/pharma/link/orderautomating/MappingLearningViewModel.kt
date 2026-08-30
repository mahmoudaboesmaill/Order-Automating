package com.pharma.link.orderautomating

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrainingCandidate(
    val supplierCode: String,
    val invoiceName: String,
    val knownMapping: LearnedMapping? = null
)

class MappingLearningViewModel : ViewModel() {
    private val _mappings = MutableStateFlow<List<LearnedMapping>>(emptyList())
    val mappings: StateFlow<List<LearnedMapping>> = _mappings.asStateFlow()

    private val _trainingCandidates = MutableStateFlow<List<TrainingCandidate>>(emptyList())
    val trainingCandidates: StateFlow<List<TrainingCandidate>> = _trainingCandidates.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun loadMappings(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { refreshMappings(context.applicationContext) }
                .onFailure { _status.value = "تعذر تحميل المطابقات: ${it.message}" }
        }
    }

    fun saveMapping(
        context: Context,
        supplierCode: String,
        invoiceName: String,
        item: PharmacyItem
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                MappingLearningRepository(context).save(supplierCode, invoiceName, item)
                refreshMappings(context.applicationContext)
                _status.value = "تم حفظ مطابقة \"$invoiceName\" مع ${item.nameAr.ifBlank { item.nameEn }}"
            }.onFailure { _status.value = "تعذر حفظ المطابقة: ${it.message}" }
        }
    }

    fun forgetMapping(context: Context, mapping: LearnedMapping) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                MappingLearningRepository(context).forget(mapping.supplierCode, mapping.invoiceName)
                refreshMappings(context.applicationContext)
                _status.value = "تم نسيان المطابقة"
            }.onFailure { _status.value = "تعذر حذف المطابقة: ${it.message}" }
        }
    }

    fun analyzeDocuments(context: Context, uris: List<Uri>, supplierCode: String) {
        val safeSupplier = supplierCode.trim()
        if (safeSupplier.isBlank()) {
            _status.value = "أدخل كود المورد قبل اختيار الفواتير"
            return
        }
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _isBusy.value = true
            try {
                val repository = InvoiceRepository(context.applicationContext)
                val extracted = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    _status.value = "جاري قراءة فاتورة ${index + 1} من ${uris.size}..."
                    val document = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw Exception("تعذر فتح الملف المختار")
                        val mimeType = context.contentResolver.getType(uri).orEmpty()
                        bytes to mimeType
                    }
                    extracted += repository.extractTrainingItemNames(
                        bytes = document.first,
                        mimeType = document.second,
                        supplierCode = safeSupplier
                    )
                }

                val names = deduplicateTrainingNames(extracted)
                val learned = withContext(Dispatchers.IO) {
                    MappingLearningRepository(context).getAll()
                }
                val normalizedSupplier = safeSupplier.lowercase()
                val knownByName = learned
                    .filter { it.supplierCode == normalizedSupplier }
                    .associateBy { ArabicNormalizer.normalize(it.invoiceName) }
                _trainingCandidates.value = names.map { name ->
                    TrainingCandidate(
                        supplierCode = normalizedSupplier,
                        invoiceName = name,
                        knownMapping = knownByName[ArabicNormalizer.normalize(name)]
                    )
                }
                val knownCount = _trainingCandidates.value.count { it.knownMapping != null }
                _status.value = "تم استخراج ${names.size} صنف: ${names.size - knownCount} جديد و$knownCount محفوظ سابقاً"
            } catch (error: Exception) {
                _status.value = error.message ?: "تعذر قراءة فواتير التدريب"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearTraining() {
        _trainingCandidates.value = emptyList()
        _status.value = ""
    }

    private suspend fun refreshMappings(context: Context) {
        val learned = MappingLearningRepository(context).getAll()
        _mappings.value = learned
        _trainingCandidates.value = _trainingCandidates.value.map { candidate ->
            candidate.copy(
                knownMapping = learned.firstOrNull {
                    it.supplierCode == candidate.supplierCode &&
                        ArabicNormalizer.normalize(it.invoiceName) == ArabicNormalizer.normalize(candidate.invoiceName)
                }
            )
        }
    }
}
