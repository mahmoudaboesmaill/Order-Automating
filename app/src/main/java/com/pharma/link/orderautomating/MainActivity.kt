package com.pharma.link.orderautomating

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AppNavigation() }
        }
    }
}

sealed class Screen {
    object Invoice : Screen()
    data class ServerSettings(val fromReview: Boolean = false) : Screen()
    data class OcrCamera(val supplierCode: String, val invoiceNumber: String) : Screen()
    data class Mapping(val supplierCode: String, val invoiceNumber: String, val items: List<OcrItem>) : Screen()
    data class Review(val supplierCode: String, val invoiceNumber: String, val items: List<OcrItem>) : Screen()
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Invoice) }
    var lastReviewData by remember { mutableStateOf<Screen.Review?>(null) }
    
    // تحميل قاعدة البيانات في الخلفية فور فتح التطبيق
    LaunchedEffect(Unit) {
        ItemsDatabase.load(context)
    }
    
    // قاموس الموردين المحدث والموسع
    val supplierDictionary = remember {
        mutableMapOf(
            "ابن سينا" to "29", "ibnsinapharma" to "29", "ibn sina" to "29",
            "فارما أوفر سيز" to "38", "pharmaoverseas" to "38", "pharma overseas" to "38",
            "تبارك" to "218", "مالتي ستورز" to "218", "tabarak" to "218", "multi stores" to "218",
            "يونايتد جروب" to "198", "united group" to "198",
            "دريم" to "175", "dream" to "175", "دريم لمستحضرات التجميل" to "175", "دريم للمستلزمات" to "175"
        )
    }

    // حالة مؤقتة للتعامل مع الموردين الجدد لمنع الـ Crash
    var pendingOcrResult by remember { mutableStateOf<OcrResponse?>(null) }
    var tempSupplierCode by remember { mutableStateOf("") } // نقلنا التعريف هنا (خارج الشرط)

    when (val s = screen) {
        is Screen.Invoice -> {
            InvoiceOcrScreen(
                onResultReady = { result ->
                    val detectedName = result.supplierName.lowercase()
                    val matchedCode = supplierDictionary.entries.find { 
                        detectedName.contains(it.key.lowercase()) 
                    }?.value ?: ""
                    
                    if (matchedCode.isNotBlank()) {
                        screen = Screen.Mapping(matchedCode, result.invoiceNumber, result.items)
                    } else {
                        tempSupplierCode = "" // تصفير الكود المؤقت
                        pendingOcrResult = result
                    }
                },
                onDismiss = { /* شاشة البداية */ }
            )

            // عرض نافذة طلب الكود فوق شاشة الكاميرا لو المورد مجهول
            pendingOcrResult?.let { result ->
                AlertDialog(
                    onDismissRequest = { pendingOcrResult = null },
                    title = { Text("مورد غير معروف") },
                    text = {
                        Column {
                            Text("الاسم المكتشف: ${result.supplierName}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tempSupplierCode,
                                onValueChange = { tempSupplierCode = it },
                                label = { Text("أدخل كود المورد") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (tempSupplierCode.isNotBlank()) {
                                val res = pendingOcrResult!!
                                val code = tempSupplierCode
                                pendingOcrResult = null
                                screen = Screen.Mapping(code, res.invoiceNumber, res.items)
                            }
                        }) { Text("موافق") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingOcrResult = null }) { Text("إلغاء") }
                    }
                )
            }
        }
        
        is Screen.Mapping -> {
            MappingScreen(
                supplierCode = s.supplierCode,
                ocrItems = s.items,
                onBack = { screen = Screen.Invoice },
                onDone = { mapped -> 
                    screen = Screen.Review(s.supplierCode, s.invoiceNumber, mapped)
                }
            )
        }

        is Screen.ServerSettings -> ServerManagementScreen(
            onBack = { 
                screen = if (s.fromReview && lastReviewData != null) lastReviewData!! else Screen.Invoice 
            }
        )

        is Screen.Review -> ReviewScreen(
            initialSupplierCode = s.supplierCode,
            initialInvoiceNumber = s.invoiceNumber,
            items = s.items,
            onBack = { 
                // تصفير البيانات للبدء في فاتورة جديدة تماماً
                screen = Screen.Invoice 
            },
            onOpenSettings = { 
                lastReviewData = s
                screen = Screen.ServerSettings(fromReview = true) 
            },
            onUpdateHeader = { sup, inv ->
                screen = s.copy(supplierCode = sup, invoiceNumber = inv)
            }
        )
        else -> {}
    }
}

@Composable
fun InvoiceScreen(
    initialSupplierCode: String = "",
    initialInvoiceNumber: String = "",
    onScanInvoice: (String, String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    onUpdateInputs: (String, String) -> Unit = { _, _ -> }
) {
    var supplierCode by remember { mutableStateOf(initialSupplierCode) }
    var invoiceNumber by remember { mutableStateOf(initialInvoiceNumber) }
    val items = remember { mutableStateListOf<Item>() }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ItemsDatabase.load(context)
    }

    if (showDialog) {
        AddItemDialog(
            onDismiss = { showDialog = false },
            onAdd = { item -> items.add(item); showDialog = false }
        )
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("OrderAutomating V2", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Button(
                    onClick = {
                        if (supplierCode.isBlank() || invoiceNumber.isBlank()) {
                            status = "⚠️ ادخل البيانات الأساسية أولاً"; return@Button
                        }
                        if (items.isEmpty()) { status = "⚠️ أضف صنف واحد على الأقل"; return@Button }
                        loading = true; status = "جاري الإرسال..."
                        scope.launch(Dispatchers.IO) {
                            val result = sendInvoice(context, supplierCode, invoiceNumber, items.toList())
                            withContext(Dispatchers.Main) { status = result; loading = false }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    else {
                        Icon(Icons.Default.Send, null)
                        Spacer(Modifier.width(8.dp))
                        Text("إرسال للكمبيوتر", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("فاتورة جديدة", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("أدخل بيانات المورد وابدأ الأتمتة", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = supplierCode,
                        onValueChange = { 
                            supplierCode = it
                            onUpdateInputs(it, invoiceNumber)
                        },
                        label = { Text("كود المورد") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        shape = MaterialTheme.shapes.medium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = invoiceNumber,
                        onValueChange = { 
                            invoiceNumber = it
                            onUpdateInputs(supplierCode, it)
                        },
                        label = { Text("رقم الفاتورة") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Receipt, null) },
                        shape = MaterialTheme.shapes.medium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (supplierCode.isBlank() || invoiceNumber.isBlank())
                        status = "⚠️ ادخل كود المورد ورقم الفاتورة"
                    else onScanInvoice(supplierCode, invoiceNumber)
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(8.dp))
                Text("تصوير الفاتورة بـ AI", fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("الأصناف المختارة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDialog = true }) {
                    Icon(Icons.Default.Add, null)
                    Text("إضافة يدوي")
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("لا يوجد أصناف حالياً", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items) { item -> ItemRow(item = item, onDelete = { items.remove(item) }) }
                }
            }

            if (status.isNotEmpty()) {
                Card(
                    modifier = Modifier.padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (status.startsWith("✅")) Color(0xFFE8F5E9) else Color(0xFFFDECEA)
                    )
                ) {
                    Text(status, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ItemRow(item: Item, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("كود: ${item.itmCode}", fontWeight = FontWeight.Bold)
                Text("كمية: ${item.quantity}  |  سعر شراء: ${item.price}  |  سعر بيع: ${item.salePrice}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "حذف")
            }
        }
    }
}

@Composable
fun AddItemDialog(onDismiss: () -> Unit, onAdd: (Item) -> Unit) {
    var code by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { barcode -> code = barcode; showScanner = false },
            onDismiss = { showScanner = false }
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("إضافة صنف", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Item Code / Barcode") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { showScanner = true }) { Text("Scan") }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it },
                    label = { Text("الكمية") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = price, onValueChange = { price = it },
                    label = { Text("سعر الشراء") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                var salePrice by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = salePrice, onValueChange = { salePrice = it },
                    label = { Text("سعر البيع") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("إلغاء")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val qty = quantity.toIntOrNull()
                            val prc = price.toDoubleOrNull()
                            val sPrc = salePrice.toDoubleOrNull() ?: 0.0
                            when {
                                code.isBlank() -> error = "ادخل الكود"
                                qty == null || qty <= 0 -> error = "كمية غير صحيحة"
                                prc == null || prc < 0 -> error = "سعر غير صحيح"
                                else -> onAdd(Item(code.trim(), qty, prc, sPrc))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("إضافة") }
                }
            }
        }
    }
}