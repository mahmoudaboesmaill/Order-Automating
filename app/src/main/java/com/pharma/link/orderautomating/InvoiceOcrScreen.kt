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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*


@Composable
fun InvoiceOcrScreen(
    onResultReady: (OcrResponse) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: InvoiceOcrViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // مراقبة تقدم تحميل قاعدة البيانات
    val dbProgress by ItemsDatabase.importProgress.collectAsState()

    val processing by viewModel.processing.collectAsState()
    val status by viewModel.status.collectAsState()
    val showCamera by viewModel.showCamera.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val scope = rememberCoroutineScope()

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.setShowCamera(true)
        else viewModel.reset() // Or some error state
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = uriToBitmap(context, it)
            if (bitmap != null) {
                viewModel.onGalleryImageSelected(context, bitmap, onResultReady)
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                if (bytes != null) {
                    viewModel.onPdfSelected(context, bytes, onResultReady)
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
                            capture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = imageProxyToBitmap(image)
                                        image.close()
                                        viewModel.onCameraCapture(context, bitmap, onResultReady)
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        // Handle error via viewModel if needed
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
                        if (capturedBitmap != null) viewModel.setCapturedBitmap(null)
                        else viewModel.setShowCamera(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("رجوع") }
            }

        } else {
            // أيقونة الإعدادات في الأعلى مع مراعاة الـ Status Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding() // يمنع تداخل الأيقونة مع الـ Status Bar
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "الإعدادات",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

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
                    if (hasPermission) viewModel.setShowCamera(true)
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



