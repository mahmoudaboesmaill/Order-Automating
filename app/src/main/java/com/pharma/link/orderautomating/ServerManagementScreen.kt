package com.pharma.link.orderautomating

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(ServerManager.getServers(context)) }
    var selectedServerId by remember { mutableStateOf(ServerManager.getSelectedServer(context)?.id) }
    var showAddDialog by remember { mutableStateOf(false) }

    var suppliers by remember { mutableStateOf<List<SupplierDictionary>>(emptyList()) }
    var showAddSupplierDialog by remember { mutableStateOf(false) }

    val supplierDao = remember { AppDatabase.getDatabase(context).supplierDictionaryDao() }

    LaunchedEffect(Unit) {
        suppliers = supplierDao.getAll()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SectionHeader("إعدادات السيرفرات", onAdd = { showAddDialog = true })
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    servers.forEach { server ->
                        ServerItem(
                            server = server,
                            isSelected = server.id == selectedServerId,
                            onSelect = {
                                ServerManager.setSelectedServer(context, server.id)
                                selectedServerId = server.id
                            },
                            onDelete = {
                                ServerManager.deleteServer(context, server.id)
                                servers = ServerManager.getServers(context)
                                if (selectedServerId == server.id) selectedServerId = null
                            }
                        )
                    }
                }
            }

            item {
                SectionHeader("قاموس الموردين", onAdd = { showAddSupplierDialog = true })
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suppliers.forEach { supplier ->
                        SupplierItem(
                            supplier = supplier,
                            onDelete = {
                                scope.launch {
                                    supplierDao.delete(supplier)
                                    suppliers = supplierDao.getAll()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, ip ->
                ServerManager.addServer(context, name, ip)
                servers = ServerManager.getServers(context)
                showAddDialog = false
            }
        )
    }

    if (showAddSupplierDialog) {
        AddSupplierDialog(
            onDismiss = { showAddSupplierDialog = false },
            onAdd = { ar, en, code ->
                scope.launch {
                    supplierDao.insert(SupplierDictionary(arabicName = ar, englishName = en, supplierCode = code))
                    suppliers = supplierDao.getAll()
                    showAddSupplierDialog = false
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun SupplierItem(supplier: SupplierDictionary, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(supplier.arabicName, fontWeight = FontWeight.Bold)
                Text("Code: ${supplier.supplierCode} | ${supplier.englishName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AddSupplierDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var ar by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("إضافة مورد للقاموس", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = ar, onValueChange = { ar = it }, label = { Text("الاسم بالعربي") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = en, onValueChange = { en = it }, label = { Text("الاسم بالإنجليزية") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("كود المورد") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء") }
                    Button(onClick = { if (ar.isNotBlank() && code.isNotBlank()) onAdd(ar, en, code) }) { Text("حفظ") }
                }
            }
        }
    }
}

@Composable
fun ServerItem(server: ServerConfig, isSelected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("إضافة سيرفر جديد", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم السيرفر (مثلاً: المحل)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("عنوان الـ IP (مثلاً: 192.168.1.5)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء") }
                    Button(onClick = { if (name.isNotBlank() && ip.isNotBlank()) onAdd(name, ip) }) { Text("إضافة") }
                }
            }
        }
    }
}
