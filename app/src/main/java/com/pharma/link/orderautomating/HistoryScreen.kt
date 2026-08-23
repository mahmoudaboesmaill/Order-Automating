package com.pharma.link.orderautomating

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pharma.link.orderautomating.ui.components.*
import com.pharma.link.orderautomating.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

private enum class HistoryFilter { ALL, TODAY, NEEDS_REVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).invoiceRecordDao() }
    var records by remember { mutableStateOf<List<InvoiceRecord>>(emptyList()) }
    var supplierQuery by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<InvoiceRecord?>(null) }
    var exportRecords by remember { mutableStateOf<List<InvoiceRecord>>(emptyList()) }

    fun reload() { scope.launch { records = dao.getAll() } }
    LaunchedEffect(Unit) { records = dao.getAll() }

    val visibleRecords = remember(records, supplierQuery, filter) {
        val query = supplierQuery.trim()
        records.filter { record ->
            // كود المورد رقم مرجعي؛ المطابقة الدقيقة تمنع خلط مورد 29 مثلاً
            // مع أي كود آخر يحتوي على نفس الأرقام.
            val queryMatches = query.isBlank() || record.supplierCode.trim() == query
            val filterMatches = when {
                query.isNotBlank() -> isToday(record.sentAt)
                filter == HistoryFilter.TODAY -> isToday(record.sentAt)
                filter == HistoryFilter.NEEDS_REVIEW -> record.matchStatus in setOf("big_diff", "missing") || record.status != "success"
                else -> true
            }
            queryMatches && filterMatches
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch { writeHistoryCsv(context, uri, exportRecords) }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("مسح كل سجل الفواتير؟", fontWeight = FontWeight.Bold) },
            text = { Text("سيتم حذف جميع سجلات الفواتير السابقة نهائياً من ذاكرة الهاتف.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        scope.launch { dao.deleteAll(); records = emptyList() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = PharmaShapes.medium
                ) { Text("نعم، امسح السجل") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text("إلغاء") } }
        )
    }

    selectedRecord?.let { record -> HistoryDetailsDialog(record, onDismiss = { selectedRecord = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الفواتير", fontWeight = FontWeight.ExtraBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = {
                            exportRecords = visibleRecords
                            exportLauncher.launch("invoice_history_${todayFileName()}.csv")
                        }) { Icon(Icons.Default.Download, contentDescription = "تصدير السجل") }
                        IconButton(onClick = { showDeleteAllConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "مسح الكل", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = PharmaDimens.space16),
            contentPadding = PaddingValues(vertical = PharmaDimens.space12),
            verticalArrangement = Arrangement.spacedBy(PharmaDimens.space10)
        ) {
            item {
                HistorySearchPanel(
                    supplierQuery = supplierQuery,
                    onSupplierQueryChange = { supplierQuery = it.filter(Char::isDigit) },
                    filter = filter,
                    onFilterChange = { filter = it },
                    resultCount = visibleRecords.size,
                    onClear = { supplierQuery = ""; filter = HistoryFilter.ALL }
                )
            }
            item { HistorySummaryCard(records, visibleRecords, supplierQuery) }
            if (visibleRecords.isEmpty()) {
                item { EmptyHistoryState(supplierQuery.isNotBlank() || filter != HistoryFilter.ALL) }
            } else {
                items(visibleRecords, key = { it.id }) { record ->
                    HistoryItemCard(
                        record = record,
                        onClick = { selectedRecord = record },
                        onDelete = { scope.launch { dao.delete(record.id); reload() } }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySearchPanel(
    supplierQuery: String,
    onSupplierQueryChange: (String) -> Unit,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    resultCount: Int,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
        OutlinedTextField(
            value = supplierQuery,
            onValueChange = onSupplierQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("رقم المورد") },
            placeholder = { Text("اكتب الكود لعرض فواتير اليوم") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (supplierQuery.isNotBlank()) IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "مسح البحث") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = PharmaShapes.medium
        )
        if (supplierQuery.isNotBlank()) {
            Text("عرض فواتير المورد $supplierQuery لليوم فقط • $resultCount فاتورة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
            HistoryFilterChip("كل الفواتير", filter == HistoryFilter.ALL) { onFilterChange(HistoryFilter.ALL) }
            HistoryFilterChip("اليوم", filter == HistoryFilter.TODAY) { onFilterChange(HistoryFilter.TODAY) }
            HistoryFilterChip("تحتاج مراجعة", filter == HistoryFilter.NEEDS_REVIEW) { onFilterChange(HistoryFilter.NEEDS_REVIEW) }
        }
    }
}

@Composable
private fun HistoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null
    )
}

@Composable
private fun HistorySummaryCard(allRecords: List<InvoiceRecord>, visibleRecords: List<InvoiceRecord>, supplierQuery: String) {
    val total = visibleRecords.sumOf { it.totalPrice }
    val printed = visibleRecords.sumOf { it.printedTotal }
    val mismatches = visibleRecords.count { it.matchStatus in setOf("big_diff", "missing") }
    val success = allRecords.count { it.status == "success" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PharmaShapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(PharmaDimens.space16), verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Assessment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(PharmaDimens.space8))
                Text(if (supplierQuery.isBlank()) "ملخص السجل" else "ملخص المورد $supplierQuery — اليوم", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                HistoryMetric("الفواتير", visibleRecords.size.toString(), Modifier.weight(1f))
                HistoryMetric("الإجمالي المحسوب", money(total), Modifier.weight(1.3f))
            }
            if (printed > 0.0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                    HistoryMetric("المطبوع", money(printed), Modifier.weight(1f))
                    HistoryMetric("تحتاج مراجعة", mismatches.toString(), Modifier.weight(1f))
                }
            } else if (supplierQuery.isBlank()) {
                Text("تم الإرسال بنجاح: $success من ${allRecords.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = PharmaShapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
        Column(Modifier.padding(horizontal = PharmaDimens.space10, vertical = PharmaDimens.space8)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyHistoryState(hasSearch: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        Spacer(Modifier.height(PharmaDimens.space12))
        Text(if (hasSearch) "لا توجد فواتير مطابقة للبحث" else "لا توجد فواتير مسجلة في السجل بعد", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun HistoryItemCard(record: InvoiceRecord, onClick: () -> Unit = {}, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy — hh:mm a", Locale("ar")) }
    val status = matchStatusInfo(record)
    val borderColor = if (record.status == "success" && record.matchStatus != "big_diff") MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = PharmaShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = PharmaDimens.elevationLow
    ) {
        Column(Modifier.padding(PharmaDimens.space14), verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(status.icon, contentDescription = null, tint = status.color, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(PharmaDimens.space10))
                Column(Modifier.weight(1f)) {
                    Text(record.supplierName.ifBlank { supplierDisplayName(record.supplierCode) }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("كود المورد: ${record.supplierCode}  •  فاتورة: ${record.invoiceNumber.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "حذف السجل", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f), modifier = Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                HistoryValue("محسوب", money(record.totalPrice), Modifier.weight(1f))
                HistoryValue("مطبوع", if (record.printedTotal > 0.0) money(record.printedTotal) else "غير متاح", Modifier.weight(1f))
                HistoryValue("الفرق", if (record.printedTotal > 0.0) money(record.difference) else "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                HistoryTag("${record.itemsCount} صنف")
                if (record.priceChangesCount > 0) HistoryTag("${record.priceChangesCount} تغيير سعر")
                if (record.expiryPendingCount > 0) HistoryTag("${record.expiryPendingCount} صلاحية ناقصة", warning = true)
                HistoryTag(providerLabel(record.ocrProvider))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.label, style = MaterialTheme.typography.labelMedium, color = status.color, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(dateFormat.format(Date(record.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HistoryValue(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = PharmaShapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryTag(text: String, warning: Boolean = false) {
    Surface(shape = PharmaShapes.small, color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private data class StatusInfo(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color)

@Composable
private fun matchStatusInfo(record: InvoiceRecord): StatusInfo {
    val scheme = MaterialTheme.colorScheme
    return when {
        record.status != "success" -> StatusInfo("فشل الإرسال", Icons.Default.ErrorOutline, scheme.error)
        record.matchStatus == "match" -> StatusInfo("مطابق للإجمالي المطبوع", Icons.Default.CheckCircle, scheme.primary)
        record.matchStatus == "small_diff" -> StatusInfo("فرق بسيط — راجع قبل الاعتماد", Icons.Default.WarningAmber, scheme.tertiary)
        record.matchStatus == "big_diff" -> StatusInfo("فرق كبير — يحتاج مراجعة", Icons.Default.ErrorOutline, scheme.error)
        else -> StatusInfo("لم يُستخرج إجمالي مطبوع", Icons.Default.Info, scheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryDetailsDialog(record: InvoiceRecord, onDismiss: () -> Unit) {
    val items = remember(record.itemsJson) { parseHistoryItems(record.itemsJson) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("تفاصيل الفاتورة", fontWeight = FontWeight.ExtraBold)
                Text("${record.supplierName} • ${record.invoiceNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                    HistoryValue("المحسوب", money(record.totalPrice), Modifier.weight(1f))
                    HistoryValue("المطبوع", if (record.printedTotal > 0.0) money(record.printedTotal) else "—", Modifier.weight(1f))
                }
                Text(if (record.printedTotal > 0.0) "الفرق: ${money(record.difference)}" else "لا يوجد إجمالي مطبوع للمقارنة", color = if (record.matchStatus == "big_diff") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text("المعالج: ${providerLabel(record.ocrProvider)}  •  المصدر: ${sourceLabel(record.sourceType)}", style = MaterialTheme.typography.labelMedium)
                if (items.isNotEmpty()) {
                    Text("الأصناف (${items.size})", fontWeight = FontWeight.Bold)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) { items(items) { item -> HistoryDetailItem(item) } }
                } else {
                    Text("تفاصيل الأصناف غير متاحة للسجلات القديمة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

private data class HistoryItemDetail(val name: String, val code: String, val quantity: Double, val bonus: Double, val purchase: Double, val sale: Double, val expiry: String)

@Composable
private fun HistoryDetailItem(item: HistoryItemDetail) {
    Surface(shape = PharmaShapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(Modifier.padding(8.dp)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text("كود: ${item.code.ifBlank { "—" }} • كمية: ${number(item.quantity)} • بونص: ${number(item.bonus)}", style = MaterialTheme.typography.labelSmall)
            Text("شراء: ${money(item.purchase)} • بيع: ${money(item.sale)} • صلاحية: ${item.expiry.ifBlank { "لم تُدخل" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun parseHistoryItems(json: String): List<HistoryItemDetail> = runCatching {
    if (json.isBlank()) return emptyList()
    val array = JSONArray(json)
    (0 until array.length()).map { index ->
        val obj = array.getJSONObject(index)
        HistoryItemDetail(obj.optString("name"), obj.optString("itmCode"), obj.optDouble("quantity", 0.0), obj.optDouble("bonus", 0.0), obj.optDouble("purchasePrice", 0.0), obj.optDouble("salePrice", 0.0), obj.optString("expiry"))
    }
}.getOrDefault(emptyList())

private suspend fun writeHistoryCsv(context: Context, uri: Uri, records: List<InvoiceRecord>) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write("المورد,كود المورد,رقم الفاتورة,عدد الأصناف,الإجمالي المحسوب,الإجمالي المطبوع,الفرق,الحالة,مزود القراءة,التاريخ\n")
                records.forEach { record ->
                    val values = listOf(record.supplierName, record.supplierCode, record.invoiceNumber, record.itemsCount.toString(), money(record.totalPrice), if (record.printedTotal > 0) money(record.printedTotal) else "", if (record.printedTotal > 0) money(record.difference) else "", matchStatusLabel(record.matchStatus, record.status), providerLabel(record.ocrProvider), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.sentAt)))
                    writer.write(values.joinToString(",") { csvEscape(it) })
                    writer.write("\n")
                }
            }
        }
    }
}

private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun isToday(timestamp: Long): Boolean {
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { timeInMillis = timestamp }
    return now.get(Calendar.YEAR) == date.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
}

private fun todayFileName(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
private fun money(value: Double): String = String.format(Locale.US, "%.2f ج.م", value)
private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)
private fun providerLabel(value: String): String = when (value.lowercase(Locale.US)) { "gemini" -> "Gemini"; "mistral" -> "Mistral"; else -> "تلقائي" }
private fun sourceLabel(value: String): String = when (value.lowercase(Locale.US)) { "image" -> "صورة"; "pdf" -> "PDF"; else -> "غير محدد" }
private fun matchStatusLabel(matchStatus: String, status: String): String = when { status != "success" -> "فشل الإرسال"; matchStatus == "match" -> "مطابق"; matchStatus == "small_diff" -> "فرق بسيط"; matchStatus == "big_diff" -> "فرق كبير"; else -> "بدون إجمالي مطبوع" }
