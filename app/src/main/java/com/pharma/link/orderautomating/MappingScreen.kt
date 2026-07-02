package com.pharma.link.orderautomating

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

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

    // حالة للاقتراح الذكي
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
            Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مطابقة الأصناف", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    val progress = (currentIndex + 1).toFloat() / mappedItems.size
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text("صنف ${currentIndex + 1} من ${mappedItems.size}", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleSmall)
            }
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // زر السابق/إلغاء على اليسار (في RTL) أو اليمين (في LTR) - هنا نضعه كزر ثانوي
                    OutlinedButton(
                        onClick = { viewModel.goBack(onBack) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium
                    ) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (currentIndex > 0) "السابق" else "إلغاء")
                    }

                    // زر تخطي (الأكشن الأساسي للتقدم) على اليمين
                    Button(
                        onClick = { viewModel.skipCurrent() },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("تخطي")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            
            // كارت الصنف الحالي "البطل"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("الاسم في الفاتورة:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(currentItem.invoiceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, minLines = 2)
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoChip(
                            text = "كمية: ${currentItem.quantity}",
                            icon = Icons.Default.Inventory,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        )
                        InfoChip(
                            text = "خصم: ${currentItem.discountPercent}%",
                            icon = Icons.Default.Percent,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // عرض الاقتراح الذكي (Fuzzy Match)
            suggestedItem?.let { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "اقتراح ذكي (${(currentItem.fuzzyScore * 100).toInt()}% تشابه):",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(item.nameEn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("كود: ${item.itmCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.selectItem(context, supplierCode, item) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("قبول الاقتراح")
                            }
                            OutlinedButton(
                                onClick = { 
                                    // مسح الاقتراح للبحث اليدوي
                                    suggestedItem = null 
                                },
                                modifier = Modifier.weight(0.6f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("بحث يدوي")
                            }
                        }
                    }
                }
            }

            // شريط البحث "العائم"
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(context, it) },
                placeholder = { Text("ابحث باسم الدواء أو الكود...") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    IconButton(onClick = { showScanner = true }) {
                        Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
            )

            Spacer(Modifier.height(16.dp))

            // رأس قائمة النتائج مع زر الإضافة الدائم
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (searchResults.isEmpty()) "لا توجد نتائج" else "نتائج البحث (${searchResults.size}):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = { showAddItemDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إضافة صنف جديد", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (searchResults.isEmpty() && searchQuery.length >= 2) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Text("لم يتم العثور على نتائج للبحث الحالي", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            initialName = searchQuery,
            onDismiss = { showAddItemDialog = false },
            onConfirm = { newItem ->
                scope.launch {
                    ItemsDatabase.addNewItem(context, newItem)
                    showAddItemDialog = false
                    viewModel.search(context, searchQuery) // تحديث البحث
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
                    viewModel.search(context, searchQuery) // تحديث البحث
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
        title = { Text("تحديث الباركود", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.nameEn, style = MaterialTheme.typography.bodyMedium)
                Text("كود: ${item.itmCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("الباركود الدولي الجديد") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showScannerInEdit = true }) {
                            Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(barcode) }) { Text("تحديث") }
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
    val context = LocalContext.current

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
        title = { Text("إضافة صنف جديد", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = itmCode,
                    onValueChange = { itmCode = it },
                    label = { Text("كود الصنف المحلي (إلزامي)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text("الاسم (إنجليزي/عربي)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("الباركود (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showScannerInDialog = true }) {
                            Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
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
                        onConfirm(PharmacyItem(itmCode = itmCode, nameAr = nameEn, nameEn = nameEn, barcode = barcode))
                    }
                },
                enabled = itmCode.isNotBlank() && nameEn.isNotBlank()
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun SearchResultItem(item: PharmacyItem, onEditBarcode: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.nameEn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text("كود: ${item.itmCode} | باركود: ${item.barcode.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onEditBarcode) {
                Icon(
                    imageVector = if (item.barcode.isEmpty()) Icons.Default.AddCircleOutline else Icons.Default.Edit,
                    contentDescription = "تعديل الباركود",
                    tint = if (item.barcode.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun InfoChip(
    text: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
