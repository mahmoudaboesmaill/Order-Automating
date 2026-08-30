package com.pharma.link.orderautomating

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private data class MappingTarget(val supplierCode: String, val invoiceName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingLearningScreen(
    initialSupplierCode: String = "",
    initialInvoiceName: String = "",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: MappingLearningViewModel = viewModel()
    val mappings by viewModel.mappings.collectAsState()
    val trainingCandidates by viewModel.trainingCandidates.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val status by viewModel.status.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(if (initialInvoiceName.isBlank()) 0 else 1) }
    var supplierCode by rememberSaveable {
        mutableStateOf(initialSupplierCode.ifBlank {
            ServerManager.getSelectedSupplierCode(context).orEmpty()
        })
    }
    var mappingTarget by remember { mutableStateOf<MappingTarget?>(null) }
    var mappingToDelete by remember { mutableStateOf<LearnedMapping?>(null) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.analyzeDocuments(context, uris, supplierCode)
    }

    LaunchedEffect(Unit) {
        viewModel.loadMappings(context)
        if (initialSupplierCode.isNotBlank() && initialInvoiceName.isNotBlank()) {
            mappingTarget = MappingTarget(initialSupplierCode, initialInvoiceName)
        }
    }

    mappingTarget?.let { target ->
        MappingDialog(
            invoiceName = target.invoiceName,
            initialSearchQuery = target.invoiceName,
            onDismiss = { mappingTarget = null },
            onSelect = { item ->
                viewModel.saveMapping(
                    context = context,
                    supplierCode = target.supplierCode,
                    invoiceName = target.invoiceName,
                    item = item
                )
                mappingTarget = null
            }
        )
    }

    mappingToDelete?.let { mapping ->
        AlertDialog(
            onDismissRequest = { mappingToDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("نسيان المطابقة؟") },
            text = {
                Text("سيطلب التطبيق مطابقة \"${mapping.invoiceName}\" من جديد في المرة القادمة.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.forgetMapping(context, mapping)
                        mappingToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("نسيان") }
            },
            dismissButton = {
                TextButton(onClick = { mappingToDelete = null }) { Text("إلغاء") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مركز التعلم والمطابقات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("المطابقات المحفوظة") },
                    icon = { Icon(Icons.Default.Link, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("تدريب من فواتير") },
                    icon = { Icon(Icons.Default.School, contentDescription = null) }
                )
            }

            if (selectedTab == 0) {
                SavedMappingsPane(
                    mappings = mappings,
                    status = status,
                    onEdit = { mappingTarget = MappingTarget(it.supplierCode, it.invoiceName) },
                    onDelete = { mappingToDelete = it }
                )
            } else {
                TrainingPane(
                    supplierCode = supplierCode,
                    onSupplierCodeChange = { supplierCode = it.filter(Char::isDigit) },
                    candidates = trainingCandidates,
                    isBusy = isBusy,
                    status = status,
                    onPickDocuments = {
                        documentPicker.launch(arrayOf("image/*", "application/pdf"))
                    },
                    onMap = { candidate ->
                        mappingTarget = MappingTarget(candidate.supplierCode, candidate.invoiceName)
                    },
                    onMapManual = { name ->
                        mappingTarget = MappingTarget(supplierCode.trim(), name.trim())
                    },
                    onClear = viewModel::clearTraining
                )
            }
        }
    }
}

@Composable
private fun SavedMappingsPane(
    mappings: List<LearnedMapping>,
    status: String,
    onEdit: (LearnedMapping) -> Unit,
    onDelete: (LearnedMapping) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var supplierFilter by rememberSaveable { mutableStateOf("") }
    val visibleMappings = remember(mappings, query, supplierFilter) {
        val normalizedQuery = ArabicNormalizer.normalize(query)
        mappings.filter { mapping ->
            val supplierMatches = supplierFilter.isBlank() || mapping.supplierCode == supplierFilter.trim()
            val queryMatches = normalizedQuery.isBlank() || listOf(
                mapping.invoiceName,
                mapping.itemName,
                mapping.itmCode
            ).any { ArabicNormalizer.normalize(it).contains(normalizedQuery) }
            supplierMatches && queryMatches
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("بحث بالاسم أو الكود") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = supplierFilter,
                    onValueChange = { supplierFilter = it.filter(Char::isDigit) },
                    modifier = Modifier.width(105.dp),
                    label = { Text("المورد") },
                    singleLine = true
                )
            }
        }
        item {
            Text(
                "${visibleMappings.size} مطابقة محفوظة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (status.isNotBlank()) item { MappingStatusBanner(status) }
        if (visibleMappings.isEmpty()) {
            item {
                Text(
                    if (mappings.isEmpty()) "لا توجد مطابقات محفوظة بعد." else "لا توجد نتائج مطابقة للبحث.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(
                items = visibleMappings,
                key = { "${it.supplierCode}:${it.invoiceName}" }
            ) { mapping ->
                SavedMappingCard(mapping, onEdit, onDelete)
            }
        }
    }
}

@Composable
private fun SavedMappingCard(
    mapping: LearnedMapping,
    onEdit: (LearnedMapping) -> Unit,
    onDelete: (LearnedMapping) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(mapping.invoiceName, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(
                        "← ${mapping.itemName.ifBlank { "صنف غير موجود" }}  •  كود ${mapping.itmCode.ifBlank { "—" }}",
                        color = if (mapping.isOrphaned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text("مورد ${mapping.supplierCode}") }
                )
            }
            if (mapping.isOrphaned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "الكود المحفوظ غير موجود في قاعدة الأصناف الحالية؛ أعد تعيينه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onEdit(mapping) }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("تعديل")
                }
                TextButton(onClick = { onDelete(mapping) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("نسيان")
                }
            }
        }
    }
}

@Composable
private fun TrainingPane(
    supplierCode: String,
    onSupplierCodeChange: (String) -> Unit,
    candidates: List<TrainingCandidate>,
    isBusy: Boolean,
    status: String,
    onPickDocuments: () -> Unit,
    onMap: (TrainingCandidate) -> Unit,
    onMapManual: (String) -> Unit,
    onClear: () -> Unit
) {
    var manualName by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("وضع تدريب آمن", fontWeight = FontWeight.Bold)
                        Text(
                            "يقرأ أسماء الأصناف فقط. لن ينشئ فاتورة أو ملفات TSV ولن يشغّل روبوت E-PLUS.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = supplierCode,
                onValueChange = onSupplierCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("كود المورد") },
                supportingText = { Text("كل الأسماء المستخرجة ستتعلّم لهذا المورد فقط") },
                singleLine = true
            )
        }
        item {
            Button(
                onClick = onPickDocuments,
                enabled = !isBusy && supplierCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("اختيار صور أو PDF قديمة")
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("أو أضف اسمًا يدويًا", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualName,
                    onValueChange = { manualName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("الاسم المكتوب في الفاتورة") },
                    singleLine = true
                )
                FilledIconButton(
                    onClick = {
                        onMapManual(manualName)
                    },
                    enabled = supplierCode.isNotBlank() && manualName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "ربط يدوي")
                }
            }
        }
        if (isBusy) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (status.isNotBlank()) item { MappingStatusBanner(status) }
        if (candidates.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("نتائج التدريب (${candidates.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear, enabled = !isBusy) { Text("مسح النتائج") }
                }
            }
            items(
                items = candidates,
                key = { "${it.supplierCode}:${ArabicNormalizer.normalize(it.invoiceName)}" }
            ) { candidate ->
                TrainingCandidateCard(candidate, onMap)
            }
        }
    }
}

@Composable
private fun TrainingCandidateCard(
    candidate: TrainingCandidate,
    onMap: (TrainingCandidate) -> Unit
) {
    val known = candidate.knownMapping
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(candidate.invoiceName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (known != null) {
                    Text(
                        "محفوظ: ${known.itemName.ifBlank { known.itmCode }} (${known.itmCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (known.isOrphaned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("جديد — يحتاج اختيار صنف E-PLUS", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { onMap(candidate) }) {
                Icon(if (known == null) Icons.Default.Link else Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (known == null) "ربط" else "تغيير")
            }
        }
    }
}

@Composable
private fun MappingStatusBanner(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(status, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}
