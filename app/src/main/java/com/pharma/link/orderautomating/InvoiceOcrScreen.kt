package com.pharma.link.orderautomating

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors


const val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

data class OcrItem(
    val invoiceName: String,
    var quantity: Double,
    var discount: Double,
    var price: Double,
    var itmCode: String = "",
    var matched: Boolean = false
)

@Composable
fun InvoiceOcrScreen(
    supplierCode: String,
    invoiceNumber: String,
    onItemsReady: (List<OcrItem>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("اختر طريقة إدخال الفاتورة") }
    var processing by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val scope = rememberCoroutineScope()

    // Camera permission
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) showCamera = true
        else status = "⚠️ محتاج صلاحية الكاميرا"
    }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            processing = true
            status = "جاري المعالجة..."
            scope.launch(Dispatchers.IO) {
                val bitmap = uriToBitmap(context, it)
                val items = bitmap?.let { bmp -> sendToGemini(bmp) }
                withContext(Dispatchers.Main) {
                    processing = false
                    if (items != null) onItemsReady(items)
                    else status = "❌ فشل في القراءة — حاول تاني"
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        if (showCamera && hasPermission) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()
                        imageCapture = capture
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, capture
                            )
                        } catch (e: Exception) { e.printStackTrace() }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (processing) status else "وجّه الكاميرا على الفاتورة كلها",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (processing) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                } else {
                    Button(
                        onClick = {
                            val capture = imageCapture ?: return@Button
                            processing = true
                            status = "جاري المعالجة..."
                            capture.takePicture(
                                Executors.newSingleThreadExecutor(),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = imageProxyToBitmap(image)
                                        image.close()
                                        scope.launch(Dispatchers.IO) {
                                            val items = sendToGemini(bitmap)
                                            withContext(Dispatchers.Main) {
                                                processing = false
                                                if (items != null) onItemsReady(items)
                                                else status = "❌ فشل في القراءة — حاول تاني"
                                            }
                                        }
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        scope.launch(Dispatchers.Main) {
                                            processing = false
                                            status = "❌ خطأ في الكاميرا"
                                        }
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("📸 التقط الفاتورة", fontSize = 18.sp) }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showCamera = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("رجوع") }
            }

        } else {
            // اختيار الطريقة
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("إدخال الفاتورة", fontSize = 22.sp)
                Spacer(Modifier.height(32.dp))

                // زر الكاميرا
                Button(
                    onClick = {
                        if (hasPermission) showCamera = true
                        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) { Text("📸 تصوير الفاتورة", fontSize = 18.sp) }

                Spacer(Modifier.height(16.dp))

                // زر اختيار من المعرض
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) { Text("🖼️ اختيار من الجهاز", fontSize = 18.sp) }

                Spacer(Modifier.height(16.dp))

                if (processing) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(status)
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إلغاء") }
            }
        }
    }
}

// ── Helper functions ──────────────────────────────────────

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun uriToBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun bitmapToBase64(bitmap: Bitmap): String {
    val maxW = 1600; val maxH = 2400
    val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height, 1f)
    val scaled = if (scale < 1f)
        Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(), true)
    else bitmap
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

fun sendToGemini(bitmap: Bitmap): List<OcrItem>? {
    return try {
        val base64Image = bitmapToBase64(bitmap)
        val prompt = """
            هذه صورة فاتورة شراء صيدلية.
            استخرج كل الأصناف من الجدول وأرجع JSON فقط بالشكل التالي بدون أي نص إضافي:
            {"items": [{"name": "اسم الصنف", "quantity": 0, "discount": 0, "price": 0}]}
            - name: اسم الصنف كما هو مكتوب في الفاتورة
            - quantity: الكمية (رقم)
            - discount: نسبة الخصم (رقم بدون %)
            - price: سعر الشراء للوحدة (رقم)
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                    put(JSONObject().apply { put("text", prompt) })
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("maxOutputTokens", 2000)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 30000
            outputStream.write(requestBody.toString().toByteArray())
        }

        val response = conn.inputStream.bufferedReader().readText()
        var text = JSONObject(response)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()

        // شيل الـ markdown لو موجود
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val itemsArray = JSONObject(text).getJSONArray("items")
        (0 until itemsArray.length()).map { i ->
            itemsArray.getJSONObject(i).let { obj ->
                OcrItem(
                    invoiceName = obj.getString("name"),
                    quantity    = obj.optDouble("quantity", 0.0),
                    discount    = obj.optDouble("discount", 0.0),
                    price       = obj.optDouble("price", 0.0)
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}