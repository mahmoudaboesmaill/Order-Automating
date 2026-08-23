package com.pharma.link.orderautomating

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pharma.link.orderautomating.ui.theme.OrderAutomatingTheme
import kotlinx.coroutines.*

import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    // The shared URI is kept as Compose state so a share received while the
    // activity is already open reaches the invoice screen immediately.
    private var incomingSharedUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // لا نستخدم التحكم في الوقت هنا لنسمح لـ Compose بالبدء فوراً

        super.onCreate(savedInstanceState)
        incomingSharedUri = extractSharedUri(intent)
        enableEdgeToEdge()
        setContent {
            OrderAutomatingTheme { 
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        incomingSharedUri = incomingSharedUri,
                        onIncomingSharedUriConsumed = { consumeIncomingSharedUri() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingSharedUri = extractSharedUri(intent)
    }

    private fun consumeIncomingSharedUri() {
        incomingSharedUri = null
        // Do not replay the same WhatsApp/Files intent after a rotation or
        // process recreation once the invoice screen has consumed it.
        setIntent(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val accepted = intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_SEND_MULTIPLE ||
            intent.action == Intent.ACTION_VIEW
        if (!accepted) return null

        // Some sharing apps put the URI in ClipData instead of EXTRA_STREAM.
        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                clipData.getItemAt(0).uri?.let { return it }
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.firstOrNull()
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
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
fun AppNavigation(
    incomingSharedUri: Uri? = null,
    onIncomingSharedUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: SharedViewModel = viewModel()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ItemsDatabase.load(context)
        }
    }

    LaunchedEffect(incomingSharedUri, currentBackStackEntry?.destination?.route) {
        if (incomingSharedUri != null &&
            currentBackStackEntry?.destination?.route != Routes.INVOICE
        ) {
            navController.popBackStack(Routes.INVOICE, inclusive = false)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        NavHost(navController = navController, startDestination = Routes.INVOICE) {
            composable(Routes.INVOICE) {
                InvoiceScreenWrapper(
                    navController = navController,
                    sharedViewModel = sharedViewModel,
                    incomingSharedUri = incomingSharedUri,
                    onIncomingSharedUriConsumed = onIncomingSharedUriConsumed
                )
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
                        navController.navigate("review/$supplierCode/$invoiceNumber") {
                            launchSingleTop = true
                        }
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
                val goBackToInvoice: () -> Unit = {
                    navController.popBackStack(Routes.INVOICE, inclusive = false)
                }
                BackHandler(onBack = goBackToInvoice)

                ReviewScreen(
                    initialSupplierCode = supplierCode,
                    initialInvoiceNumber = invoiceNumber,
                    response = ocrResponse,
                    onBack = goBackToInvoice,
                    onOpenSettings = {
                        navController.navigate("server_settings/true") { launchSingleTop = true }
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

@Composable
fun InvoiceScreenWrapper(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    incomingSharedUri: Uri? = null,
    onIncomingSharedUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingSupplierResponse by sharedViewModel.pendingSupplierResponse.collectAsState()
    var tempSupplierCode by rememberSaveable { mutableStateOf("") }

    InvoiceOcrScreen(
        incomingSharedUri = incomingSharedUri,
        onIncomingSharedUriConsumed = onIncomingSharedUriConsumed,
        onResultReady = { result ->
            val detectedNameOrCode = result.supplierName.trim()
            
            scope.launch {
                val selectedCode = ServerManager.getSelectedSupplierCode(context).orEmpty()
                val matchedCode = if (selectedCode.isNotBlank()) {
                    selectedCode
                } else if (detectedNameOrCode.all { it.isDigit() }) {
                    detectedNameOrCode
                } else {
                    sharedViewModel.findSupplierCodeByName(context, ArabicNormalizer.normalize(detectedNameOrCode)) ?: ""
                }
                
                if (matchedCode.isNotBlank()) {
                    val invNum = result.invoiceNumber.ifBlank { "0" }.replace("/", "-")
                    sharedViewModel.setOcrResult(matchedCode, invNum, result)
                    try {
                    navController.navigate("mapping/$matchedCode/$invNum") { launchSingleTop = true }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Navigation error", e)
                    }
                } else {
                    tempSupplierCode = ""
                    sharedViewModel.setPendingSupplierResponse(result)
                }
            }
        },
        onDismiss = { /* شاشة البداية */ },
        onOpenSettings = { navController.navigate("server_settings/false") { launchSingleTop = true } },
        onOpenHistory = { navController.navigate(Routes.HISTORY) { launchSingleTop = true } }
    )

    pendingSupplierResponse?.let { result ->
        AlertDialog(
            onDismissRequest = { sharedViewModel.setPendingSupplierResponse(null) },
            icon = {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "تحديد كود المورد",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "الاسم المستخرج من الفاتورة: ${result.supplierName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "لم يتم التعرف على كود المورد تلقائياً. أدخل الكود المعتمد في نظام E-PLUS للمتابعة:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = tempSupplierCode,
                        onValueChange = { tempSupplierCode = it },
                        label = { Text("كود المورد (مثال: 29، 38، 198)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentResult = result
                        if (tempSupplierCode.isNotBlank()) {
                            val code = tempSupplierCode.trim()
                            val invNum = currentResult.invoiceNumber.ifBlank { "0" }.replace("/", "-")
                            sharedViewModel.setPendingSupplierResponse(null)
                            sharedViewModel.setOcrResult(code, invNum, currentResult)
                            try {
                                navController.navigate("mapping/$code/$invNum") { launchSingleTop = true }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Navigation error", e)
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    enabled = tempSupplierCode.isNotBlank()
                ) { Text("متابعة المطابقة") }
            },
            dismissButton = {
                TextButton(onClick = { sharedViewModel.setPendingSupplierResponse(null) }) { Text("إلغاء") }
            }
        )
    }
}
