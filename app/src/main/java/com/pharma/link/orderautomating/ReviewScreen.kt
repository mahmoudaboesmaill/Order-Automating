package com.pharma.link.orderautomating

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

@Composable
fun ReviewScreen(
    initialSupplierCode: String,
    initialInvoiceNumber: String,
    items: List<OcrItem>,
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
    val itemToRemapIndex by viewModel.itemToRemapIndex.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.init(initialSupplierCode, initialInvoiceNumber, items)
    }

    if (itemToRemapIndex != -1) {
        val currentItem = editableItems[itemToRemapIndex]
        MappingDialog(
            invoiceName = currentItem.invoiceName,
            onDismiss = { viewModel.setItemToRemap(-1) },
            onSelect = { newItem ->
                viewModel.remapItem(context, itemToRemapIndex, newItem)
            }
        )
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("مراجعة الفاتورة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallHeaderField(value = supplierCode, label = "مورد", modifier = Modifier.weight(1f)) { 
                            viewModel.updateSupplierCode(it)
                        }
                        SmallHeaderField(value = invoiceNumber, label = "فاتورة", modifier = Modifier.weight(1f)) { 
                            viewModel.updateInvoiceNumber(it)
                        }
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "تغيير السيرفر", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) { 
                        Text(if (status.startsWith("✅")) "فاتورة جديدة" else "رجوع") 
                    }

                    Button(
                        onClick = { viewModel.sendInvoice(context) },
                        enabled = !loading,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        else {
                            Icon(Icons.Default.Upload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("إرسال الآن")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (status.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (status.startsWith("✅")) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Text(status, modifier = Modifier.padding(12.dp), color = if (status.startsWith("✅")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(editableItems) { index, item ->
                    ReviewItemCard(
                        item = item,
                        onDelete = { viewModel.deleteItem(index) },
                        onRemap = { viewModel.setItemToRemap(index) },
                        onUpdate = { updated -> viewModel.updateItem(index, updated) }
                    )
                }
            }
        }
    }
}

@Composable
fun ReviewItemCard(item: OcrItem, onDelete: () -> Unit, onRemap: () -> Unit, onUpdate: (OcrItem) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large, // زوايا دائرية أكبر
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.invoiceName, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    AssistChip(
                        onClick = onRemap,
                        label = { Text("كود: ${item.itmCode.ifEmpty { "غير مطابَق" }}", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (item.itmCode.isEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                IconButton(onClick = onDelete) { 
                    Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) 
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // تنظيم الحقول في شبكة (Grid) أنيقة
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModernField(value = item.quantity.toString(), label = "الكمية", icon = Icons.Default.Inventory, modifier = Modifier.weight(1f)) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(quantity = v)) }
                }
                ModernField(value = item.bonus.toString(), label = "بونص", icon = Icons.Default.CardGiftcard, modifier = Modifier.weight(1f)) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(bonus = v)) }
                }
                ModernField(value = item.taxes.toString(), label = "ضريبة", icon = Icons.Default.AccountBalance, modifier = Modifier.weight(1f)) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(taxes = v)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernField(value = item.salePrice.toString(), label = "س. بيع", icon = Icons.Default.Storefront, modifier = Modifier.weight(1f)) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(salePrice = v)) }
                }
                ModernField(value = item.price.toString(), label = "س. شراء", icon = Icons.Default.Payments, modifier = Modifier.weight(1f)) {
                    it.toDoubleOrNull()?.let { v -> onUpdate(item.copy(price = v)) }
                }
            }
        }
    }
}

@Composable
fun ModernField(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun SmallHeaderField(value: String, label: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = MaterialTheme.shapes.small
    )
}

@Composable
fun SmallEditField(value: String, label: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        shape = MaterialTheme.shapes.small
    )
}

@Composable
fun MappingDialog(
    invoiceName: String,
    onDismiss: () -> Unit,
    onSelect: (PharmacyItem) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PharmacyItem>>(emptyList()) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("تعديل مطابقة الصنف", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(invoiceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { showAddItemDialog = true }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "إضافة صنف جديد", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        scope.launch { results = ItemsDatabase.search(context, it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ابحث بالاسم أو الكود...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = MaterialTheme.shapes.medium
                )

                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { item ->
                        SearchResultItem(item = item, onEditBarcode = { /* No-op in ReviewScreen */ }) { 
                            onSelect(item) 
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء") }
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
                    results = ItemsDatabase.search(context, query) // تحديث البحث
                }
            }
        )
    }
}
