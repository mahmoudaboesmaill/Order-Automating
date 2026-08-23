package com.pharma.link.orderautomating

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var ocrProvider by remember { mutableStateOf(ServerManager.getOcrProvider(context)) }
    var ocrProviderMenuExpanded by remember { mutableStateOf(false) }

    var suppliers by remember { mutableStateOf<List<SupplierDictionary>>(emptyList()) }
    var showAddSupplierDialog by remember { mutableStateOf(false) }

    val supplierDao = remember { AppDatabase.getDatabase(context).supplierDictionaryDao() }

    var itemsCount by remember { mutableIntStateOf(0) }
    var isReloadingItems by remember { mutableStateOf(false) }
    val importProgress by ItemsDatabase.importProgress.collectAsState()

    val smartMappingDao = remember { AppDatabase.getDatabase(context).smartMappingDao() }
    val ocrCacheDao = remember { AppDatabase.getDatabase(context).ocrCorrectionCacheDao() }

    // لاونشر لاختيار ملف CSV مخصص للأصناف
    val csvPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isReloadingItems = true
                itemsCount = ItemsDatabase.importFromUri(context, uri)
                isReloadingItems = false
                android.widget.Toast.makeText(context, "تم استيراد $itemsCount صنف بنجاح", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // لاونشر لاختيار ملف JSON لاستيراد الربط الذكي
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val jsonStr = stream.bufferedReader().readText()
                        val jsonObj = org.json.JSONObject(jsonStr)

                        // استيراد smart_mapping
                        if (jsonObj.has("smart_mappings")) {
                            val arr = jsonObj.getJSONArray("smart_mappings")
                            val mappings = mutableListOf<SmartMapping>()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                mappings.add(SmartMapping(
                                    supplierCode = o.getString("supplierCode"),
                                    invoiceName = o.getString("invoiceName"),
                                    itmCode = o.getString("itmCode")
                                ))
                            }
                            smartMappingDao.insertAll(mappings)
                        }

                        // استيراد ocr_corrections
                        if (jsonObj.has("ocr_corrections")) {
                            val arr = jsonObj.getJSONArray("ocr_corrections")
                            val caches = mutableListOf<OcrCorrectionCache>()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                caches.add(OcrCorrectionCache(
                                    supplierCode = o.getString("supplierCode"),
                                    ocrRawText = o.getString("ocrRawText"),
                                    correctedItmCode = o.getString("correctedItmCode"),
                                    correctedName = o.optString("correctedName", ""),
                                    usageCount = o.optInt("usageCount", 1),
                                    lastUsed = o.optLong("lastUsed", System.currentTimeMillis())
                                ))
                            }
                            ocrCacheDao.insertAll(caches)
                        }

                        // استيراد supplier_dictionary
                        if (jsonObj.has("suppliers")) {
                            val arr = jsonObj.getJSONArray("suppliers")
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                supplierDao.insert(SupplierDictionary(
                                    arabicName = o.getString("arabicName"),
                                    englishName = o.getString("englishName"),
                                    supplierCode = o.getString("supplierCode")
                                ))
                            }
                            suppliers = supplierDao.getAll()
                        }

                        android.widget.Toast.makeText(context, "تم استعادة بيانات الربط الذكي بنجاح", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "خطأ في استيراد الملف: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        suppliers = supplierDao.getAll()
        itemsCount = ItemsDatabase.count(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات وإدارة البيانات", fontWeight = FontWeight.Bold) },
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
            // محرك القراءة قابل للتغيير من التطبيق، ويُرسل مع كل فاتورة بدون إعادة تشغيل السيرفر.
            item {
                Text(
                    text = "محرك قراءة الفواتير",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("اختيار مزود الـ OCR", fontWeight = FontWeight.Bold)
                                Text(
                                    text = when (ocrProvider) {
                                        "gemini" -> "Gemini فقط"
                                        "mistral" -> "Mistral فقط"
                                        else -> "تلقائي: Gemini ثم Mistral عند الحاجة"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Box {
                                OutlinedButton(onClick = { ocrProviderMenuExpanded = true }) {
                                    Text(
                                        when (ocrProvider) {
                                            "gemini" -> "Gemini"
                                            "mistral" -> "Mistral"
                                            else -> "تلقائي"
                                        }
                                    )
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(
                                    expanded = ocrProviderMenuExpanded,
                                    onDismissRequest = { ocrProviderMenuExpanded = false }
                                ) {
                                    listOf(
                                        "auto" to "تلقائي (موصى به)",
                                        "gemini" to "Gemini فقط",
                                        "mistral" to "Mistral فقط"
                                    ).forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                ocrProvider = value
                                                ServerManager.saveOcrProvider(context, value)
                                                ocrProviderMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "التغيير يُحفظ على الهاتف ويُطبق على الفاتورة التالية.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 1. قسم إدارة قاعدة بيانات الأصناف
            item {
                Text(
                    text = "قاعدة بيانات الأصناف",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory2, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "إجمالي الأصناف المسجلة: $itemsCount صنف",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        if (isReloadingItems || importProgress < 1f) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { importProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "جاري استيراد وتحديث الأصناف... ${(importProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // زر إعادة التحميل من النظام
                            Button(
                                onClick = {
                                    scope.launch {
                                        isReloadingItems = true
                                        itemsCount = ItemsDatabase.forceReload(context)
                                        isReloadingItems = false
                                        android.widget.Toast.makeText(context, "تم تحديث الأصناف بنجاح ($itemsCount صنف)", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isReloadingItems && importProgress >= 1f,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تحديث الأصناف")
                            }

                            // زر استيراد ملف مخصص
                            OutlinedButton(
                                onClick = { csvPickerLauncher.launch("*/*") },
                                enabled = !isReloadingItems && importProgress >= 1f,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("استيراد CSV")
                            }
                        }
                    }
                }
            }

            // 2. قسم النسخ الاحتياطي للربط الذكي
            item {
                Text(
                    text = "النسخ الاحتياطي للربط الذكي",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "احفظ الربط الذكي وتصحيحات الـ OCR لنقلها لهاتف آخر أو استعادتها.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // تصدير الربط
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val mappings = smartMappingDao.getAll()
                                            val ocrCaches = ocrCacheDao.getAll()
                                            val allSuppliers = supplierDao.getAll()

                                            val root = org.json.JSONObject()
                                            val mapArr = org.json.JSONArray()
                                            mappings.forEach { m ->
                                                mapArr.put(org.json.JSONObject().apply {
                                                    put("supplierCode", m.supplierCode)
                                                    put("invoiceName", m.invoiceName)
                                                    put("itmCode", m.itmCode)
                                                })
                                            }
                                            root.put("smart_mappings", mapArr)

                                            val ocrArr = org.json.JSONArray()
                                            ocrCaches.forEach { c ->
                                                ocrArr.put(org.json.JSONObject().apply {
                                                    put("supplierCode", c.supplierCode)
                                                    put("ocrRawText", c.ocrRawText)
                                                    put("correctedItmCode", c.correctedItmCode)
                                                    put("correctedName", c.correctedName)
                                                    put("usageCount", c.usageCount)
                                                    put("lastUsed", c.lastUsed)
                                                })
                                            }
                                            root.put("ocr_corrections", ocrArr)

                                            val supArr = org.json.JSONArray()
                                            allSuppliers.forEach { s ->
                                                supArr.put(org.json.JSONObject().apply {
                                                    put("arabicName", s.arabicName)
                                                    put("englishName", s.englishName)
                                                    put("supplierCode", s.supplierCode)
                                                })
                                            }
                                            root.put("suppliers", supArr)

                                            // مشاركة أو حفظ الملف
                                            val backupFile = java.io.File(context.cacheDir, "smart_mapping_backup.json")
                                            backupFile.writeText(root.toString(2), Charsets.UTF_8)

                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                backupFile
                                            )

                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, "مشاركة النسخة الاحتياطية"))

                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "خطأ في التصدير: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تصدير الربط")
                            }

                            // استيراد الربط
                            OutlinedButton(
                                onClick = { jsonPickerLauncher.launch("*/*") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("استيراد الربط")
                            }
                        }
                    }
                }
            }

            // 3. قسم إعدادات السيرفرات
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

            // 4. قسم قاموس الموردين
            item {
                SectionHeader("قاموس الموردين", onAdd = { showAddSupplierDialog = true })
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // كل كود مورد يظهر مرة واحدة، وتُعرض تحته كل الأسماء
                    // البديلة المرتبطة به بدلاً من تكرار نفس المورد عدة مرات.
                    suppliers
                        .groupBy { it.supplierCode }
                        .toSortedMap()
                        .forEach { (_, aliases) ->
                            SupplierGroupItem(
                                aliases = aliases,
                                onDeleteAlias = { alias ->
                                    scope.launch {
                                        supplierDao.delete(alias)
                                        suppliers = supplierDao.getAll()
                                    }
                                }
                            )
                        }
                    if (suppliers.isEmpty()) {
                        Text(
                            "لا يوجد موردون في القاموس حالياً",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, ip, token ->
                ServerManager.addServer(context, name, ip, token = token)
                servers = ServerManager.getServers(context)
                showAddDialog = false
            }
        )
    }

    if (showAddSupplierDialog) {
        AddSupplierDialog(
            existingCodes = suppliers.map { it.supplierCode }.distinct().sorted(),
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
fun SupplierGroupItem(
    aliases: List<SupplierDictionary>,
    onDeleteAlias: (SupplierDictionary) -> Unit
) {
    var expanded by remember(aliases) { mutableStateOf(false) }
    val code = aliases.firstOrNull()?.supplierCode.orEmpty()
    val displayNames = aliases.map { alias ->
        listOf(alias.arabicName.trim(), alias.englishName.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
    }.filter { it.isNotBlank() }.distinct()
    val primaryName = aliases
        .firstOrNull { it.arabicName.any { character -> character in '\u0600'..'\u06FF' } }
        ?.arabicName
        ?.takeIf { it.isNotBlank() }
        ?: displayNames.firstOrNull()
        ?: "مورد $code"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(primaryName, fontWeight = FontWeight.Bold)
                    Text("كود المورد: $code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "إخفاء" else "الأسماء (${aliases.size})")
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("الأسماء والبدائل المرتبطة بهذا الكود", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                displayNames.forEach { name ->
                    Text("• $name", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                aliases.forEach { alias ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "حذف هذا الاسم: ${alias.arabicName.ifBlank { alias.englishName }}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        IconButton(onClick = { onDeleteAlias(alias) }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AddSupplierDialog(
    existingCodes: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var ar by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeMenuExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("إضافة مورد للقاموس", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = ar, onValueChange = { ar = it }, label = { Text("الاسم بالعربي") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = en, onValueChange = { en = it }, label = { Text("الاسم بالإنجليزية") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = codeMenuExpanded,
                    onExpandedChange = { codeMenuExpanded = !codeMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("كود المورد") },
                        placeholder = { Text("اختر كوداً موجوداً أو اكتب كوداً جديداً") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = codeMenuExpanded,
                        onDismissRequest = { codeMenuExpanded = false }
                    ) {
                        existingCodes.forEach { existingCode ->
                            DropdownMenuItem(
                                text = { Text("استخدام الكود $existingCode") },
                                onClick = {
                                    code = existingCode
                                    codeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
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
fun AddServerDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("إضافة سيرفر جديد", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم السيرفر (مثلاً: المحل)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("عنوان الـ IP (مثلاً: 192.168.1.5)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("رمز الأمان (اختياري)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("إلغاء") }
                Button(onClick = { if (name.isNotBlank() && ip.isNotBlank()) onAdd(name, ip, token) }) { Text("إضافة") }
            }
            }
        }
    }
}
