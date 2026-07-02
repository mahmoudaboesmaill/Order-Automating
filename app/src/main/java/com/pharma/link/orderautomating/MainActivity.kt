package com.pharma.link.orderautomating

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pharma.link.orderautomating.ui.theme.OrderAutomatingTheme
import kotlinx.coroutines.*
import java.io.File

import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // لا نستخدم التحكم في الوقت هنا لنسمح لـ Compose بالبدء فوراً

        // حذف ملف الـ mapping القديم لو موجود
        val oldFile = File(filesDir, "supplier_mapping.json")
        if (oldFile.exists()) oldFile.delete()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrderAutomatingTheme { 
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation() 
                }
            }
        }
    }
}

object Routes {
    const val INVOICE = "invoice"
    const val MAPPING = "mapping/{supplierCode}/{invoiceNumber}"
    const val REVIEW = "review/{supplierCode}/{invoiceNumber}"
    const val SERVER_SETTINGS = "server_settings/{fromReview}"
    const val HISTORY = "history"
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: SharedViewModel = viewModel()
    
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // تحميل البيانات في الخلفية بدون تعطيل الـ Main Thread
        withContext(Dispatchers.IO) {
            ItemsDatabase.load(context)
        }
        delay(1500) // تقليل الوقت قليلاً لسرعة الاستجابة
        showSplash = false
    }

    if (showSplash) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.splash_icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    } else {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        NavHost(navController = navController, startDestination = Routes.INVOICE) {
            composable(Routes.INVOICE) {
                InvoiceScreenWrapper(navController, sharedViewModel)
            }
            
            composable(
                route = Routes.MAPPING,
                arguments = listOf(
                    navArgument("supplierCode") { type = NavType.StringType },
                    navArgument("invoiceNumber") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val supplierCode = backStackEntry.arguments?.getString("supplierCode") ?: ""
                val invoiceNumber = backStackEntry.arguments?.getString("invoiceNumber") ?: ""
                val ocrItems by sharedViewModel.ocrItems.collectAsState()
                
                MappingScreen(
                    supplierCode = supplierCode,
                    ocrItems = ocrItems,
                    onBack = { navController.popBackStack() },
                    onDone = { mapped ->
                        sharedViewModel.updateMappedItems(mapped)
                        navController.navigate("review/$supplierCode/$invoiceNumber")
                    }
                )
            }

            composable(
                route = Routes.REVIEW,
                arguments = listOf(
                    navArgument("supplierCode") { type = NavType.StringType },
                    navArgument("invoiceNumber") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val supplierCode = backStackEntry.arguments?.getString("supplierCode") ?: ""
                val invoiceNumber = backStackEntry.arguments?.getString("invoiceNumber") ?: ""
                val ocrResponse by sharedViewModel.ocrResponse.collectAsState()

                ReviewScreen(
                    initialSupplierCode = supplierCode,
                    initialInvoiceNumber = invoiceNumber,
                    response = ocrResponse,
                    onBack = {
                        navController.popBackStack(Routes.INVOICE, inclusive = false)
                    },
                    onOpenSettings = {
                        navController.navigate("server_settings/true")
                    }
                )
            }

            composable(
                route = Routes.SERVER_SETTINGS,
                arguments = listOf(navArgument("fromReview") { type = NavType.BoolType })
            ) {
                ServerManagementScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
}

@Composable
fun InvoiceScreenWrapper(navController: NavController, sharedViewModel: SharedViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingOcrResult by remember { mutableStateOf<OcrResponse?>(null) }
    var tempSupplierCode by remember { mutableStateOf("") }

    InvoiceOcrScreen(
        onResultReady = { result ->
            val detectedNameOrCode = result.supplierName.trim()
            
            scope.launch {
                // إذا كان الاسم المكتشف عبارة عن أرقام، نعتبره الكود مباشرة
                val matchedCode = if (detectedNameOrCode.all { it.isDigit() }) {
                    detectedNameOrCode
                } else {
                    sharedViewModel.findSupplierCodeByName(context, ArabicNormalizer.normalize(detectedNameOrCode)) ?: ""
                }
                
                if (matchedCode.isNotBlank()) {
                    val invNum = result.invoiceNumber.ifBlank { "0" }.replace("/", "-")
                    sharedViewModel.setOcrResult(matchedCode, invNum, result)
                    try {
                        navController.navigate("mapping/$matchedCode/$invNum")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Navigation error", e)
                    }
                } else {
                    tempSupplierCode = ""
                    pendingOcrResult = result
                }
            }
        },
        onDismiss = { /* شاشة البداية */ },
        onOpenSettings = { navController.navigate("server_settings/false") },
        onOpenHistory = { navController.navigate(Routes.HISTORY) }
    )

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
                    val currentResult = pendingOcrResult
                    if (tempSupplierCode.isNotBlank() && currentResult != null) {
                        val code = tempSupplierCode.trim()
                        val invNum = currentResult.invoiceNumber.ifBlank { "0" }.replace("/", "-")
                        
                        pendingOcrResult = null
                        sharedViewModel.setOcrResult(code, invNum, currentResult)
                        
                        // استخدام try-catch بسيط لحماية التنقل
                        try {
                            navController.navigate("mapping/$code/$invNum")
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Navigation error", e)
                        }
                    }
                }) { Text("موافق") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOcrResult = null }) { Text("إلغاء") }
            }
        )
    }
}

