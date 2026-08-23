package com.pharma.link.orderautomating

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pharma.link.orderautomating.ui.components.*
import com.pharma.link.orderautomating.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MappingScreen(
    supplierCode: String,
    ocrItems: List<OcrItem>,
    onDone: (List<OcrItem>) -> Unit,
    onBack: () -> Unit,
    viewModel: MappingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val currentIndex by viewModel.currentIndex.collectAsState()
    val mappedItems by viewModel.mappedItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    
    var showScanner by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var itemToEditBarcode by remember { mutableStateOf<PharmacyItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadMappings(context, supplierCode, ocrItems)
    }

    if (currentIndex == -1) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= mappedItems.size && mappedItems.isNotEmpty()) {
            onDone(mappedItems)
        }
    }
    if (currentIndex >= mappedItems.size) return

    val currentItem = mappedItems[currentIndex]

    // AI Smart Match Suggestion state
    var suggestedItem by remember(currentItem.itmCode) { mutableStateOf<PharmacyItem?>(null) }
    LaunchedEffect(currentItem.itmCode, currentItem.fuzzyScore) {
        if (currentItem.fuzzyScore in 0.70..0.89 && currentItem.itmCode.isNotEmpty()) {
            suggestedItem = ItemsDatabase.getByCode(context, currentItem.itmCode)
        } else {
            suggestedItem = null
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = PharmaDimens.elevationLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space10)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "مطابقة أصناف الفاتورة",
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
                            Text(
                                "صنف ${currentIndex + 1} من ${mappedItems.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { onDone(mappedItems) },
                                shape = PharmaShapes.medium,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = PharmaDimens.minTouchTarget)
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("مراجعة الفاتورة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }

                            Spacer(Modifier.width(PharmaDimens.space8))

                            val progress = (currentIndex + 1).toFloat() / mappedItems.size
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 3.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = PharmaDimens.elevationMedium,
                shadowElevation = PharmaDimens.elevationHigh
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space12),
                    horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space12)
                ) {
                    PharmaSecondaryButton(
                        text = if (currentIndex > 0) "الصنف السابق" else "إلغاء والرجوع",
                        icon = Icons.Default.ChevronRight,
                        onClick = { viewModel.goBack(onBack) },
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = { viewModel.skipCurrent() },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = PharmaDimens.minTouchTarget).height(PharmaDimens.buttonHeight),
                        shape = PharmaShapes.medium,
                        border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("تخطي الصنف", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(PharmaDimens.space6))
                        Icon(Icons.Default.SkipNext, contentDescription = "تخطي الصنف", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space10)
        ) {
            // Hero Item Card to Map
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = PharmaShapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(PharmaDimens.space16)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(PharmaDimens.space6))
                        Text(
                            "اسم الصنف في الفاتورة:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(PharmaDimens.space4))
                    Text(
                        text = currentItem.invoiceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(Modifier.height(PharmaDimens.space12))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                        InfoChip(
                            text = "الكمية: ${if (currentItem.quantity % 1.0 == 0.0) currentItem.quantity.toInt().toString() else currentItem.quantity.toString()}",
                            icon = Icons.Default.Inventory
                        )
                        InfoChip(
                            text = currentItem.referenceDiscountPercent?.let {
                                "خصم مرجعي: ${String.format(Locale.US, "%.2f", it)}%"
                            } ?: "الخصم المطبوع: ${currentItem.discountPercent}%",
                            icon = Icons.Default.Percent
                        )
                        if (currentItem.bonus > 0.0) {
                            InfoChip(
                                text = "بونص: ${if (currentItem.bonus % 1.0 == 0.0) currentItem.bonus.toInt().toString() else currentItem.bonus.toString()}",
                                icon = Icons.Default.CardGiftcard
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(PharmaDimens.space12))

            // Smart suggestion card (shown only when confidence is high enough
            // to be useful, but still requires explicit human confirmation).
            suggestedItem?.let { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = PharmaDimens.space12),
                    shape = PharmaShapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(PharmaDimens.space16)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(PharmaDimens.space6))
                                Text(
                                    "مقترح للمطابقة",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            PharmaStatusBadge(
                                text = "${(currentItem.fuzzyScore * 100).toInt()}% تطابق",
                                type = PharmaBadgeType.INFO
                            )
                        }
                        Spacer(Modifier.height(PharmaDimens.space8))
                        Text(
                            "الاسم المقترح",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.nameEn,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "الكود المحلي: ${item.itmCode}  •  راجع الاسم قبل الاعتماد",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(Modifier.height(PharmaDimens.space12))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(PharmaDimens.space8)) {
                            Button(
                                onClick = { viewModel.selectItem(context, supplierCode, item) },
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = PharmaShapes.medium
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("اعتماد المقترح", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { suggestedItem = null },
                                modifier = Modifier.weight(0.6f).defaultMinSize(minHeight = 46.dp),
                                shape = PharmaShapes.medium
                            ) {
                                Text("بحث يدوي")
                            }
                        }
                    }
                }
            }

            // Search Bar with Barcode Scanner Icon
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(context, it) },
                placeholder = { Text("ابحث باسم الدواء أو الكود أو الباركود...") },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث", tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    IconButton(onClick = { showScanner = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "مسح بالباركود", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                singleLine = true,
                shape = PharmaShapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(Modifier.height(PharmaDimens.space10))

            // Search Results Header with Add New Item button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (searchResults.isEmpty()) "نتائج البحث" else "النتائج المطابقة (${searchResults.size}):",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = { showAddItemDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("إضافة صنف جديد", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(PharmaDimens.space6))

            if (searchResults.isEmpty() && searchQuery.length >= 2) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text("لم يتم العثور على صنف مطابق", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showAddItemDialog = true },
                            shape = PharmaShapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("إضافة هذا الصنف لقاعدتك الآن")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PharmaDimens.space8)
                ) {
                    items(searchResults) { item ->
                        SearchResultItem(
                            item = item,
                            onEditBarcode = { itemToEditBarcode = item },
                            onClick = {
                                viewModel.selectItem(context, supplierCode, item)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            BarcodeScannerScreen(
                onBarcodeDetected = { barcode ->
                    showScanner = false
                    viewModel.selectByBarcode(context, supplierCode, barcode)
                },
                onDismiss = { showScanner = false }
            )
        }
    }

    if (showAddItemDialog) {
        AddNewItemDialog(
            initialName = searchQuery.ifBlank { currentItem.invoiceName },
            onDismiss = { showAddItemDialog = false },
            onConfirm = { newItem ->
                scope.launch {
                    ItemsDatabase.addNewItem(context, newItem)
                    showAddItemDialog = false
                    viewModel.search(context, searchQuery)
                }
            }
        )
    }

    itemToEditBarcode?.let { item ->
        EditBarcodeDialog(
            item = item,
            onDismiss = { itemToEditBarcode = null },
            onConfirm = { updatedBarcode ->
                scope.launch {
                    ItemsDatabase.updateItem(context, item.copy(barcode = updatedBarcode))
                    itemToEditBarcode = null
                    viewModel.search(context, searchQuery)
                }
            }
        )
    }
}

@Composable
fun EditBarcodeDialog(item: PharmacyItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var barcode by remember { mutableStateOf(item.barcode) }
    var showScannerInEdit by remember { mutableStateOf(false) }

    if (showScannerInEdit) {
        Dialog(onDismissRequest = { showScannerInEdit = false }) {
            BarcodeScannerScreen(
                onBarcodeDetected = { detected ->
                    barcode = detected
                    showScannerInEdit = false
                },
                onDismiss = { showScannerInEdit = false }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحديث باركود الصنف", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.nameEn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("كود محلي: ${item.itmCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("الباركود الدولي الجديد") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = PharmaShapes.medium,
                    trailingIcon = {
                        IconButton(onClick = { showScannerInEdit = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "مسح بالباركود", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(barcode) },
                shape = PharmaShapes.medium
            ) { Text("حفظ التحديث") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun AddNewItemDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (PharmacyItem) -> Unit) {
    var itmCode by remember { mutableStateOf("") }
    var nameEn by remember { mutableStateOf(initialName) }
    var barcode by remember { mutableStateOf("") }
    var showScannerInDialog by remember { mutableStateOf(false) }

    if (showScannerInDialog) {
        Dialog(onDismissRequest = { showScannerInDialog = false }) {
            BarcodeScannerScreen(
                onBarcodeDetected = { detected ->
                    barcode = detected
                    showScannerInDialog = false
                },
                onDismiss = { showScannerInDialog = false }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة صنف لقاعدة الأدوية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = itmCode,
                    onValueChange = { itmCode = it },
                    label = { Text("كود الصنف في E-PLUS (إلزامي)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = PharmaShapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text("اسم الصنف (إنجليزي / عربي)") },
                    shape = PharmaShapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("الباركود الدولي (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = PharmaShapes.medium,
                    trailingIcon = {
                        IconButton(onClick = { showScannerInDialog = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "مسح باركود بالكاميرا", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (itmCode.isNotBlank() && nameEn.isNotBlank()) {
                        onConfirm(PharmacyItem(itmCode = itmCode.trim(), nameAr = nameEn.trim(), nameEn = nameEn.trim(), barcode = barcode.trim()))
                    }
                },
                enabled = itmCode.isNotBlank() && nameEn.isNotBlank(),
                shape = PharmaShapes.medium
            ) { Text("حفظ ومطابقة") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun SearchResultItem(item: PharmacyItem, onEditBarcode: () -> Unit, onClick: () -> Unit) {
    var showFullName by remember { mutableStateOf(false) }

    if (showFullName) {
        AlertDialog(
            onDismissRequest = { showFullName = false },
            title = { Text("اسم الصنف بالكامل", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PharmaDimens.space6)) {
                    Text(
                        item.nameEn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "كود: ${item.itmCode}  •  باركود: ${item.barcode.ifEmpty { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullName = false }) { Text("إغلاق") }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showFullName = true }
            ),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.outlineVariant),
        shape = PharmaShapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PharmaDimens.space12, vertical = PharmaDimens.space10),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = PharmaShapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Medication,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(PharmaDimens.space12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.nameEn,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "كود: ${item.itmCode}  •  باركود: ${item.barcode.ifEmpty { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onEditBarcode,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (item.barcode.isEmpty()) Icons.Default.AddCircleOutline else Icons.Default.Edit,
                    contentDescription = "تعديل باركود الصنف",
                    tint = if (item.barcode.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun InfoChip(
    text: String,
    icon: ImageVector
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = PharmaShapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(name = "Mapping Screen - Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun PreviewMappingScreen() {
    val sampleItems = listOf(
        OcrItem(
            invoiceName = "CONCOR 5 PLUS 30 TAB",
            quantity = 2.0,
            discountPercent = 12.0
        )
    )
    OrderAutomatingTheme(darkTheme = false) {
        MappingScreen(
            supplierCode = "38",
            ocrItems = sampleItems,
            onDone = {},
            onBack = {}
        )
    }
}
