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

@Composable
fun MappingScreen(
    supplierCode: String,
    ocrItems: List<OcrItem>,
    onDone: (List<OcrItem>) -> Unit
) {
    val context = LocalContext.current
    val mappedItems = remember { ocrItems.toMutableList() }
    var currentIndex by remember { mutableStateOf(-1) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PharmacyItem>>(emptyList()) }

    // تحقق من المطابقات المحفوظة
    LaunchedEffect(Unit) {
        mappedItems.forEachIndexed { i, item ->
            val saved = MappingStorage.getMapping(context, supplierCode, item.invoiceName)
            if (saved != null) {
                mappedItems[i] = item.copy(itmCode = saved, matched = true)
            }
        }
        val firstUnmapped = mappedItems.indexOfFirst { !it.matched }
        currentIndex = if (firstUnmapped != -1) firstUnmapped else mappedItems.size

    }

    if (currentIndex >= mappedItems.size) {
        onDone(mappedItems)
        return
    }

    val currentItem = mappedItems[currentIndex]

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("مطابقة الأصناف", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${currentIndex + 1} من ${mappedItems.size}", fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("اسم الفاتورة:", fontSize = 12.sp)
                Text(currentItem.invoiceName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("كمية: ${currentItem.quantity} | خصم: ${currentItem.discount}% | سعر: ${currentItem.price}")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("ابحث في الأصناف:", fontSize = 14.sp)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                searchResults = ItemsDatabase.search(it)
            },
            label = { Text("اسم الصنف أو الكود") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(searchResults) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            MappingStorage.saveMapping(
                                context, supplierCode,
                                currentItem.invoiceName, item.itmCode
                            )
                            mappedItems[currentIndex] = currentItem.copy(
                                itmCode = item.itmCode, matched = true
                            )
                            searchQuery = ""
                            searchResults = emptyList()
                            val next = mappedItems.indexOfFirst { !it.matched }
                            currentIndex = if (next != -1) next else mappedItems.size
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.itmName, fontWeight = FontWeight.Bold)
                        Text("كود: ${item.itmCode} | باركود: ${item.itmIntCode}",
                            fontSize = 12.sp)
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                val next = mappedItems.indexOfFirst { !it.matched && mappedItems.indexOf(it) > currentIndex }
                currentIndex = if (next != -1) next else mappedItems.size
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("تخطي هذا الصنف") }
    }
}