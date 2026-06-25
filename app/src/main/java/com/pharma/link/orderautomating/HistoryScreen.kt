package com.pharma.link.orderautomating

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<InvoiceRecord>>(emptyList()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = AppDatabase.getDatabase(context).invoiceRecordDao().getAll()
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("مسح كل السجل؟") },
            text = { Text("هيتمسح كل تاريخ الفواتير المرسلة.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        // حذف كل السجلات
                        MainScope().launch {
                            AppDatabase.getDatabase(context).invoiceRecordDao().deleteAll()
                            records = emptyList()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("نعم، امسح") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text("إلغاء") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("سجل الفواتير", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ReceiptLong, null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))
                    Text("لا يوجد فواتير مرسلة بعد",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records) { record ->
                    HistoryItemCard(record) {
                        MainScope().launch {
                            AppDatabase.getDatabase(context).invoiceRecordDao().delete(record.id)
                            records = AppDatabase.getDatabase(context).invoiceRecordDao().getAll()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(record: InvoiceRecord, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy — hh:mm a", Locale("ar")) }
    val isSuccess = record.status == "success"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSuccess) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                null,
                tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("مورد: ${record.supplierCode} | فاتورة: ${record.invoiceNumber}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("${record.itemsCount} صنف | إجمالي: ${String.format("%.2f", record.totalPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Text(dateFormat.format(Date(record.sentAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
