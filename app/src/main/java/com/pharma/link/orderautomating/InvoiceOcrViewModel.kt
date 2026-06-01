package com.pharma.link.orderautomating

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

    fun onGalleryImageSelected(context: Context, bitmap: Bitmap, onResultReady: (OcrResponse) -> Unit) {
        ensureRepository(context)
        viewModelScope.launch {
            _processing.value = true
            _status.value = "جاري المعالجة..."
            try {
                val result = repository.analyzeImage(bitmap)
                if (result != null) {
                    onResultReady(result)
                } else {
                    _status.value = "❌ السيرفر لم يرجع بيانات"
                }
            } catch (e: Exception) {
                _status.value = "❌ خطأ: ${e.message}"
                Log.e("InvoiceOcrViewModel", "Gallery OCR Error", e)
            } finally {
                _processing.value = false
            }
        }
    }

    fun onCameraCapture(context: Context, bitmap: Bitmap, onResultReady: (OcrResponse) -> Unit) {
        ensureRepository(context)
        viewModelScope.launch {
            _processing.value = true
            _capturedBitmap.value = bitmap
            _status.value = "جاري تحليل البيانات بـ AI..."
            try {
                val result = repository.analyzeImage(bitmap)
                if (result != null) {
                    onResultReady(result)
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
            }
        }
    }

    fun onPdfSelected(context: Context, bytes: ByteArray, onResultReady: (OcrResponse) -> Unit) {
        ensureRepository(context)
        viewModelScope.launch {
            _processing.value = true
            _status.value = "جاري قراءة ملف PDF..."
            try {
                val result = repository.analyzePdf(bytes)
                if (result != null) {
                    onResultReady(result)
                } else {
                    _status.value = "❌ السيرفر لم يرجع بيانات"
                }
            } catch (e: Exception) {
                _status.value = "❌ خطأ: ${e.message}"
                Log.e("InvoiceOcrViewModel", "PDF OCR Error", e)
            } finally {
                _processing.value = false
            }
        }
    }

    fun reset() {
        _processing.value = false
        _status.value = ""
        _showCamera.value = false
        _capturedBitmap.value = null
    }

    // تم نقل bitmapToBase64 للمستودع (Repository)
}
