package com.pharma.link.orderautomating

import android.os.Bundle
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AppNavigation() }
        }
    }
}

object Routes {
    const val INVOICE = "invoice"
    const val MAPPING = "mapping/{supplierCode}/{invoiceNumber}"
    const val REVIEW = "review/{supplierCode}/{invoiceNumber}"
    const val SERVER_SETTINGS = "server_settings/{fromReview}"
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: SharedViewModel = viewModel()
    
    // تحميل قاعدة البيانات في الخلفية فور فتح التطبيق
    LaunchedEffect(Unit) {
        ItemsDatabase.load(context)
    }

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
            val ocrItems by sharedViewModel.ocrItems.collectAsState()

            ReviewScreen(
                initialSupplierCode = supplierCode,
                initialInvoiceNumber = invoiceNumber,
                items = ocrItems,
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
            val detectedName = result.supplierName.lowercase()
            
            scope.launch {
                val matchedCode = sharedViewModel.findSupplierCodeByName(context, detectedName) ?: ""
                
                if (matchedCode.isNotBlank()) {
                    sharedViewModel.setOcrResult(matchedCode, result.invoiceNumber, result.items)
                    navController.navigate("mapping/$matchedCode/${result.invoiceNumber}")
                } else {
                    tempSupplierCode = ""
                    pendingOcrResult = result
                }
            }
        },
        onDismiss = { /* شاشة البداية */ },
        onOpenSettings = { navController.navigate("server_settings/false") }
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
                    if (tempSupplierCode.isNotBlank()) {
                        val res = pendingOcrResult!!
                        val code = tempSupplierCode
                        pendingOcrResult = null
                        sharedViewModel.setOcrResult(code, res.invoiceNumber, res.items)
                        navController.navigate("mapping/$code/${res.invoiceNumber}")
                    }
                }) { Text("موافق") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOcrResult = null }) { Text("إلغاء") }
            }
        )
    }
}

// الكومبوزابلات الأخرى (InvoiceScreen, ItemRow, AddItemDialog) تبقى كما هي لكنها لم تعد مستخدمة في الـ NavHost الرئيسي 
// حيث استبدلنا منطق الـ when بـ NavHost. إذا كنت تريد إبقاء InvoiceScreen كخيار يدوي، يمكن إضافتها للـ NavHost.
