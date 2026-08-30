package com.pharma.link.orderautomating

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    private val _sendSession = MutableStateFlow<SendSessionState?>(null)
    val sendSession: StateFlow<SendSessionState?> = _sendSession.asStateFlow()

    private val _sessionActionLoading = MutableStateFlow(false)
    val sessionActionLoading: StateFlow<Boolean> = _sessionActionLoading.asStateFlow()

    private val _itemToRemapIndex = MutableStateFlow(-1)
    val itemToRemapIndex: StateFlow<Int> = _itemToRemapIndex.asStateFlow()

    private val _invoiceTotalCheck = MutableStateFlow(InvoiceTotalCheck())
    val invoiceTotalCheck: StateFlow<InvoiceTotalCheck> = _invoiceTotalCheck.asStateFlow()

    private val _ignorePharmaWarnings = MutableStateFlow(false)
    val ignorePharmaWarnings: StateFlow<Boolean> = _ignorePharmaWarnings.asStateFlow()

    private var printedInvoiceTotal: Double = 0.0
    private var invoiceSourceType: String = "unknown"
    private var sendWakeLock: PowerManager.WakeLock? = null
    private var sessionPollingJob: Job? = null

    // Compose may call init() again while the review screen is recomposing.
    // Keep user edits intact and only load a response when it is a new invoice.
    private var initializedInvoiceKey: String? = null

    private fun ensureRepository(context: Context) {
        if (!::repository.isInitialized) {
            repository = InvoiceRepository(context.applicationContext)
        }
    }

    fun init(context: Context, supplierCode: String, invoiceNumber: String, response: OcrResponse?) {
        ensureRepository(context)
        // A repeated OCR of the same invoice number is still a new result and
        // must replace the previous review values rather than looking cached.
        val invoiceKey = "$supplierCode|$invoiceNumber|${response?.let { System.identityHashCode(it) }}"
        if (response != null && initializedInvoiceKey != invoiceKey) {
            _supplierCode.value = supplierCode
            _invoiceNumber.value = invoiceNumber
            val defaultItems = response.items.map { item ->
                if (supplierCode.trim() == "175" && item.expiryMonth.isBlank() && item.expiryYear.isBlank() && item.expiryMode == ExpiryMode.REQUIRED) {
                    item.copy(expiryMode = ExpiryMode.UNKNOWN)
                } else item
            }
            _editableItems.value = defaultItems
            _ignorePharmaWarnings.value = false
            printedInvoiceTotal = response.invoiceTotalAsPrinted
            invoiceSourceType = response.sourceType
            refreshInvoiceTotalCheck()
            initializedInvoiceKey = invoiceKey

            // تطبيق قرار الصلاحية المحفوظ سابقاً لكل صنف بعد اكتمال المطابقة.
            viewModelScope.launch {
                val rules = AppDatabase.getDatabase(context).expiryRuleDao()
                    .getForSupplier(supplierCode.trim())
                    .associateBy { it.itmCode }
                if (initializedInvoiceKey == invoiceKey) {
                    _editableItems.value = _editableItems.value.map { item ->
                        rules[item.itmCode]?.let { rule -> item.copy(expiryMode = rule.mode) } ?: item
                    }
                    refreshInvoiceTotalCheck()
                }
            }
        }
        startSessionPolling()
    }

    private fun startSessionPolling() {
        if (!::repository.isInitialized) return
        sessionPollingJob?.cancel()
        sessionPollingJob = viewModelScope.launch {
            while (true) {
                val state = repository.getSendSession()
                if (state.error.isBlank()) {
                    _sendSession.value = state.takeIf { it.exists }
                }
                if (state.status !in setOf("launching", "running", "cancelling")) {
                    break
                }
                delay(2_000)
            }
        }
    }

    private fun applySessionActionResult(state: SendSessionState) {
        if (state.exists) {
            _sendSession.value = state
        }
        if (state.error.isNotBlank()) {
            _status.value = "⚠️ ${state.error}"
        }
        if (state.status in setOf("launching", "running", "cancelling")) {
            startSessionPolling()
        }
    }

    fun resumeSendSession(context: Context, resolution: String? = null) {
        ensureRepository(context)
        _sessionActionLoading.value = true
        viewModelScope.launch {
            applySessionActionResult(repository.resumeSendSession(resolution))
            _sessionActionLoading.value = false
        }
    }

    fun cancelSendSession(context: Context) {
        ensureRepository(context)
        _sessionActionLoading.value = true
        viewModelScope.launch {
            applySessionActionResult(repository.cancelSendSession())
            _sessionActionLoading.value = false
        }
    }

    fun restartSendSession(context: Context) {
        ensureRepository(context)
        _sessionActionLoading.value = true
        viewModelScope.launch {
            applySessionActionResult(repository.restartSendSession())
            _sessionActionLoading.value = false
        }
    }

    private fun refreshInvoiceTotalCheck() {
        _invoiceTotalCheck.value = InvoiceTotalCheck.from(
            printedTotal = printedInvoiceTotal,
            items = _editableItems.value
        )
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
            val previous = newList[index]
            val manuallyCorrected = !previous.purchasePriceMethodsMatch && (
                previous.price != updatedItem.price ||
                    previous.taxes != updatedItem.taxes ||
                    previous.quantity != updatedItem.quantity
                )
            newList[index] = if (manuallyCorrected) {
                updatedItem.copy(
                    purchasePriceMethodsMatch = true,
                    pharmaValidationWarnings = emptyList()
                )
            } else updatedItem
            _editableItems.value = newList
            refreshInvoiceTotalCheck()
        }
    }

    fun deleteItem(index: Int) {
        val newList = _editableItems.value.toMutableList()
        if (index in newList.indices) {
            newList.removeAt(index)
            _editableItems.value = newList
            refreshInvoiceTotalCheck()
        }
    }

    fun splitItem(index: Int, splitQuantity: Double): Boolean {
        val updatedItems = splitReviewItemList(_editableItems.value, index, splitQuantity)
            ?: return false
        _editableItems.value = updatedItems
        refreshInvoiceTotalCheck()
        return true
    }

    fun mergeItem(index: Int): Boolean {
        val updatedItems = mergeReviewItemList(_editableItems.value, index)
            ?: return false
        _editableItems.value = updatedItems
        refreshInvoiceTotalCheck()
        return true
    }

    fun setItemToRemap(index: Int) {
        _itemToRemapIndex.value = index
    }

    fun remapItem(context: Context, index: Int, newItem: PharmacyItem) {
        val correctingAfterSend = _status.value.startsWith("✅")
        viewModelScope.launch {
            val items = _editableItems.value.toMutableList()
            if (index in items.indices) {
                val currentItem = items[index]
                val sCode = _supplierCode.value.trim().lowercase()
                val previousCode = currentItem.itmCode
                val database = AppDatabase.getDatabase(context)

                MappingLearningRepository(context).save(
                    supplierCode = sCode,
                    invoiceName = currentItem.invoiceName,
                    item = newItem
                )

                val savedMode = database.expiryRuleDao()
                    .getForSupplier(_supplierCode.value.trim())
                    .firstOrNull { it.itmCode == newItem.itmCode }
                    ?.mode
                items[index] = currentItem.copy(
                    itmCode = newItem.itmCode,
                    matched = true,
                    expiryMode = savedMode ?: if (_supplierCode.value.trim() == "175") ExpiryMode.UNKNOWN else ExpiryMode.REQUIRED
                )
                _editableItems.value = items
                refreshInvoiceTotalCheck()
                _itemToRemapIndex.value = -1
                if (correctingAfterSend) {
                    _status.value = "✅ تم تصحيح مطابقة \"${currentItem.invoiceName}\" من كود " +
                        "${previousCode.ifBlank { "—" }} إلى ${newItem.itmCode} داخل التطبيق. " +
                        "صحح السطر الحالي يدويًا في E-PLUS ولا تعِد إرسال الفاتورة."
                }
            }
        }
    }

    fun sendInvoice(context: Context) {
        ensureRepository(context)
        val allItems = _editableItems.value
        val unresolved = allItems.filter { it.itmCode.isBlank() || !it.matched }
        if (allItems.isEmpty()) {
            _status.value = "⚠️ مفيش أصناف في الفاتورة"
            return
        }
        if (unresolved.isNotEmpty()) {
            _status.value = "⚠️ لا يمكن الإرسال: ${unresolved.size} صنف يحتاج مطابقة مؤكدة أو حذفاً من الفاتورة"
            return
        }
        val inconsistentPrices = allItems.filter { !it.purchasePriceMethodsMatch }
        if (inconsistentPrices.isNotEmpty() && !_ignorePharmaWarnings.value) {
            _status.value = "⚠️ راجع الكروت الحمراء أو اختر تجاهل التحذير"
            return
        }
        if (_supplierCode.value.isBlank() || _invoiceNumber.value.isBlank()) {
            _status.value = "⚠️ البيانات الأساسية ناقصة"
            return
        }

        _loading.value = true
        _status.value = "جاري الإرسال..."
        acquireSendLock(context)

        viewModelScope.launch {
            try {
                val sendLines = allItems.map {
                    InvoiceSendLine(
                        item = Item(
                            itmCode = it.itmCode,
                            quantity = it.quantity.toInt(),
                            price = it.price,
                            salePrice = it.salePrice,
                            taxes = it.taxes,
                            discount = 0.0,
                            bonus = it.bonus.toInt(),
                            updateSalePrice = it.shouldUpdateSalePrice,
                            priceAlertKind = it.priceAlertKind,
                            invoiceName = it.invoiceName
                        ),
                        expiryMonth = it.expiryMonth,
                        expiryYear = it.expiryYear
                    )
                }
                val result = repository.sendInvoice(_supplierCode.value, _invoiceNumber.value, sendLines)
                
                // حفظ نسخة غنية من الفاتورة حتى يمكن مراجعتها لاحقاً دون إعادة OCR.
                val totalCheck = _invoiceTotalCheck.value
                val totalPrice = totalCheck.calculatedTotal
                val matchStatus = when {
                    !totalCheck.hasPrintedTotal -> "missing"
                    totalCheck.matches -> "match"
                    totalCheck.withinOnePound -> "small_diff"
                    else -> "big_diff"
                }
                val priceChangesCount = allItems.count {
                    it.shouldUpdateSalePrice && it.salePrice > 0.0
                }
                val expiryPendingCount = allItems.count {
                    it.expiryMode == ExpiryMode.REQUIRED &&
                        (it.expiryMonth.length != 2 || it.expiryYear.length != 2)
                }
                AppDatabase.getDatabase(context).invoiceRecordDao().insert(
                    InvoiceRecord(
                        supplierCode  = _supplierCode.value,
                        supplierName  = supplierDisplayName(_supplierCode.value),
                        invoiceNumber = _invoiceNumber.value,
                        itemsCount    = sendLines.size,
                        totalPrice    = totalPrice,
                        status        = if (result.startsWith("✅")) "success" else "failed",
                        printedTotal  = totalCheck.printedTotal,
                        difference    = totalCheck.difference,
                        matchStatus   = matchStatus,
                        priceChangesCount = priceChangesCount,
                        expiryPendingCount = expiryPendingCount,
                        ocrProvider   = ServerManager.getOcrProvider(context),
                        sourceType    = invoiceSourceType,
                        itemsJson     = serializeHistoryItems(allItems)
                    )
                )

                _loading.value = false
                _status.value = result
                startSessionPolling()
            } catch (e: Exception) {
                _loading.value = false
                _status.value = "❌ خطأ: ${e.message}"
            } finally {
                releaseSendLock()
            }
        }
    }

    fun setStatus(msg: String) {
        _status.value = msg
    }

    fun setIgnorePharmaWarnings(ignore: Boolean) {
        _ignorePharmaWarnings.value = ignore
        _status.value = if (ignore) {
            "⚠️ تم السماح بالإرسال رغم تعارض بيانات فارما بناءً على مراجعتك اليدوية"
        } else ""
    }

    fun setExpiryMode(context: Context, index: Int, mode: String) {
        if (mode !in setOf(ExpiryMode.REQUIRED, ExpiryMode.NOT_REQUIRED, ExpiryMode.UNKNOWN)) return
        val items = _editableItems.value.toMutableList()
        if (index !in items.indices) return
        val current = items[index]
        items[index] = current.copy(
            expiryMode = mode,
            expiryMonth = if (mode == ExpiryMode.NOT_REQUIRED) "" else current.expiryMonth,
            expiryYear = if (mode == ExpiryMode.NOT_REQUIRED) "" else current.expiryYear
        )
        _editableItems.value = items
        refreshInvoiceTotalCheck()
        if (_supplierCode.value.isNotBlank() && current.itmCode.isNotBlank()) {
            viewModelScope.launch {
                AppDatabase.getDatabase(context).expiryRuleDao().save(
                    ExpiryRule(_supplierCode.value.trim(), current.itmCode, mode)
                )
            }
        }
    }

    private fun serializeHistoryItems(items: List<OcrItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("name", item.invoiceName)
                put("quantity", item.quantity)
                put("bonus", item.bonus)
                put("purchasePrice", item.price)
                put("salePrice", item.salePrice)
                put("tax", item.taxes)
                put("expiry", if (item.expiryMonth.length == 2 && item.expiryYear.length == 2) {
                    "${item.expiryMonth}/${item.expiryYear}"
                } else "")
                put("expiryMode", item.expiryMode)
                put("itmCode", item.itmCode)
                put("updateSalePrice", item.shouldUpdateSalePrice)
            })
        }
        return array.toString()
    }

    private fun acquireSendLock(context: Context) {
        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (sendWakeLock?.isHeld == true) return
        sendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrderAutomating:InvoiceSend").apply {
            acquire(4 * 60 * 1000L)
        }
    }

    private fun releaseSendLock() {
        sendWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        sendWakeLock = null
    }

    override fun onCleared() {
        releaseSendLock()
        super.onCleared()
    }
}

internal fun findMergeCandidateIndex(items: List<OcrItem>, index: Int): Int? {
    if (index !in items.indices) return null
    val itemCode = items[index].itmCode.trim()
    if (itemCode.isEmpty()) return null
    return items.indices.firstOrNull { candidateIndex ->
        candidateIndex != index && items[candidateIndex].itmCode.trim() == itemCode
    }
}

internal fun splitReviewItemList(
    items: List<OcrItem>,
    index: Int,
    splitQuantity: Double
): List<OcrItem>? {
    if (index !in items.indices || !splitQuantity.isFinite()) return null
    val original = items[index]
    if (splitQuantity <= 0.0 || splitQuantity >= original.quantity) return null

    return items.toMutableList().apply {
        this[index] = original.copy(quantity = original.quantity - splitQuantity)
        add(index + 1, original.copy(quantity = splitQuantity))
    }
}

internal fun mergeReviewItemList(items: List<OcrItem>, index: Int): List<OcrItem>? {
    val candidateIndex = findMergeCandidateIndex(items, index) ?: return null
    val keepIndex = minOf(index, candidateIndex)
    val removeIndex = maxOf(index, candidateIndex)
    val keptItem = items[keepIndex]
    val removedItem = items[removeIndex]

    return items.toMutableList().apply {
        this[keepIndex] = keptItem.copy(quantity = keptItem.quantity + removedItem.quantity)
        removeAt(removeIndex)
    }
}
