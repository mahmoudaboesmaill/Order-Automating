package com.pharma.link.orderautomating

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pharma.link.orderautomating.ui.components.*
import com.pharma.link.orderautomating.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ReviewScreen(
    initialSupplierCode: String,
    initialInvoiceNumber: String,
    response: OcrResponse?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ReviewViewModel = viewModel()
) {
    val context = LocalContext.current
    
    val supplierCode by viewModel.supplierCode.collectAsState()
    val invoiceNumber by viewModel.invoiceNumber.collectAsState()
    val editableItems by viewModel.editableItems.collectAsState()
    val status by viewModel.status.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val sendSession by viewModel.sendSession.collectAsState()
    val sessionActionLoading by viewModel.sessionActionLoading.collectAsState()
    val itemToRemapIndex by viewModel.itemToRemapIndex.collectAsState()
    val invoiceTotalCheck by viewModel.invoiceTotalCheck.collectAsState()
    val ignorePharmaWarnings by viewModel.ignorePharmaWarnings.collectAsState()
    val hasPurchaseMismatch = editableItems.any { !it.purchasePriceMethodsMatch }
    val sentSuccessfully = status.startsWith("✅")
    var showPostSendCorrectionPicker by remember { mutableStateOf(false) }
    var showResumeResolution by remember { mutableStateOf(false) }
    var showCancelSessionConfirmation by remember { mutableStateOf(false) }
    var showRestartSessionConfirmation by remember { mutableStateOf(false) }

    BackHandler(enabled = !loading) { onBack() }
    BackHandler(enabled = loading) { }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        if (status.startsWith("✅")) {
            snackbarHostState.showSnackbar(
                message = status,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(response, initialSupplierCode, initialInvoiceNumber) {
        viewModel.init(context, initialSupplierCode, initialInvoiceNumber, response)
    }

    if (itemToRemapIndex != -1 && itemToRemapIndex in editableItems.indices) {
        val currentItem = editableItems[itemToRemapIndex]
        MappingDialog(
            invoiceName = currentItem.invoiceName,
            onDismiss = { viewModel.setItemToRemap(-1) },
            onSelect = { newItem ->
                viewModel.remapItem(context, itemToRemapIndex, newItem)
            }
        )
    }

    if (showPostSendCorrectionPicker) {
        PostSendCorrectionPickerDialog(
            items = editableItems,
            onDismiss = { showPostSendCorrectionPicker = false },
            onSelect = { index ->
                showPostSendCorrectionPicker = false
                viewModel.setItemToRemap(index)
            }
        )
    }

    val activeSession = sendSession?.takeIf { it.isIncomplete }
    if (showResumeResolution && activeSession != null) {
        AlertDialog(
            onDismissRequest = { showResumeResolution = false },
            icon = { Icon(Icons.Default.HelpOutline, contentDescription = null) },
            title = { Text("الصنف الذي حدث عنده التوقف") },
            text = {
                Text(
                    "الروبوت توقف أثناء الصنف رقم ${activeSession.currentIndex + 1}. " +
                        "راجع آخر صف داخل E-PLUS أولاً: هل الصنف دخل بالكامل أم تريد إعادة هذا الصنف؟"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResumeResolution = false
                    viewModel.resumeSendSession(context, "completed")
                }) { Text("دخل بالكامل") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showResumeResolution = false }) { Text("إغلاق") }
                    TextButton(onClick = {
                        showResumeResolution = false
                        viewModel.resumeSendSession(context, "retry")
                    }) { Text("إعادة الصنف") }
                }
            }
        )
    }

    if (showCancelSessionConfirmation && activeSession != null) {
        AlertDialog(
            onDismissRequest = { showCancelSessionConfirmation = false },
            icon = { Icon(Icons.Default.StopCircle, contentDescription = null) },
            title = { Text("إلغاء جلسة الإرسال؟") },
            text = { Text("الإلغاء يحرر الجلسة فقط، ولا يحذف أو يحفظ ما كُتب بالفعل داخل E-PLUS.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelSessionConfirmation = false
                    viewModel.cancelSendSession(context)
                }) { Text("إلغاء الجلسة") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelSessionConfirmation = false }) { Text("رجوع") }
            }
        )
    }

    if (showRestartSessionConfirmation && activeSession != null) {
        AlertDialog(
            onDismissRequest = { showRestartSessionConfirmation = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text("إعادة الإدخال من البداية؟") },
            text = {
                Text(
                    "لن تتم إعادة البداية تلقائياً. تأكد أولاً أنك أغلقت أو ألغيت الفاتورة الجزئية داخل E-PLUS؛ " +
                        "بعد التأكيد سيُدخل الروبوت رأس الفاتورة وكل الأصناف من جديد."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestartSessionConfirmation = false
                    viewModel.restartSendSession(context)
                }) { Text("ابدأ من الصفر") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartSessionConfirmation = false }) { Text("رجوع") }
            }
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(PharmaDimens.space16),
                    shape = PharmaShapes.large
                )
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = PharmaDimens.elevationMedium,
                shadowElevation = PharmaDimens.elevationHigh
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space12),
                ) {
                    if (sentSuccessfully) {
                        PharmaSecondaryButton(
                            text = "تصحيح مطابقة",
                            icon = Icons.Default.Edit,
                            onClick = { showPostSendCorrectionPicker = true },
                            enabled = !loading && editableItems.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(PharmaDimens.space8))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space12)) {
                        PharmaSecondaryButton(
                            text = if (sentSuccessfully) "فاتورة جديدة" else "رجوع للبداية",
                            onClick = onBack,
                            modifier = Modifier.weight(1f)
                        )

                        PharmaPrimaryButton(
                            text = if (sentSuccessfully) "إعادة الإرسال" else "إرسال إلى E-PLUS",
                            icon = Icons.Default.Upload,
                            onClick = { viewModel.sendInvoice(context) },
                            loading = loading,
                            enabled = !loading && editableItems.isNotEmpty() &&
                                (!hasPurchaseMismatch || ignorePharmaWarnings),
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(PharmaDimens.space12),
            contentPadding = PaddingValues(
                start = PharmaDimens.space16,
                end = PharmaDimens.space16,
                top = PharmaDimens.space8,
                bottom = PharmaDimens.space16
            )
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = PharmaDimens.elevationLow,
                    shape = PharmaShapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space12)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "مراجعة وتدقيق الفاتورة",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "المورد المعتمد: ${supplierDisplayName(supplierCode)} (كود $supplierCode)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "الإعدادات وتغيير السيرفر",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(PharmaDimens.space12))
                        Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                            PharmaHeaderSmallField(
                                value = supplierCode,
                                label = "كود المورد",
                                modifier = Modifier.weight(1f),
                                onValueChange = { viewModel.updateSupplierCode(it) }
                            )
                            PharmaHeaderSmallField(
                                value = invoiceNumber,
                                label = "رقم الفاتورة",
                                modifier = Modifier.weight(1f),
                                onValueChange = { viewModel.updateInvoiceNumber(it) }
                            )
                        }
                    }
                }
            }

            item {
                InvoiceTotalSummaryCard(
                    check = invoiceTotalCheck,
                    itemsCount = editableItems.size
                )
            }

            if (activeSession != null) {
                item {
                    SendSessionCard(
                        session = activeSession,
                        loading = sessionActionLoading,
                        onResume = {
                            if (activeSession.requiresResolution) {
                                showResumeResolution = true
                            } else {
                                viewModel.resumeSendSession(context)
                            }
                        },
                        onCancel = { showCancelSessionConfirmation = true },
                        onRestart = { showRestartSessionConfirmation = true }
                    )
                }
            }

            if (hasPurchaseMismatch) {
                item {
                    Surface(
                        shape = PharmaShapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(PharmaDimens.space12)) {
                            Text(
                                "راجع ${editableItems.count { !it.purchasePriceMethodsMatch }} صنف",
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "الأصناف التي تحتاج تصحيح مميزة باللون الأحمر.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(PharmaDimens.space8))
                            if (ignorePharmaWarnings) {
                                OutlinedButton(onClick = { viewModel.setIgnorePharmaWarnings(false) }) {
                                    Text("إلغاء التجاهل")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.setIgnorePharmaWarnings(true) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Text("راجعت القيم — تجاهل التحذير واسمح بالإرسال")
                                }
                            }
                        }
                    }
                }
            }

            if (status.isNotEmpty()) {
                item {
                    val isSuccess = status.startsWith("✅")
                    Surface(
                        shape = PharmaShapes.medium,
                        color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            itemsIndexed(
                items = editableItems,
                key = { index, item -> "$index|${item.itmCode}|${item.invoiceName}" }
            ) { index, item ->
                val mergeCandidateIndex = findMergeCandidateIndex(editableItems, index)
                ReviewItemCard(
                    item = item,
                    index = index + 1,
                    supplierCode = supplierCode,
                    onDelete = { viewModel.deleteItem(index) },
                    onRemap = { viewModel.setItemToRemap(index) },
                    onSplit = { splitQuantity -> viewModel.splitItem(index, splitQuantity) },
                    canMerge = mergeCandidateIndex != null,
                    mergeCandidateQuantity = mergeCandidateIndex?.let { editableItems[it].quantity } ?: 0.0,
                    onMerge = { viewModel.mergeItem(index) },
                    onExpiryModeChange = { mode -> viewModel.setExpiryMode(context, index, mode) },
                    onUpdate = { updated -> viewModel.updateItem(index, updated) }
                )
            }
        }
    }
}

@Composable
private fun SendSessionCard(
    session: SendSessionState,
    loading: Boolean,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit
) {
    val progress = if (session.totalItems > 0) {
        session.completedItems.toFloat() / session.totalItems.toFloat()
    } else 0f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PharmaShapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PharmaDimens.space16),
            verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "جلسة إرسال قابلة للاستكمال",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        "فاتورة ${session.invoiceNumber} — مورد ${session.supplierCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Text(
                    "${session.completedItems}/${session.totalItems}",
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                session.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (session.requiresResolution) {
                Text(
                    "آخر صنف حالته غير مؤكدة؛ سيُطلب منك تحديدها قبل الاستكمال.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (session.requiresRestart) {
                Text(
                    "لا يمكن ضمان نفس نافذة الفاتورة الجزئية؛ الاستكمال معطّل للحماية.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (session.phase == "item_needs_manual_fix") {
                Text(
                    "أكمل التصحيح داخل E-PLUS ثم اضغط Ctrl+Shift+R.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)
                ) {
                    if (session.canResume) {
                        Button(onClick = onResume, modifier = Modifier.weight(1.2f)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("استكمال")
                        }
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("إلغاء الجلسة")
                    }
                    if (!session.processAlive && session.status == "interrupted") {
                        OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                            Text("من البداية")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InvoiceTotalSummaryCard(
    check: InvoiceTotalCheck,
    itemsCount: Int,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    // 4 Visual States Distinction (WCAG 2.2 AA Verified)
    val (containerColor, borderColor, badgeText, badgeType) = when {
        !check.hasPrintedTotal -> Quad(
            if (isDark) InfoBlueBgDark else InfoBlueBg,
            if (isDark) InfoBlueDark.copy(alpha = 0.3f) else InfoBlue.copy(alpha = 0.3f),
            "لم يُستخرج الإجمالي المطبوع",
            PharmaBadgeType.INFO
        )
        check.matches -> Quad(
            if (isDark) SuccessGreenBgDark else SuccessGreenBg,
            if (isDark) SuccessGreenDark.copy(alpha = 0.35f) else SuccessGreen.copy(alpha = 0.35f),
            "مطابق تماماً للاستخراج",
            PharmaBadgeType.SUCCESS
        )
        check.withinOnePound -> Quad(
            if (isDark) WarningAmberBgDark else WarningAmberBg,
            if (isDark) WarningAmberDark.copy(alpha = 0.35f) else WarningAmber.copy(alpha = 0.35f),
            "فرق مسموح حتى 1 ج.م",
            PharmaBadgeType.WARNING
        )
        else -> Quad(
            if (isDark) ErrorRedBgDark else ErrorRedBg,
            if (isDark) ErrorRedDark.copy(alpha = 0.35f) else ErrorRed.copy(alpha = 0.35f),
            "فرق أكبر من 1 ج.م",
            PharmaBadgeType.ERROR
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        shape = PharmaShapes.large,
        color = containerColor,
        border = BorderStroke(PharmaDimens.borderThin, borderColor)
    ) {
        Column(Modifier.padding(PharmaDimens.space14)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(PharmaDimens.space8))
                    Text(
                        "مطابقة إجمالي الفاتورة",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                PharmaStatusBadge(
                    text = "$itemsCount صنف",
                    type = PharmaBadgeType.NEUTRAL
                )
            }

            Spacer(Modifier.height(PharmaDimens.space10))

            if (!check.hasPrintedTotal) {
                Text(
                    "لم يتم العثور على الإجمالي المطبوع في رأس الفاتورة. يرجى مراجعة أسعار الأصناف أدناه وتأكيدها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                    PharmaMetricTile(
                        label = "المطبوع بالفاتورة",
                        value = "${formatInvoiceMoney(check.printedTotal)} ج.م",
                        modifier = Modifier.weight(1f)
                    )
                    PharmaMetricTile(
                        label = "المحسوب من الأصناف",
                        value = "${formatInvoiceMoney(check.calculatedTotal)} ج.م",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(PharmaDimens.space10))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PharmaStatusBadge(
                        text = badgeText,
                        type = badgeType
                    )

                    if (!check.matches) {
                        Text(
                            "الفرق: ${formatInvoiceMoney(check.difference)} ج.م",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (badgeType == PharmaBadgeType.WARNING) {
                                if (isDark) OnWarningAmberBgDark else OnWarningAmberBg
                            } else {
                                if (isDark) OnErrorRedBgDark else OnErrorRedBg
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatInvoiceMoney(value: Double): String =
    String.format(Locale.US, "%.2f", value)

private fun formatReviewQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
private fun PostSendCorrectionPickerDialog(
    items: List<OcrItem>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("تصحيح مطابقة بعد الإرسال", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space10)) {
                Surface(
                    shape = PharmaShapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        "اختر الصنف الغلط لتصحيح حفظ التطبيق للمستقبل. " +
                            "سطر الفاتورة الحالية داخل E-PLUS يجب تصحيحه يدويًا، ولا تعِد إرسال الفاتورة.",
                        modifier = Modifier.padding(PharmaDimens.space10),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(PharmaDimens.space6)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { index, item -> "$index|${item.itmCode}|${item.invoiceName}" }
                    ) { index, item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) },
                            shape = PharmaShapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(PharmaDimens.space10),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space10)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.invoiceName,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "كود E-PLUS: ${item.itmCode.ifBlank { "—" }}  •  الكمية: ${formatReviewQuantity(item.quantity)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun SplitQuantityDialog(
    itemName: String,
    originalQuantity: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var quantityText by remember(originalQuantity) { mutableStateOf("") }
    val splitQuantity = quantityText.toDoubleOrNull()
    val isValid = splitQuantity != null && splitQuantity > 0.0 && splitQuantity < originalQuantity
    val remainingQuantity = if (isValid) originalQuantity - splitQuantity!! else null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("تقسيم الكمية على صلاحيتين", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space10)) {
                Text(
                    itemName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "الكمية الحالية: ${formatReviewQuantity(originalQuantity)}. " +
                        "اكتب كمية الكارت الجديد، وبعد التقسيم غيّر صلاحية كل كارت.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("كمية الكارت الجديد") },
                    placeholder = { Text("مثال: 5") },
                    singleLine = true,
                    isError = quantityText.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        when {
                            quantityText.isNotBlank() && !isValid -> Text(
                                "أدخل رقمًا أكبر من صفر وأقل من ${formatReviewQuantity(originalQuantity)}"
                            )
                            remainingQuantity != null -> Text(
                                "سيبقى في الكارت الأصلي: ${formatReviewQuantity(remainingQuantity)}"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(splitQuantity!!) },
                enabled = isValid
            ) { Text("تأكيد التقسيم") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun ReviewItemCard(
    item: OcrItem,
    index: Int,
    supplierCode: String = "",
    onDelete: () -> Unit,
    onRemap: () -> Unit,
    onSplit: (Double) -> Unit = {},
    canMerge: Boolean = false,
    mergeCandidateQuantity: Double = 0.0,
    onMerge: () -> Unit = {},
    onExpiryModeChange: (String) -> Unit = {},
    onUpdate: (OcrItem) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExpiryPicker by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showMergeConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp)) },
            title = { Text("حذف الصنف من الفاتورة؟", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = { Text("سيتم استبعاد \"${item.invoiceName}\" ولن يتم إدخاله إلى برنامج E-PLUS.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = PharmaShapes.medium
                ) { Text("نعم، احذف") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("إلغاء") }
            }
        )
    }

    if (showExpiryPicker) {
        ExpiryMonthYearPickerDialog(
            initialMonth = item.expiryMonth,
            initialYear = item.expiryYear,
            onDismiss = { showExpiryPicker = false },
            onConfirm = { month, year ->
                showExpiryPicker = false
                onUpdate(item.copy(expiryMonth = month, expiryYear = year, expiryMode = ExpiryMode.REQUIRED))
            }
        )
    }

    if (showSplitDialog) {
        SplitQuantityDialog(
            itemName = item.invoiceName,
            originalQuantity = item.quantity,
            onDismiss = { showSplitDialog = false },
            onConfirm = { splitQuantity ->
                showSplitDialog = false
                onSplit(splitQuantity)
            }
        )
    }

    if (showMergeConfirm) {
        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            icon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null) },
            title = { Text("دمج الصنف المكرر", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "سيتم جمع ${formatReviewQuantity(item.quantity)} + ${formatReviewQuantity(mergeCandidateQuantity)} " +
                        "في كارت واحد بنفس كود E-PLUS، مع الاحتفاظ بباقي بيانات أقدم كارت."
                )
            },
            confirmButton = {
                Button(onClick = { showMergeConfirm = false; onMerge() }) {
                    Text("تأكيد الدمج")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirm = false }) { Text("إلغاء") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PharmaShapes.large,
        color = if (!item.purchasePriceMethodsMatch) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
        } else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (!item.purchasePriceMethodsMatch) 2.dp else PharmaDimens.borderThin,
            if (!item.purchasePriceMethodsMatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        ),
        shadowElevation = PharmaDimens.elevationLow
    ) {
        Column(modifier = Modifier.padding(PharmaDimens.space14)) {
            // Header Row: Item Name + editable E-PLUS code, index badge, delete action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$index",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.width(PharmaDimens.space10))

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.invoiceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(PharmaDimens.space6))

                    // Compact code-only pill; tapping it opens the remapping screen.
                    AssistChip(
                        onClick = onRemap,
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                        label = {
                            Text(
                                item.itmCode.ifBlank { "—" },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "تعديل الكود",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (item.itmCode.isEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            labelColor = if (item.itmCode.isEmpty()) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
                        ),
                        shape = PillShape
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "حذف الصنف من الفاتورة",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(PharmaDimens.space8))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)
            ) {
                OutlinedButton(
                    onClick = { showSplitDialog = true },
                    enabled = item.quantity > 1.0,
                    modifier = Modifier.weight(1f),
                    shape = PharmaShapes.medium,
                    contentPadding = PaddingValues(horizontal = PharmaDimens.space8)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(PharmaDimens.space6))
                    Text("تقسيم الكمية")
                }
                OutlinedButton(
                    onClick = { showMergeConfirm = true },
                    enabled = canMerge,
                    modifier = Modifier.weight(1f),
                    shape = PharmaShapes.medium,
                    contentPadding = PaddingValues(horizontal = PharmaDimens.space8)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(PharmaDimens.space6))
                    Text("دمج المكرر")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = PharmaDimens.space10),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Quantities & Taxes Row (Adaptive for narrow 360dp screens)
            Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space6)) {
                PharmaFieldCell(
                    value = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity.toString(),
                    label = "الكمية",
                    modifier = Modifier.weight(1f)
                ) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(quantity = v)) }
                }

                PharmaFieldCell(
                    value = if (item.bonus % 1.0 == 0.0) item.bonus.toInt().toString() else item.bonus.toString(),
                    label = "بونص",
                    modifier = Modifier.weight(1f)
                ) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(bonus = v)) }
                }

                PharmaFieldCell(
                    value = item.taxes.toString(),
                    label = "ضريبة",
                    modifier = Modifier.weight(1f)
                ) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(taxes = v)) }
                }
            }

            // For United and Tabark this is a derived reference only. It is
            // deliberately not editable and is never sent as E-PLUS discount.
            item.referenceDiscountPercent?.let { referenceDiscount ->
                Spacer(Modifier.height(PharmaDimens.space8))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            "خصم مرجعي محسوب: ${formatInvoiceMoney(referenceDiscount)}% (للمراجعة فقط)",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Percent, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                        disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        disabledLeadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }

            Spacer(Modifier.height(PharmaDimens.space8))

            // Pricing + expiry row: the three fields intentionally share the
            // same visual treatment and horizontal rhythm.
            Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space6)) {
                if (item.priceAlertKind == SupplierInvoiceRules.ALERT_PURCHASE_PRICE) {
                    Surface(
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        shape = PharmaShapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("دريم: س.بيع ثابت", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            Text("تكلفة الصيدلية فقط", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (item.shouldUpdateSalePrice) {
                    PharmaFieldCell(
                        value = item.salePrice.toString(),
                        label = "سعر البيع",
                        modifier = Modifier.weight(1f)
                    ) {
                        it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(salePrice = v)) }
                    }
                } else {
                    Surface(
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = PharmaShapes.medium
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("س. البيع غير محدد", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("تخطي للحفاظ على القديم", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                PharmaFieldCell(
                    value = item.price.toString(),
                    label = "سعر الشراء",
                    modifier = Modifier.weight(1f)
                ) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(price = v)) }
                }

                ExpiryFieldCell(
                    value = if (item.expiryMonth.length == 2 && item.expiryYear.length == 2)
                        "${item.expiryMonth}/${item.expiryYear}"
                    else if (item.expiryMode == ExpiryMode.NOT_REQUIRED) "بدون صلاحية" else "اختيار",
                    modifier = Modifier.weight(1f),
                    expiryMode = item.expiryMode,
                    onClick = { showExpiryPicker = true }
                )
            }

            if (supplierCode.trim() == "175") {
                Spacer(Modifier.height(PharmaDimens.space8))
                Text(
                    "الصلاحية حسب الصنف",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space6)) {
                    FilterChip(
                        selected = item.expiryMode == ExpiryMode.REQUIRED,
                        onClick = { onExpiryModeChange(ExpiryMode.REQUIRED) },
                        label = { Text("له صلاحية") },
                        leadingIcon = if (item.expiryMode == ExpiryMode.REQUIRED) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = item.expiryMode == ExpiryMode.NOT_REQUIRED,
                        onClick = { onExpiryModeChange(ExpiryMode.NOT_REQUIRED) },
                        label = { Text("بدون صلاحية") },
                        leadingIcon = if (item.expiryMode == ExpiryMode.NOT_REQUIRED) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (item.expiryMode == ExpiryMode.UNKNOWN && item.expiryMonth.isBlank()) {
                    Text(
                        "حدد الاختيار مرة واحدة؛ سيُحفظ تلقائياً لهذا الصنف في الفواتير القادمة.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpiryMonthYearPickerDialog(
    initialMonth: String,
    initialYear: String,
    onDismiss: () -> Unit,
    onConfirm: (month: String, year: String) -> Unit
) {
    val context = LocalContext.current
    val now = remember { java.util.Calendar.getInstance() }
    val currentFullYear = now.get(java.util.Calendar.YEAR)
    val currentYear = currentFullYear % 100
    val currentMonth = now.get(java.util.Calendar.MONTH) + 1

    fun isAllowedExpiry(month: Int, year: Int): Boolean {
        val fullYear = 2000 + year
        return fullYear > currentFullYear || (fullYear == currentFullYear && month > currentMonth)
    }

    val years = (currentYear..99).toList()
    val initialYearValue = initialYear.toIntOrNull()?.takeIf { it in years }
    val initialMonthValue = initialMonth.toIntOrNull()?.takeIf { it in 1..12 }
    var selectedYear by remember { mutableStateOf(initialYearValue ?: 0) }
    var selectedMonth by remember { mutableStateOf(initialMonthValue ?: 0) }
    val initialYearIndex = initialYearValue
        ?.let { years.indexOf(it) }
        ?.takeIf { it >= 0 }
        ?: 0
    val yearListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (initialYearIndex - 2).coerceAtLeast(0)
    )
    val hasSelectedYear = selectedYear in years
    val hasSelectedMonth = selectedMonth in 1..12
    val selectedDateIsPast = hasSelectedYear && hasSelectedMonth &&
        !isAllowedExpiry(selectedMonth, selectedYear)
    val monthsUntilExpiry = if (hasSelectedYear && hasSelectedMonth) {
        (2000 + selectedYear - currentFullYear) * 12 + selectedMonth - currentMonth
    } else null
    val lessThanEightMonths = monthsUntilExpiry != null &&
        monthsUntilExpiry in 1..7
    fun commitIfComplete(month: Int, year: Int) {
        if (month !in 1..12 || year !in years || !isAllowedExpiry(month, year)) return
        if (isAllowedExpiry(month, year)) {
            val monthsLeft = (2000 + year - currentFullYear) * 12 + month - currentMonth
            if (monthsLeft in 1..7) {
                android.widget.Toast.makeText(
                    context,
                    "تنبيه: متبقي على الصلاحية أقل من ٨ شهور",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            onConfirm(
                "%02d".format(Locale.US, month),
                "%02d".format(Locale.US, year)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("اختيار الصلاحية", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                Text(
                    "اختر الشهر والسنة وسيتم الحفظ تلقائيًا. الشهر الحالي هو " +
                        "\u200E${"%02d".format(Locale.US, currentMonth)}/${"%02d".format(Locale.US, currentYear)}\u200E",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space10),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(2f)) {
                            Text(
                                "الشهر",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(PharmaDimens.space6))
                            Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space6)) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    (1..6).forEach { month ->
                                        ExpiryWheelOption(
                                            value = month,
                                            selected = selectedMonth == month,
                                            onClick = {
                                                selectedMonth = month
                                                if (hasSelectedYear) commitIfComplete(month, selectedYear)
                                            }
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    (7..12).forEach { month ->
                                        ExpiryWheelOption(
                                            value = month,
                                            selected = selectedMonth == month,
                                            onClick = {
                                                selectedMonth = month
                                                if (hasSelectedYear) commitIfComplete(month, selectedYear)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        VerticalDivider(
                            modifier = Modifier.height(274.dp),
                            thickness = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "السنة",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(PharmaDimens.space6))
                            LazyColumn(
                                state = yearListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(248.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(years, key = { it }) { year ->
                                    ExpiryWheelOption(
                                        value = year,
                                        selected = selectedYear == year,
                                        onClick = {
                                            selectedYear = year
                                            if (hasSelectedMonth) commitIfComplete(selectedMonth, year)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    !hasSelectedMonth || !hasSelectedYear -> Text(
                        "اختر شهر الصلاحية والسنة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    selectedDateIsPast -> Text(
                        "خطأ: تاريخ الصلاحية يجب أن يكون بعد الشهر الحالي " +
                            "(\u200E${"%02d".format(Locale.US, currentMonth)}/${"%02d".format(Locale.US, currentYear)}\u200E)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    lessThanEightMonths -> Text(
                        "تنبيه: متبقي على الصلاحية أقل من ٨ شهور",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun ExpiryWheelOption(
    value: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = PharmaShapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "%02d".format(Locale.US, value),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ExpiryFieldCell(
    value: String,
    modifier: Modifier,
    expiryMode: String = ExpiryMode.REQUIRED,
    onClick: () -> Unit
) {
    val isMissing = (value.isBlank() || value == "اختيار") && expiryMode == ExpiryMode.REQUIRED
    val isUnknown = (value.isBlank() || value == "اختيار") && expiryMode == ExpiryMode.UNKNOWN
    val borderColor = when {
        isMissing -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        isUnknown -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        isMissing -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        isUnknown -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }
    val contentColor = when {
        isMissing -> MaterialTheme.colorScheme.error
        isUnknown -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Box(modifier = modifier.defaultMinSize(minHeight = PharmaDimens.minTouchTarget)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = {
                Text(
                    "الصلاحية",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "الصلاحية",
                    modifier = Modifier.size(14.dp),
                    tint = contentColor
                )
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            singleLine = true,
            shape = PharmaShapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = containerColor,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = borderColor
            )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick)
        )
    }
}

@Composable
fun PharmaFieldCell(
    value: String,
    label: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        singleLine = true,
        shape = PharmaShapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun PharmaHeaderSmallField(
    value: String,
    label: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        shape = PharmaShapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun MappingDialog(
    invoiceName: String,
    initialSearchQuery: String = "",
    onDismiss: () -> Unit,
    onSelect: (PharmacyItem) -> Unit
) {
    val context = LocalContext.current
    var query by remember(invoiceName, initialSearchQuery) { mutableStateOf(initialSearchQuery) }
    var results by remember { mutableStateOf<List<PharmacyItem>>(emptyList()) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialSearchQuery) {
        if (initialSearchQuery.isNotBlank()) {
            results = ItemsDatabase.search(context, initialSearchQuery)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = PharmaShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = PharmaDimens.elevationMedium
        ) {
            Column(modifier = Modifier.padding(PharmaDimens.space16)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "مطابقة الصنف مع قاعدة الأدوية",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            invoiceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { showAddItemDialog = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.AddCircle, contentDescription = "إضافة صنف جديد", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }
                
                Spacer(Modifier.height(PharmaDimens.space12))

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        scope.launch { results = ItemsDatabase.search(context, it) }
                    },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
                    placeholder = { Text("ابحث باسم الدواء أو الكود...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث", tint = MaterialTheme.colorScheme.primary) },
                    shape = PharmaShapes.medium,
                    singleLine = true
                )

                Spacer(Modifier.height(PharmaDimens.space12))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)
                ) {
                    items(results) { item ->
                        SearchResultItem(
                            item = item,
                            onEditBarcode = { /* No-op in Review mapping */ },
                            onClick = { onSelect(item) }
                        )
                    }
                }

                Spacer(Modifier.height(PharmaDimens.space12))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("إغلاق") }
                }
            }
        }
    }

    if (showAddItemDialog) {
        AddNewItemDialog(
            initialName = query.ifBlank { invoiceName },
            onDismiss = { showAddItemDialog = false },
            onConfirm = { newItem ->
                scope.launch {
                    ItemsDatabase.addNewItem(context, newItem)
                    showAddItemDialog = false
                    results = ItemsDatabase.search(context, query)
                }
            }
        )
    }
}

// ============================================================================
// 📱 Previews for ReviewScreen
// ============================================================================

@Preview(name = "Review Screen - Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun PreviewReviewScreenLight() {
    val sampleItems = listOf(
        OcrItem(
            invoiceName = "PANADOL EXTRA 24 TAB",
            quantity = 10.0,
            bonus = 1.0,
            unitPrice = 45.0,
            discountPercent = 10.0,
            price = 40.5,
            salePrice = 50.0,
            itmCode = "10024"
        ),
        OcrItem(
            invoiceName = "AUGMENTIN 1GM 14 TAB",
            quantity = 5.0,
            bonus = 0.0,
            unitPrice = 90.0,
            discountPercent = 5.0,
            price = 85.5,
            salePrice = 110.0,
            itmCode = ""
        )
    )

    OrderAutomatingTheme(darkTheme = false) {
        ReviewScreen(
            initialSupplierCode = "29",
            initialInvoiceNumber = "INV-2026-99",
            response = OcrResponse(
                supplierName = "ابن سينا فارما",
                invoiceNumber = "INV-2026-99",
                invoiceTotalAsPrinted = 832.50,
                items = sampleItems
            ),
            onBack = {},
            onOpenSettings = {}
        )
    }
}
