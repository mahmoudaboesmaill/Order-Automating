package com.pharma.link.orderautomating

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pharma.link.orderautomating.ui.components.*
import com.pharma.link.orderautomating.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private data class SupplierOption(
    val code: String,
    val displayName: String,
    val aliases: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceOcrScreen(
    onResultReady: (OcrResponse) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    incomingSharedUri: Uri? = null,
    onIncomingSharedUriConsumed: () -> Unit = {},
    viewModel: InvoiceOcrViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var supplierOptions by remember { mutableStateOf<List<SupplierOption>>(emptyList()) }
    var selectedSupplierCode by rememberSaveable {
        mutableStateOf(ServerManager.getSelectedSupplierCode(context).orEmpty())
    }
    var supplierMenuExpanded by remember { mutableStateOf(false) }
    var pendingSharedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingSharedSupplier by rememberSaveable { mutableStateOf(false) }
    var sharedFileError by rememberSaveable { mutableStateOf("") }
    var readingSharedFile by rememberSaveable { mutableStateOf(false) }
    var pendingPdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPdfPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPdfName by rememberSaveable { mutableStateOf("") }
    var pendingPdfPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showPdfPreview by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(incomingSharedUri) {
        incomingSharedUri?.let { uri ->
            pendingSharedUri = uri.toString()
            awaitingSharedSupplier = true
            sharedFileError = ""
            selectedSupplierCode = ""
            onIncomingSharedUriConsumed()
        }
    }

    LaunchedEffect(Unit) {
        supplierOptions = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(context).supplierDictionaryDao().getAll()
                .groupBy { it.supplierCode }
                .toSortedMap()
                .map { (code, aliases) ->
                    val names = aliases.flatMap { listOf(it.arabicName, it.englishName) }
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val primary = aliases
                        .firstOrNull { it.arabicName.any { character -> character in '\u0600'..'\u06FF' } }
                        ?.arabicName
                        ?.takeIf { it.isNotBlank() }
                        ?: names.firstOrNull()
                        ?: "مورد $code"
                    SupplierOption(
                        code = code,
                        displayName = primary,
                        aliases = names.joinToString(" / ")
                    )
                }
        }
    }

    // A PDF can be large and cannot be placed in the saved instance Bundle.
    // Keep a cache copy so rotation/background recreation can restore the
    // preview and continue processing instead of silently losing the file.
    LaunchedEffect(pendingPdfPath, showPdfPreview) {
        if (showPdfPreview && pendingPdfPreview == null && pendingPdfPath != null) {
            val restoredBytes = withContext(Dispatchers.IO) {
                runCatching { File(pendingPdfPath!!).readBytes() }.getOrNull()
            }
            if (restoredBytes != null) {
                pendingPdfBytes = restoredBytes
                pendingPdfPreview = withContext(Dispatchers.IO) { renderFirstPdfPage(restoredBytes) }
            }
        }
    }

    val selectedSupplier = supplierOptions.firstOrNull { it.code == selectedSupplierCode }
    val processingSupplierLabel = selectedSupplier?.let {
        "${it.displayName} (${it.code})"
    } ?: if (selectedSupplierCode.isBlank()) {
        "تلقائي (التعرف من الفاتورة)"
    } else {
        "كود $selectedSupplierCode"
    }

    val dbProgress by ItemsDatabase.importProgress.collectAsState()
    val processing by viewModel.processing.collectAsState()
    val status by viewModel.status.collectAsState()
    val showCamera by viewModel.showCamera.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val pdfCandidates by viewModel.pdfCandidates.collectAsState()
    val ocrResult by viewModel.result.collectAsState()

    // Navigation is driven by ViewModel state, so a rotation or a temporary
    // backgrounding cannot leave the result attached to a destroyed callback.
    LaunchedEffect(ocrResult) {
        val result = ocrResult ?: return@LaunchedEffect
        viewModel.consumeResult()
        onResultReady(result)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val scope = rememberCoroutineScope()

    fun queuePdfPreview(uri: Uri, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, "pending_invoice_${System.currentTimeMillis()}.pdf")
            runCatching { cacheFile.writeBytes(bytes) }
            val preview = renderFirstPdfPage(bytes)
            val displayName = queryDisplayName(context, uri)
            withContext(Dispatchers.Main) {
                pendingPdfBytes = bytes
                pendingPdfPath = cacheFile.takeIf { it.exists() }?.absolutePath
                pendingPdfName = displayName
                pendingPdfPreview = preview
                showPdfPreview = true
            }
        }
    }

    fun continueWithPendingPdf() {
        val bytes = pendingPdfBytes ?: pendingPdfPath?.let { runCatching { File(it).readBytes() }.getOrNull() } ?: return
        showPdfPreview = false
        pendingPdfBytes = null
        pendingPdfPreview = null
        pendingPdfPath?.let { runCatching { File(it).delete() } }
        pendingPdfPath = null
        viewModel.preparePdf(context, bytes)
    }

    fun clearPendingPdf() {
        showPdfPreview = false
        pendingPdfBytes = null
        pendingPdfPreview = null
        pendingPdfPath?.let { runCatching { File(it).delete() } }
        pendingPdfPath = null
        pendingPdfName = ""
    }

    fun startSharedFile(uri: Uri) {
        sharedFileError = ""
        readingSharedFile = true
        val mimeType = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val isPdf = mimeType == "application/pdf" ||
                uri.toString().lowercase().substringBefore("?").endsWith(".pdf")

        scope.launch(Dispatchers.IO) {
            if (isPdf) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    readingSharedFile = false
                    if (bytes == null || bytes.isEmpty()) {
                        sharedFileError = "تعذر قراءة ملف الـ PDF المشترك. جرّب مشاركته من جديد."
                    } else {
                        queuePdfPreview(uri, bytes)
                    }
                }
            } else {
                val bitmap = runCatching { uriToBitmap(context, uri) }.getOrNull()
                withContext(Dispatchers.Main) {
                    readingSharedFile = false
                    if (bitmap == null) {
                        sharedFileError = "تعذر قراءة الصورة المشتركة. جرّب اختيار صورة أوضح."
                    } else {
                        viewModel.onGalleryImageSelected(context, bitmap)
                    }
                }
            }
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.setShowCamera(true)
        else viewModel.reset()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = uriToBitmap(context, it)
            if (bitmap != null) {
                viewModel.onGalleryImageSelected(context, bitmap)
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    queuePdfPreview(it, bytes)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (showCamera && hasPermission) {
            // Fullscreen Camera View
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                if (capturedBitmap != null) {
                    Image(
                        bitmap = capturedBitmap!!.asImageBitmap(),
                        contentDescription = "لقطة الفاتورة الملتقطة",
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
                                val preview = androidx.camera.core.Preview.Builder().build().also {
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
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Camera Controls Overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = PharmaDimens.space24, vertical = PharmaDimens.space16),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = PillShape,
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = PharmaDimens.space16)
                    ) {
                        Text(
                            text = if (processing) "$status\nالمورد المعتمد: $processingSupplierLabel" else "المورد المعتمد: $processingSupplierLabel",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    if (processing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
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
                                            viewModel.onCameraCapture(context, bitmap)
                                        }

                                        override fun onError(e: ImageCaptureException) {
                                            e.printStackTrace()
                                        }
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = PharmaShapes.large,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(PharmaDimens.space8))
                            Text("التقط صورة الفاتورة الآن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(PharmaDimens.space12))
                    OutlinedButton(
                        onClick = {
                            if (processing) return@OutlinedButton
                            if (capturedBitmap != null) viewModel.setCapturedBitmap(null)
                            else viewModel.setShowCamera(false)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = PharmaShapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                    ) {
                        Text("إلغاء والرجوع")
                    }
                }
            }

        } else {
            // Main OCR Home Dashboard
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Branded Header App Bar
                HomeAppBar(
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings
                )

                // Database Sync Progress Bar (if indexing)
                if (dbProgress < 1f) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space8),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { dbProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(PillShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "جاري تجهيز قاعدة بيانات الأصناف (${(dbProgress * 100).toInt()}%)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = PharmaDimens.space20, vertical = PharmaDimens.space12),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Level 1: Authoritative Active Supplier Card
                    SupplierSelectorSection(
                        selectedLabel = processingSupplierLabel,
                        options = supplierOptions,
                        expanded = supplierMenuExpanded,
                        onExpandedChange = { supplierMenuExpanded = !supplierMenuExpanded },
                        onSelect = { code ->
                            selectedSupplierCode = code
                            ServerManager.saveSelectedSupplierCode(context, code)
                            supplierMenuExpanded = false

                            val sharedUri = pendingSharedUri?.let(Uri::parse)
                            if (awaitingSharedSupplier && sharedUri != null) {
                                awaitingSharedSupplier = false
                                pendingSharedUri = null
                                startSharedFile(sharedUri)
                            }
                        }
                    )

                    Spacer(Modifier.height(PharmaDimens.space16))

                    // Level 2: Server Configuration Alert (if no server configured)
                    val hasServer = remember { ServerManager.getSelectedServer(context) != null }
                    if (!hasServer) {
                        Surface(
                            shape = PharmaShapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSettings() }
                                .padding(bottom = PharmaDimens.space12)
                        ) {
                            Row(
                                modifier = Modifier.padding(PharmaDimens.space12),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.WifiOff, contentDescription = "السيرفر غير متصل", tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(PharmaDimens.space10))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("السيرفر غير متصل", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("اضغط لضبط عنوان IP سيرفر الصيدلية في الإعدادات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // Level 2: External Shared File Banner (if user shared via WhatsApp/Gallery/Files)
                    if (awaitingSharedSupplier || readingSharedFile || sharedFileError.isNotBlank()) {
                        Surface(
                            shape = PharmaShapes.large,
                            color = if (sharedFileError.isBlank()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, if (sharedFileError.isBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = PharmaDimens.space16)
                        ) {
                            Row(
                                modifier = Modifier.padding(PharmaDimens.space16),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (sharedFileError.isBlank()) Icons.Default.Share else Icons.Default.ErrorOutline,
                                    contentDescription = "ملف مشترك",
                                    tint = if (sharedFileError.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(PharmaDimens.space12))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (readingSharedFile) "جاري تجهيز الملف المشترك للتحليل..."
                                        else if (awaitingSharedSupplier) "تم استلام ملف! اختر المورد وسيبدأ التحليل تلقائياً"
                                        else sharedFileError,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (sharedFileError.isBlank()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // Section Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "وسائل إدخال الفاتورة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(Modifier.height(PharmaDimens.space12))

                    // Level 3: Action Cards — keep all input methods visually equal.
                    PharmaActionCard(
                        title = "تصوير الفاتورة بالكاميرا",
                        subtitle = "التقاط فوري ومباشر للفاتورة الورقية",
                        icon = Icons.Default.PhotoCamera,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = {
                            if (!processing) {
                                if (hasPermission) viewModel.setShowCamera(true)
                                else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    )

                    Spacer(Modifier.height(PharmaDimens.space12))

                    // Action 2: PDF Digital Invoice (Secondary)
                    PharmaActionCard(
                        title = "ملف PDF رقمي",
                        subtitle = "استخراج إلكتروني فائق الدقة ودعم فواتير متعددة",
                        icon = Icons.Default.PictureAsPdf,
                        color = MaterialTheme.colorScheme.secondary,
                        badgeText = "دقة 100%",
                        isHero = false,
                        onClick = {
                            if (!processing) pdfLauncher.launch("application/pdf")
                        }
                    )

                    Spacer(Modifier.height(PharmaDimens.space12))

                    // Action 3: Gallery Image (Tertiary)
                    PharmaActionCard(
                        title = "اختيار صورة من المعرض",
                        subtitle = "تحليل صور الفواتير المحفوظة في الهاتف",
                        icon = Icons.Default.Collections,
                        color = MaterialTheme.colorScheme.tertiary,
                        isHero = false,
                        onClick = {
                            if (!processing) galleryLauncher.launch("image/*")
                        }
                    )

                    Spacer(Modifier.height(PharmaDimens.space20))

                    // Live Status Card during processing
                    if (processing) {
                        Surface(
                            shape = PharmaShapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(PharmaDimens.space16),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(PharmaDimens.space16))
                                Column {
                                    Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("المورد المعتمد: $processingSupplierLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                }
                            }
                        }
                    } else if (status.isNotEmpty()) {
                        Surface(
                            shape = PharmaShapes.medium,
                            color = if (status.startsWith("❌") || status.startsWith("⚠️")) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = if (status.startsWith("❌") || status.startsWith("⚠️")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(PharmaDimens.space32))
                }
            }
        }

        // Multiple PDF Invoices Picker Overlay
        if (pdfCandidates.isNotEmpty()) {
            PdfInvoicePickerOverlay(
                candidates = pdfCandidates,
                isProcessing = processing,
                onCancel = { viewModel.cancelPdfSelection() },
                onSelect = { candidate ->
                    if (!processing) {
                        viewModel.processPdfCandidate(context, candidate)
                    }
                }
            )
        }

        if (showPdfPreview) {
            PdfPreviewDialog(
                fileName = pendingPdfName,
                preview = pendingPdfPreview,
                supplierLabel = processingSupplierLabel,
                onCancel = { clearPendingPdf() },
                onContinue = { continueWithPendingPdf() }
            )
        }

        BackHandler(enabled = showCamera) {
            if (!processing) {
                if (capturedBitmap != null) viewModel.setCapturedBitmap(null) else viewModel.setShowCamera(false)
            }
        }
        BackHandler(enabled = pdfCandidates.isNotEmpty() && !processing) {
            viewModel.cancelPdfSelection()
        }
        BackHandler(enabled = showPdfPreview && !processing) {
            clearPendingPdf()
        }
        // Do not destroy the screen while a request is in flight. The user
        // can still press Home; the ViewModel and wake lock keep the request
        // alive until it completes.
        BackHandler(enabled = processing) { }
    }
}

@Composable
private fun HomeAppBar(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_splash_logo),
                contentDescription = "شعار التطبيق",
                modifier = Modifier.size(38.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(PharmaDimens.space12))
            Column {
                Text(
                    "OrderAutomating",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "أتمتة ومعالجة فواتير الصيدلية",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenHistory,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "سجل الفواتير السابقة",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "الإعدادات والسيرفر",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupplierSelectorSection(
    selectedLabel: String,
    options: List<SupplierOption>,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    onSelect: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PharmaShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = PharmaDimens.elevationLow
    ) {
        Column(modifier = Modifier.padding(PharmaDimens.space16)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Business,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(PharmaDimens.space8))
                    Text(
                        "المورد المعتمد للتحليل",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                PharmaStatusBadge(
                    text = "تطبيق قواعد التسعير",
                    type = PharmaBadgeType.INFO
                )
            }

            Spacer(Modifier.height(PharmaDimens.space8))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { onExpandedChange() }
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    shape = PharmaShapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = onExpandedChange,
                    modifier = Modifier.heightIn(max = 280.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("تلقائي (التعرف الذكي من الفاتورة)", fontWeight = FontWeight.Bold)
                                Text("استخراج كود المورد آلياً من رأس الفاتورة", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = { onSelect("") }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("${option.displayName} (كود ${option.code})", fontWeight = FontWeight.SemiBold)
                                    if (option.aliases.isNotBlank()) {
                                        Text(option.aliases, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            onClick = { onSelect(option.code) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(PharmaDimens.space4))
            Text(
                "تحديد المورد مسبقاً يضمن تطبيق معادلات الأسعار الصحيحة (ابن سينا، أوفرسيز، دريم، يونايتد، تبارك).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PdfPreviewDialog(
    fileName: String,
    preview: Bitmap?,
    supplierLabel: String,
    onCancel: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("معاينة ملف PDF", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                Text(
                    fileName.ifBlank { "ملف PDF بدون اسم" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "المورد المختار: $supplierLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (preview != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        shape = PharmaShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "معاينة الصفحة الأولى من ملف PDF",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }
                } else {
                    Text(
                        "تعذر إنشاء صورة المعاينة، لكن يمكن متابعة قراءة الملف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "راجع اسم المورد والصفحة الأولى قبل بدء المعالجة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onContinue) { Text("متابعة المعالجة") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("إلغاء") }
        }
    )
}

@Composable
private fun PdfInvoicePickerOverlay(
    candidates: List<PdfInvoiceCandidate>,
    isProcessing: Boolean,
    onCancel: () -> Unit,
    onSelect: (PdfInvoiceCandidate) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(PharmaDimens.space16),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = PharmaShapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = PharmaDimens.elevationHigh)
        ) {
            Column(Modifier.padding(PharmaDimens.space20)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(PharmaDimens.space8))
                        Text(
                            "فواتير ملف الـ PDF",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    PharmaStatusBadge(
                        text = "${candidates.size} فواتير",
                        type = PharmaBadgeType.INFO
                    )
                }

                Spacer(Modifier.height(PharmaDimens.space6))
                Text(
                    "تم اكتشاف فواتير متعددة داخل الملف. اضغط على الفاتورة المراد تحليلها ومراجعتها:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(PharmaDimens.space16))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PharmaDimens.space12)
                ) {
                    items(candidates) { candidate ->
                        val pages = if (candidate.pageStart == candidate.pageEnd) {
                            "صفحة ${candidate.pageStart}"
                        } else {
                            "الصفحات ${candidate.pageStart} - ${candidate.pageEnd}"
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = PharmaDimens.minTouchTarget)
                                .clip(PharmaShapes.large)
                                .clickable(enabled = !isProcessing) { onSelect(candidate) },
                            shape = PharmaShapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(Modifier.padding(PharmaDimens.space16)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (candidate.invoiceNumber.isBlank()) "فاتورة غير مرقمة"
                                        else "فاتورة رقم ${candidate.invoiceNumber}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    PharmaStatusBadge(text = pages, type = PharmaBadgeType.NEUTRAL)
                                }
                                Spacer(Modifier.height(PharmaDimens.space6))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (candidate.itemCount > 0) {
                                        Text(
                                            "📦 ${candidate.itemCount} صنف",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (candidate.printedTotal > 0.0) {
                                        Text(
                                            "💵 الإجمالي: ${String.format(Locale.US, "%.2f", candidate.printedTotal)} ج.م",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (candidate.duplicateCopies > 1) {
                                    Spacer(Modifier.height(PharmaDimens.space6))
                                    Text(
                                        "⚠️ يوجد نسختان مكررتان؛ سيتم تحليل نسخة واحدة فقط.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(PharmaDimens.space16))

                PharmaSecondaryButton(
                    text = "إلغاء والرجوع",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex).orEmpty()
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "ملف PDF"
}

private fun renderFirstPdfPage(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val temporaryFile = runCatching {
        File.createTempFile("invoice_preview_", ".pdf")
    }.getOrNull() ?: return null

    return try {
        FileOutputStream(temporaryFile).use { it.write(bytes) }
        ParcelFileDescriptor.open(
            temporaryFile,
            ParcelFileDescriptor.MODE_READ_ONLY
        ).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val maxDimension = 1200f
                    val scale = minOf(
                        1f,
                        maxDimension / page.width.toFloat(),
                        maxDimension / page.height.toFloat()
                    )
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        temporaryFile.delete()
    }
}

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

// ============================================================================
// 📱 Previews for InvoiceOcrScreen
// ============================================================================

@Preview(name = "Start Screen - Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun PreviewInvoiceOcrScreenLight() {
    OrderAutomatingTheme(darkTheme = false) {
        InvoiceOcrScreen(
            onResultReady = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Start Screen - Dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun PreviewInvoiceOcrScreenDark() {
    OrderAutomatingTheme(darkTheme = true) {
        InvoiceOcrScreen(
            onResultReady = {},
            onDismiss = {}
        )
    }
}
