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
    data class OcrCamera(val supplierCode: String, val invoiceNumber: String) : Screen()
    data class Mapping(val supplierCode: String, val items: List<OcrItem>) : Screen()
    data class Review(val supplierCode: String, val invoiceNumber: String, val items: List<OcrItem>) : Screen()
}

@Composable
fun AppNavigation() {
    var screen by remember { mutableStateOf<Screen>(Screen.Invoice) }
    when (val s = screen) {
        is Screen.Invoice -> InvoiceScreen(
            onScanInvoice = { sup, inv -> screen = Screen.OcrCamera(sup, inv) }
        )
        is Screen.OcrCamera -> InvoiceOcrScreen(
            supplierCode = s.supplierCode,
            invoiceNumber = s.invoiceNumber,
            onItemsReady = { items -> screen = Screen.Mapping(s.supplierCode, items) },
            onDismiss = { screen = Screen.Invoice }
        )
        is Screen.Mapping -> MappingScreen(
            supplierCode = s.supplierCode,
            ocrItems = s.items,
            onDone = { mapped -> screen = Screen.Review(s.supplierCode, "", mapped) }
        )
        is Screen.Review -> ReviewScreen(
            supplierCode = s.supplierCode,
            invoiceNumber = s.invoiceNumber,
            items = s.items,
            onBack = { screen = Screen.Invoice }
        )
    }
}

@Composable
fun InvoiceScreen(onScanInvoice: (String, String) -> Unit = { _, _ -> }) {
    var supplierCode by remember { mutableStateOf("") }
    var invoiceNumber by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<Item>() }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!ItemsDatabase.isLoaded()) ItemsDatabase.load(context)
    }

    if (showDialog) {
        AddItemDialog(
            onDismiss = { showDialog = false },
            onAdd = { item -> items.add(item); showDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("فاتورة جديدة", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = supplierCode,
            onValueChange = { supplierCode = it },
            label = { Text("Supplier Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = invoiceNumber,
            onValueChange = { invoiceNumber = it },
            label = { Text("Invoice Number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (supplierCode.isBlank() || invoiceNumber.isBlank())
                    status = "⚠️ ادخل Supplier Code و Invoice Number أولاً"
                else onScanInvoice(supplierCode, invoiceNumber)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) { Text("📸 صوّر الفاتورة", fontSize = 16.sp) }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("الأصناف (${items.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showDialog = true }) { Text("+ إضافة يدوي") }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item -> ItemRow(item = item, onDelete = { items.remove(item) }) }
        }

        if (status.isNotEmpty())
            Text(status, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))

        Button(
            onClick = {
                if (supplierCode.isBlank() || invoiceNumber.isBlank()) {
                    status = "⚠️ ادخل Supplier Code و Invoice Number"; return@Button
                }
                if (items.isEmpty()) { status = "⚠️ أضف صنف واحد على الأقل"; return@Button }
                loading = true; status = "جاري الإرسال..."
                scope.launch(Dispatchers.IO) {
                    val result = sendInvoice(supplierCode, invoiceNumber, items.toList())
                    withContext(Dispatchers.Main) { status = result; loading = false }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("إرسال للـ PC", fontSize = 16.sp) }
    }
}

@Composable
fun ItemRow(item: Item, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("كود: ${item.itmCode}", fontWeight = FontWeight.Bold)
                Text("كمية: ${item.quantity}  |  سعر: ${item.price}")
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
                    label = { Text("السعر") },
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
                            when {
                                code.isBlank() -> error = "ادخل الكود"
                                qty == null || qty <= 0 -> error = "كمية غير صحيحة"
                                prc == null || prc < 0 -> error = "سعر غير صحيح"
                                else -> onAdd(Item(code.trim(), qty, prc))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("إضافة") }
                }
            }
        }
    }
}