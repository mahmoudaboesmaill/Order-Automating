package com.pharma.link.orderautomating

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun MappingScreen(
    supplierCode: String,
    ocrItems: List<OcrItem>,
    onDone: (List<OcrItem>) -> Unit,
    onBack: () -> Unit // أضفنا هذا البارامتر
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mappedItems = remember { ocrItems.toMutableList() }
    var currentIndex by remember { mutableStateOf(-1) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PharmacyItem>>(emptyList()) }
    var showScanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val db = AppDatabase.getDatabase(context)
        val mappingDao = db.smartMappingDao()
        val pharmacyDao = db.pharmacyItemDao()
        val sCode = supplierCode.trim().lowercase()

        mappedItems.forEachIndexed { i, item ->
            val mappingKey = item.invoiceName.trim().lowercase()
            val learnedCode = mappingDao.getMappedCode(sCode, mappingKey)
            if (learnedCode != null) {
                mappedItems[i] = item.copy(itmCode = learnedCode, matched = true)
            } else {
                val exactMatch = pharmacyDao.getByName(item.invoiceName.trim())
                if (exactMatch != null) {
                    mappedItems[i] = item.copy(itmCode = exactMatch.itmCode, matched = true)
                }
            }
        }
        val firstUnmapped = mappedItems.indexOfFirst { !it.matched }
        currentIndex = if (firstUnmapped != -1) firstUnmapped else mappedItems.size
    }

    if (currentIndex == -1) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (currentIndex >= mappedItems.size) {
        onDone(mappedItems)
        return
    }

    val currentItem = mappedItems[currentIndex]

    Scaffold(
        topBar = {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مطابقة الأصناف", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    val progress = (currentIndex + 1).toFloat() / mappedItems.size
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = progress, modifier = Modifier.size(40.dp), strokeWidth = 4.dp)
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                    }
                }
                Text("صنف ${currentIndex + 1} من ${mappedItems.size}", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
            }
        },
        bottomBar = {
            Surface(tonalElevation = 12.dp, shadowElevation = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { 
                            if (currentIndex > 0) currentIndex-- 
                            else onBack() // لو في أول صنف يرجع لشاشة الكاميرا
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (currentIndex > 0) "السابق" else "إلغاء")
                    }

                    Button(
                        onClick = {
                            val next = mappedItems.indexOfFirst { !it.matched && mappedItems.indexOf(it) > currentIndex }
                            currentIndex = if (next != -1) next else mappedItems.size
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("تخطي")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            
            // كارت الصنف الحالي "البطل"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("الاسم في الفاتورة:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(currentItem.invoiceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        SuggestionChip(onClick = {}, label = { Text("كمية: ${currentItem.quantity}") }, icon = { Icon(Icons.Default.Inventory, null, modifier = Modifier.size(16.dp)) })
                        Spacer(Modifier.width(8.dp))
                        SuggestionChip(onClick = {}, label = { Text("خصم: ${currentItem.discount}%") }, icon = { Icon(Icons.Default.Percent, null, modifier = Modifier.size(16.dp)) })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // شريط البحث "العائم"
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    scope.launch { searchResults = ItemsDatabase.search(context, it) }
                },
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

            if (searchResults.isEmpty() && searchQuery.length >= 2) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Text("لم يتم العثور على نتائج", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(searchResults) { item ->
                        SearchResultItem(item = item) {
                            scope.launch {
                                val mappingKey = currentItem.invoiceName.trim().lowercase()
                                val sCode = supplierCode.trim().lowercase()
                                // حفظ باسم نظيف تماماً بدون مسافات زائدة
                                AppDatabase.getDatabase(context).smartMappingDao().insertMapping(
                                    SmartMapping(sCode, mappingKey, item.itmCode)
                                )
                                mappedItems[currentIndex] = currentItem.copy(itmCode = item.itmCode, matched = true)
                                searchQuery = ""
                                searchResults = emptyList()
                                val next = mappedItems.indexOfFirst { !it.matched }
                                currentIndex = if (next != -1) next else mappedItems.size
                            }
                        }
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
                    searchQuery = barcode
                    // تفعيل البحث فوراً بمجرد استلام الباركود
                    scope.launch {
                        searchResults = ItemsDatabase.search(context, barcode)
                        // لو لقى نتيجة واحدة بس، يقدر يطابقها فوراً (اختياري)
                        if (searchResults.size == 1) {
                            val item = searchResults[0]
                            val mappingKey = currentItem.invoiceName.trim().lowercase()
                            val sCode = supplierCode.trim().lowercase()
                            AppDatabase.getDatabase(context).smartMappingDao().insertMapping(
                                SmartMapping(sCode, mappingKey, item.itmCode)
                            )
                            mappedItems[currentIndex] = currentItem.copy(itmCode = item.itmCode, matched = true)
                            searchQuery = ""
                            searchResults = emptyList()
                            val next = mappedItems.indexOfFirst { !it.matched }
                            currentIndex = if (next != -1) next else mappedItems.size
                        }
                    }
                },
                onDismiss = { showScanner = false }
            )
        }
    }
}

@Composable
fun SearchResultItem(item: PharmacyItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.nameEn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text("كود: ${item.itmCode} | باركود: ${item.barcode.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
