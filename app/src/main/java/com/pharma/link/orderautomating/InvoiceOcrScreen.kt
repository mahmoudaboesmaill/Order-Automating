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
import android.util.Log
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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL


const val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

data class OcrItem(
    val invoiceName: String,
    var quantity: Double,
    var bonus: Double = 0.0,
    var taxes: Double=0.0,
    var price: Double,       // هذا هو سعر الشراء (الصافي)
    var salePrice: Double = 0.0, // سعر البيع (الجمهور)
    var discount: Double = 0.0,
    var itmCode: String = "",
    var matched: Boolean = false
)

data class OcrResponse(
    val supplierName: String,
    val invoiceNumber: String,
    val items: List<OcrItem>
)

@Composable
fun InvoiceOcrScreen(
    onResultReady: (OcrResponse) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // مراقبة تقدم تحميل قاعدة البيانات
    val dbProgress by ItemsDatabase.importProgress.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) } // ← جديد لحفظ اللقطة
    val scope = rememberCoroutineScope()

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) showCamera = true
        else status = "⚠️ محتاج صلاحية الكاميرا"
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            processing = true
            status = "جاري المعالجة..."
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = uriToBitmap(context, it)
                    if (bitmap == null) {
                        withContext(Dispatchers.Main) {
                            processing = false
                            status = "❌ فشل في تحميل الصورة"
                        }
                        return@launch
                    }
                    val result = sendToGemini(bitmap)
                    withContext(Dispatchers.Main) {
                        processing = false
                        if (result != null) onResultReady(result)
                        else status = "❌ فشل في القراءة — حاول تاني"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        processing = false
                        status = "❌ خطأ: ${e.message}"
                    }
                }
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            processing = true
            status = "جاري قراءة ملف PDF..."
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                    if (bytes == null) {
                        withContext(Dispatchers.Main) { processing = false; status = "❌ فشل قراءة الملف" }
                        return@launch
                    }
                    val result = sendToGemini(null, bytes, "application/pdf")
                    withContext(Dispatchers.Main) {
                        processing = false
                        if (result != null) onResultReady(result)
                        else status = "❌ فشل تحليل الـ PDF"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { processing = false; status = "❌ خطأ: ${e.message}" }
                }
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {

        if (showCamera && hasPermission) {
            // خلفية سوداء فقط للكاميرا
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black))

            if (capturedBitmap != null) {
                // إظهار اللقطة الثابتة أثناء المعالجة
                androidx.compose.foundation.Image(
                    bitmap = capturedBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
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
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (processing) status else "وجّه الكاميرا على الفاتورة كلها",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )

                if (processing) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                } else {
                    Button(
                        onClick = {
                            val capture = imageCapture ?: return@Button
                            processing = true
                            status = "جاري التقاط الصورة..."
                            capture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = imageProxyToBitmap(image)
                                        image.close()
                                        
                                        // تجميد الشاشة بالصورة الملتقطة
                                        capturedBitmap = bitmap
                                        status = "جاري تحليل البيانات بـ AI..."

                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val result = sendToGemini(bitmap)
                                                withContext(Dispatchers.Main) {
                                                    processing = false
                                                    if (result != null) onResultReady(result)
                                                    else {
                                                        status = "❌ فشل في القراءة — حاول تاني"
                                                        capturedBitmap = null // إعادة فتح الكاميرا
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    processing = false
                                                    status = "❌ خطأ: ${e.message}"
                                                    capturedBitmap = null
                                                }
                                            }
                                        }
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        processing = false
                                        status = "❌ خطأ في الكاميرا"
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = MaterialTheme.shapes.large
                    ) { 
                        Icon(Icons.Default.Camera, null)
                        Spacer(Modifier.width(8.dp))
                        Text("التقط الفاتورة الآن", fontSize = 18.sp) 
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { 
                        if (processing) return@OutlinedButton
                        if (capturedBitmap != null) capturedBitmap = null
                        else showCamera = false 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("رجوع") }
            }

        } else {
            // عرض شريط تقدم تحميل قاعدة البيانات في الأعلى
            if (dbProgress < 1f) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = dbProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "جاري تجهيز قاعدة البيانات (${(dbProgress * 100).toInt()}%)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("بدء معالجة الفاتورة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("اختر الطريقة المناسبة لك لسحب البيانات", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                
                Spacer(Modifier.height(40.dp))

                ModernActionCard(
                    title = "تصوير الفاتورة",
                    subtitle = "استخدام الكاميرا مباشرة",
                    icon = Icons.Default.PhotoCamera,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    if (hasPermission) showCamera = true
                    else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }

                Spacer(Modifier.height(16.dp))

                ModernActionCard(
                    title = "اختيار صورة",
                    subtitle = "من معرض الصور في جهازك",
                    icon = Icons.Default.Collections,
                    color = MaterialTheme.colorScheme.secondary
                ) { galleryLauncher.launch("image/*") }

                Spacer(Modifier.height(16.dp))

                ModernActionCard(
                    title = "ملف PDF",
                    subtitle = "قراءة الفاتورة الرقمية بدقة عالية",
                    icon = Icons.Default.PictureAsPdf,
                    color = MaterialTheme.colorScheme.tertiary
                ) { pdfLauncher.launch("application/pdf") }

                Spacer(Modifier.height(32.dp))

                if (processing) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (status.startsWith("❌") || status.startsWith("⚠️"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ModernActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.2f)) {
                Icon(icon, null, modifier = Modifier
                    .padding(8.dp)
                    .size(24.dp), tint = color)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = color)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = color.copy(alpha = 0.5f))
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// ← التعديل: ALLOCATOR_SOFTWARE عشان يحل مشكلة hardware bitmap
private fun uriToBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri)
            ) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
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
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(), true
        )
    else bitmap
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

fun sendToGemini(bitmap: Bitmap?, pdfBytes: ByteArray? = null, mimeType: String = "image/jpeg"): OcrResponse? {
    val base64Data = if (pdfBytes != null) {
        Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
    } else if (bitmap != null) {
        bitmapToBase64(bitmap)
    } else return null

    val prompt = """
        أنت خبير فواتير أدوية محترف. استخرج البيانات وحولها لـ JSON بهذا الشكل:
        {
          "supplier_name": "اسم المورد",
          "invoice_number": "رقم الفاتورة",
          "items": [{"name": "اسم الصنف","tax":0, "qty": 0, "bns": 0, "unit_p": 0, "extra": 0, "line_total": 0, "sale_p": 0}]
        }

        دليل استخراج البيانات حسب المورد:
        1. يونايتد جروب فارما كود المورد 198  / مالتي ستورز فارما (تبارك) كود المورد 218:
           - line_total: استخرجه من عمود "إجمالي التكلفة" أو "الإجمالي ".
           - qty: من عمود "العدد" أو "الكمية".
           - sale_p: من عمود "سعر الجمهور"أو"السعر" أو "المستهلك".

        2. ابن سينا (Ibnsina):
           - qty: هو الرقم "العلوي" في خانة الكمية البونص.
           - bns (البونص): هو الرقم "السفلي" في خانة الكمية البونص.
           - unit_p: من عمود "سعر الصيدلي".
           - tax:من عامود "ضريبة ق.م" 
           - extra: من عمود "هامش صيدلي موزع الرقم العلوي".
           - sale_p: من عمود "سعر الجمهور" أو "P.P".

        3. فارما أوفر سيز (PharmaOverseas):
           - qty: الرقم قبل علامة + (مثلاً 10 من 10+1).
           - bns: الرقم بعد علامة + (مثلاً 1 من 10+1).
           - tax:من عامود " ق.م مضافة ج.م " 
           - unit_p: من عمود "سعر صيدلي ج.م".
           - extra: من عمود "هامش ثابت للصيدلي".
           - sale_p: من عمود "سعر الجمهور".
           
        :4.(dream) دريم لمستحضرات التجميل
           - line_total: استخرجه من عمود "إجمالي التكلفة" أو "الإجمالي ".
           - qty: من عمود "العدد" أو "الكمية".
           - sale_p: من عمود "سعر الجمهور"أو"السعر" أو "المستهلك".
           - unit_p:من عامود "سعر البيع"

        قواعد عامة:
        - ابحث عن "اسم المورد" و "رقم الفاتورة" في أعلى الورقة.
        - في أي مورد آخر: sale_p هو سعر الجمهور، و line_total هو صافي السعر في آخر السطر.
    """.trimIndent()

    val requestBody = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", base64Data)
                    })
                })
                put(JSONObject().apply { put("text", prompt) })
            })
        }))
        put("generationConfig", JSONObject().apply {
            put("temperature", 0)
            put("maxOutputTokens", 8192)
        })
    }

    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
        connectTimeout = 30000
        readTimeout = 30000
        outputStream.write(requestBody.toString().toByteArray())
    }

    val responseCode = conn.responseCode
    val response = if (responseCode == 200)
        conn.inputStream.bufferedReader().readText()
    else {
        val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
        throw Exception("HTTP $responseCode: $errBody")
    }

    var text = JSONObject(response)
        .getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")
        .trim()

    text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    // أضف هذا السطر هنا
    Log.d("GEMINI_DEBUG", "البيانات الخام من جيميناي: $text")

    val root = JSONObject(text)
    val supName = root.optString("supplier_name", "").lowercase()
    val itemsArray = root.getJSONArray("items")
    val items = (0 until itemsArray.length()).map { i ->
        itemsArray.getJSONObject(i).let { obj ->
            val qty = obj.optDouble("qty", 1.0).let { if (it <= 0) 1.0 else it }
            val bonus = obj.optDouble("bns", 0.0)
            val lineTotal = obj.optDouble("line_total", 0.0)
            val unitP = obj.optDouble("unit_p", 0.0)
            val extra = obj.optDouble("extra", 0.0)
            val rawTax = obj.optDouble("tax", 0.0)
            
            var pPrice = 0.0
            var finalTax = rawTax
            var finalSalePrice = obj.optDouble("sale_p", 0.0)

            // المنطق الذكي حسب المورد:
            if (supName.contains("overseas") || supName.contains("sina")|| supName.contains("سينا")|| supName.contains("أوفر سيز")) {
                pPrice = unitP + extra
                // لو ابن سينا، نقسم إجمالي الضريبة على الكمية
                if (supName.contains("سينا") && qty > 0) {
                    finalTax = rawTax / qty
                }
            }
            else if (supName.contains("دريم") || supName.contains("dream")) {
                // قاعدة دريم الجديدة
                finalSalePrice = -1.0  // علامة التخطي للروبوت
                pPrice = unitP         // سعر الشراء
            } else if (lineTotal > 0) {
                pPrice = lineTotal / qty

            } else {
                pPrice = unitP
            }

            // تتبع الحساب (المحطة الأولى)
            Log.d("CALC_DEBUG", "الصنف: ${obj.optString("name")} | الحسبة: (unitP:$unitP + extra:$extra) OR (total:$lineTotal / qty:$qty) | الناتج: $pPrice")

            // تقريب الأرقام لـ 3 أرقام عشرية
            val formattedPrice = (Math.round(pPrice * 1000).toDouble() / 1000.0)
            val formattedTax = (Math.round(finalTax * 1000).toDouble() / 1000.0)

            OcrItem(
                invoiceName = obj.optString("name", "غير معروف"),
                quantity    = qty,
                bonus       = bonus,
                taxes       = formattedTax,
                price       = formattedPrice,
                salePrice   = finalSalePrice,
                discount    = 0.0
            )
        }
    }
    return OcrResponse(
        supplierName = root.optString("supplier_name", "غير معروف"),
        invoiceNumber = root.optString("invoice_number", ""),
        items = items
    )
}
