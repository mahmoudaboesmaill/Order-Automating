package com.pharma.link.orderautomating

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InvoiceOcrViewModel : ViewModel() {

    private lateinit var repository: InvoiceRepository

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _showCamera = MutableStateFlow(false)
    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap.asStateFlow()

    private val _pdfCandidates = MutableStateFlow<List<PdfInvoiceCandidate>>(emptyList())
    val pdfCandidates: StateFlow<List<PdfInvoiceCandidate>> = _pdfCandidates.asStateFlow()

    // Keep OCR results as ViewModel state so a rotation cannot lose the
    // response or invoke a callback tied to a destroyed composition.
    private val _result = MutableStateFlow<OcrResponse?>(null)
    val result: StateFlow<OcrResponse?> = _result.asStateFlow()

    private var pendingPdfBytes: ByteArray? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun ensureRepository(context: Context) {
        if (!::repository.isInitialized) {
            repository = InvoiceRepository(context.applicationContext)
        }
    }

    fun setShowCamera(show: Boolean) {
        _showCamera.value = show
    }

    fun setCapturedBitmap(bitmap: Bitmap?) {
        _capturedBitmap.value = bitmap
    }

    fun onGalleryImageSelected(context: Context, bitmap: Bitmap) {
        ensureRepository(context)
        if (_processing.value) return
        _result.value = null
        viewModelScope.launch {
            acquireProcessingLock(context)
            _processing.value = true
            _status.value = "جاري المعالجة..."
            try {
                val result = repository.analyzeImage(bitmap)
                if (result != null) {
                    _result.value = result
                    // لا نحتفظ برسالة "جاري المعالجة" أو بصورة الكاميرا بعد
                    // الانتقال للمراجعة؛ عند اختيار "فاتورة جديدة" تبدأ الشاشة
                    // بحالة نظيفة تماماً.
                    _status.value = ""
                } else {
                    _status.value = "❌ السيرفر لم يرجع بيانات"
                }
            } catch (e: Exception) {
                _status.value = "❌ خطأ: ${e.message}"
                Log.e("InvoiceOcrViewModel", "Gallery OCR Error", e)
            } finally {
                _processing.value = false
                releaseProcessingLock()
            }
        }
    }

    fun onCameraCapture(context: Context, bitmap: Bitmap) {
        ensureRepository(context)
        if (_processing.value) return
        _result.value = null
        viewModelScope.launch {
            acquireProcessingLock(context)
            _processing.value = true
            _capturedBitmap.value = bitmap
            _status.value = "جاري تحليل البيانات بـ AI..."
            try {
                val result = repository.analyzeImage(bitmap)
                if (result != null) {
                    _result.value = result
                    _status.value = ""
                } else {
                    _status.value = "❌ السيرفر لم يرجع بيانات"
                    _capturedBitmap.value = null
                }
            } catch (e: Exception) {
                _status.value = "❌ خطأ: ${e.message}"
                Log.e("InvoiceOcrViewModel", "Camera OCR Error", e)
                _capturedBitmap.value = null
            } finally {
                _processing.value = false
                releaseProcessingLock()
            }
        }
    }

    fun preparePdf(context: Context, bytes: ByteArray) {
        ensureRepository(context)
        if (_processing.value) return
        _result.value = null
        viewModelScope.launch {
            acquireProcessingLock(context)
            _processing.value = true
            _status.value = "جاري اكتشاف الفواتير داخل ملف PDF..."
            try {
                val candidates = repository.inspectPdf(bytes)
                if (candidates.isNotEmpty()) {
                    pendingPdfBytes = bytes
                    _pdfCandidates.value = candidates
                    _status.value = "اختر الفاتورة المطلوب تحليلها"
                } else {
                    _status.value = "❌ لم يتم العثور على فواتير داخل ملف PDF"
                }
            } catch (e: Exception) {
                _status.value = "❌ خطأ في قراءة PDF: ${e.message}"
                Log.e("InvoiceOcrViewModel", "PDF OCR Error", e)
            } finally {
                _processing.value = false
                releaseProcessingLock()
            }
        }
    }

    fun processPdfCandidate(
        context: Context,
        candidate: PdfInvoiceCandidate
    ) {
        ensureRepository(context)
        if (_processing.value) return
        val bytes = pendingPdfBytes
        if (bytes == null) {
            _status.value = "❌ انتهت صلاحية ملف PDF. شاركه أو اختره مرة أخرى."
            _pdfCandidates.value = emptyList()
            return
        }

        val candidatesBeforeProcessing = _pdfCandidates.value
        _pdfCandidates.value = emptyList()
        viewModelScope.launch {
            acquireProcessingLock(context)
            _processing.value = true
            _status.value = "جاري تحليل الفاتورة ${candidate.invoiceNumber}..."
            try {
                val result = repository.analyzePdf(bytes, candidate)
                if (result != null) {
                    val remaining = candidatesBeforeProcessing.filterNot { it == candidate }
                    _pdfCandidates.value = remaining
                    if (remaining.isEmpty()) pendingPdfBytes = null
                    _result.value = result
                    _processing.value = false
                    _status.value = ""
                } else {
                    _pdfCandidates.value = candidatesBeforeProcessing
                    _status.value = "❌ السيرفر لم يرجع بيانات"
                }
            } catch (e: Exception) {
                _pdfCandidates.value = candidatesBeforeProcessing
                _status.value = "❌ خطأ في تحليل PDF: ${e.message}"
                Log.e("InvoiceOcrViewModel", "Selected PDF OCR Error", e)
            } finally {
                _processing.value = false
                releaseProcessingLock()
            }
        }
    }

    fun cancelPdfSelection() {
        _pdfCandidates.value = emptyList()
        pendingPdfBytes = null
        _status.value = ""
    }

    fun consumeResult() {
        _result.value = null
    }

    fun reset() {
        _processing.value = false
        _status.value = ""
        _showCamera.value = false
        _capturedBitmap.value = null
        _pdfCandidates.value = emptyList()
        pendingPdfBytes = null
        _result.value = null
    }

    private fun acquireProcessingLock(context: Context) {
        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OrderAutomating:InvoiceOCR"
        ).apply {
            // Keeps the CPU/network alive when the screen turns off. The
            // timeout is a safety net in case a request hangs unexpectedly.
            acquire(4 * 60 * 1000L)
        }
    }

    private fun releaseProcessingLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    override fun onCleared() {
        releaseProcessingLock()
        super.onCleared()
    }

    // تم نقل bitmapToBase64 للمستودع (Repository)
}
