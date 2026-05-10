package com.pharma.link.orderautomating

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable
fun ReviewScreen(
    supplierCode: String,
    invoiceNumber: String,
    items: List<OcrItem>,
    onBack: () -> Unit
) {
    val editableItems = remember { items.toMutableStateList() }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("مراجعة الفاتورة", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("المورد: $supplierCode | ${editableItems.size} صنف",
            fontSize = 14.sp)

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(editableItems) { index, item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.invoiceName,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("كود: ${item.itmCode}",
                                    fontSize = 11.sp,
                                    color = if (item.itmCode.isEmpty())
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { editableItems.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = item.quantity.toString(),
                                onValueChange = { v ->
                                    v.toDoubleOrNull()?.let {
                                        editableItems[index] = item.copy(quantity = it)
                                    }
                                },
                                label = { Text("كمية") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = item.discount.toString(),
                                onValueChange = { v ->
                                    v.toDoubleOrNull()?.let {
                                        editableItems[index] = item.copy(discount = it)
                                    }
                                },
                                label = { Text("خصم%") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = item.price.toString(),
                                onValueChange = { v ->
                                    v.toDoubleOrNull()?.let {
                                        editableItems[index] = item.copy(price = it)
                                    }
                                },
                                label = { Text("سعر") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        if (status.isNotEmpty()) {
            Text(status, modifier = Modifier.padding(vertical = 8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) { Text("رجوع") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    val readyItems = editableItems.filter { it.itmCode.isNotEmpty() }
                    if (readyItems.isEmpty()) {
                        status = "⚠️ مفيش أصناف مطابقة للإرسال"
                        return@Button
                    }
                    loading = true
                    status = "جاري الإرسال..."
                    scope.launch(Dispatchers.IO) {
                        val invoiceItems = readyItems.map {
                            Item(
                                itmCode = it.itmCode,
                                quantity = it.quantity.toInt(),
                                price = it.price,
                                discount = it.discount  // ← أضف ده
                            )
                        }
                        val result = sendInvoice(supplierCode, invoiceNumber, invoiceItems)
                        withContext(Dispatchers.Main) {
                            loading = false
                            status = result
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("📤 إرسال للـ PC", fontSize = 16.sp) }
        }
    }
}