package com.example

import android.app.AppOpsManager
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.AutomationEntity
import com.example.data.SettingsEntity
import com.example.viewmodel.CustomizerViewModel
import com.example.viewmodel.CustomizerViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var billingManager: BillingManager
    private lateinit var viewModel: CustomizerViewModel

    // Activity launcher for Device Admin grant request
    private val adminResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.forceCheckBilling()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize DB + Billing Services
        val db = AppDatabase.getInstance(applicationContext)
        val lifecycleScope = CoroutineScope(Dispatchers.Main)
        billingManager = BillingManager(this, lifecycleScope)

        // 2. Initialize ViewModel
        val factory = CustomizerViewModelFactory(db.settingsDao, billingManager)
        viewModel = ViewModelProvider(this, factory)[CustomizerViewModel::class.java]

        // 3. Register live automation receivers
        try {
            val autoFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            registerReceiver(AutomationReceiver(), autoFilter)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Load UI from XML Layout containing ComposeView (Interoperability Design)
        setContentView(R.layout.activity_main)
        
        val composeView = findViewById<ComposeView>(R.id.compose_view)
        composeView.setContent {
            CyberAppTheme {
                MainDashboardScreen(
                    viewModel = viewModel,
                    billingManager = billingManager,
                    onGrantAdmin = { triggerAdminRequest() },
                    onStartWellbeingCheck = { checkAndRequestStatsPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.queryDeviceWellbeingStats(this)
        viewModel.forceCheckBilling()
        
        // Synchronize reactive values in DB Settings
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val settings = db.settingsDao.getSettings()
            if (settings != null) {
                // Keep service operations up-to-date
                viewModel.syncBackgroundServices(this@MainActivity, settings)
            }
        }
    }

    private fun triggerAdminRequest() {
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to turn off the screen instantly using tap gestures.")
        }
        adminResultLauncher.launch(intent)
    }

    private fun checkAndRequestStatsPermission() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }

        if (mode != AppOpsManager.MODE_ALLOWED) {
            // Toast helper and redirect
            Toast.makeText(this, "Please enable 'Aura Customizer' in Usage Access Settings", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } else {
            viewModel.queryDeviceWellbeingStats(this)
        }
    }
}

// ------------------- Cyber Theme Colors -------------------
val CyberSlate = Color(0xFF121212)
val CyberCard = Color(0xFF1E1E1E)
val CyberAccent = Color(0xFFFF9800) // Deep Orange Glow
val CyberMagenta = Color(0xFFE91E63) // Neon pink
val CyberCyan = Color(0xFF00E5FF) // Cyber ice blue
val CyberDivider = Color(0xFF2C2C2C)
val PremiumGold = Color(0xFFFFD700)

@Composable
fun CyberAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = CyberSlate,
            surface = CyberCard,
            primary = CyberAccent,
            secondary = CyberCyan,
            tertiary = CyberMagenta
        ),
        content = content
    )
}

// ------------------- Main UI Dashboard -------------------
@Composable
fun MainDashboardScreen(
    viewModel: CustomizerViewModel,
    billingManager: BillingManager,
    onGrantAdmin: () -> Unit,
    onStartWellbeingCheck: () -> Unit
) {
    val context = LocalContext.current
    val currentSettings by viewModel.settings.collectAsState()
    val automationsList by viewModel.automations.collectAsState()
    val isProUnlocked by viewModel.isProUnlocked.collectAsState()
    val wellbeingStats by viewModel.usageStatsList.collectAsState()
    val totalScreenTime by viewModel.totalScreenTimeMinutes.collectAsState()

    // Easter Egg counter state
    var versionClicks by remember { mutableStateOf(0) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var adminEmailInput by remember { mutableStateOf("") }

    // Automation Creator dialog state
    var showAddAutomationDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val showMessage: (String) -> Unit = { msg ->
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSlate)
                    .padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AURA",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyan,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Customizer",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Credit: shankp son of ashkar",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // License state layout
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isProUnlocked) Color(0x33FFD700) else Color(0x22FFFFFF),
                        modifier = Modifier.clickable {
                            if (!isProUnlocked) {
                                billingManager.launchBillingFlow(context as android.app.Activity)
                            } else {
                                showMessage("Premium Features Fully Activated!")
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isProUnlocked) Icons.Default.Star else Icons.Default.Lock,
                                contentDescription = "License Status",
                                tint = if (isProUnlocked) PremiumGold else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isProUnlocked) "PRO ACTIVE" else "UPGRADE PRO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isProUnlocked) PremiumGold else Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = CyberSlate
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // CARD 1: Wallpaper & Shortcut Customizer
            item {
                CardSection(title = "Wallpaper & Shortcuts", icon = Icons.Default.Build) {
                    WallpaperCustomizerView(context, showMessage)
                }
            }

            // CARD 2: Shake Flashlight Gesture
            item {
                CardSection(title = "Shake for Flashlight", icon = Icons.Default.Refresh) {
                    ShakeFlashlightView(
                        settings = currentSettings,
                        onUpdate = { viewModel.updateSettings(context, it) }
                    )
                }
            }

            // CARD 3: Screen Gestures (Double Tap Sleep / Wake)
            item {
                CardSection(title = "Double-Tap Screen Gestures", icon = Icons.Default.PlayArrow) {
                    DoubleTapGesturesView(
                        context = context,
                        settings = currentSettings,
                        onGrantAdmin = onGrantAdmin,
                        showMessage = showMessage,
                        onUpdate = { viewModel.updateSettings(context, it) }
                    )
                }
            }

            // CARD 4: Privacy Shader Overlay (PRO)
            item {
                CardSection(
                    title = "Privacy Display Overlay", 
                    icon = Icons.Default.Check, 
                    isPro = true, 
                    isUnlocked = isProUnlocked,
                    onUpgradeClick = { billingManager.launchBillingFlow(context as android.app.Activity) }
                ) {
                    PrivacyOverlayPane(
                        settings = currentSettings,
                        showMessage = showMessage,
                        onUpdate = { viewModel.updateSettings(context, it) }
                    )
                }
            }

            // CARD 5: Smart Offline Automations (PRO)
            item {
                CardSection(
                    title = "Smart Offline Automations", 
                    icon = Icons.Default.Settings, 
                    isPro = true, 
                    isUnlocked = isProUnlocked,
                    onUpgradeClick = { billingManager.launchBillingFlow(context as android.app.Activity) }
                ) {
                    AutomationsPane(
                        automations = automationsList,
                        onAddClick = { showAddAutomationDialog = true },
                        onDeleteClick = { viewModel.removeAutomation(it) }
                    )
                }
            }

            // CARD 6: Digital Wellbeing Analysis (PRO)
            item {
                CardSection(
                    title = "Wellbeing Telemetry", 
                    icon = Icons.Default.Info, 
                    isPro = true, 
                    isUnlocked = isProUnlocked,
                    onUpgradeClick = { billingManager.launchBillingFlow(context as android.app.Activity) }
                ) {
                    WellbeingPane(
                        totalMinutes = totalScreenTime,
                        usageList = wellbeingStats,
                        onCheckStats = { onStartWellbeingCheck() }
                    )
                }
            }

            // FOOTER with Easter Egg click counter
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VERSION 1.0.0 (AURA CYBER-EMBER BUILD)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable {
                                versionClicks++
                                if (versionClicks >= 7) {
                                    versionClicks = 0
                                    showAdminDialog = true
                                } else if (versionClicks > 3) {
                                    showMessage("${7 - versionClicks} taps to administrative config")
                                }
                            }
                            .padding(8.dp)
                    )
                    Text(
                        text = "Real-time Zero Battery Offline Shield",
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }
    }

    // ------------------- Easter Egg Admin Dialog -------------------
    if (showAdminDialog) {
        AlertDialog(
            onDismissRequest = { showAdminDialog = false },
            title = {
                Text(
                    text = "Aura Bypass Diagnostics",
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Please enter structural developer credentials to bypass billing restrictions.",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    TextField(
                        value = adminEmailInput,
                        onValueChange = { adminEmailInput = it },
                        label = { Text("Developer Email") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color(0x33FFFFFF)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = CyberMagenta),
                    onClick = {
                        if (adminEmailInput.trim().equals("kpshan52@gmail.com", ignoreCase = true)) {
                            billingManager.enableDeveloperBypass()
                            showMessage("DESTRUCTIVE BYPASS OK! All features unlocked permanently.")
                        } else {
                            showMessage("Incorrect Credentials")
                        }
                        showAdminDialog = false
                    }
                ) {
                    Text("Unlock Core")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdminDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = CyberCard
        )
    }

    // ------------------- Create Automation Dialog -------------------
    if (showAddAutomationDialog) {
        var autoName by remember { mutableStateOf("Night Shift Filter") }
        var selectedTriggerType by remember { mutableStateOf("TIME") } // "TIME" or "BATTERY"
        var triggerVal by remember { mutableStateOf("22:00") }
        var selectedActionType by remember { mutableStateOf("PRIVACY_FILTER") } // "WALLPAPER", "PRIVACY_FILTER", "BOTH"
        var actionValue by remember { mutableStateOf("ON") } // comma separater Gradient colors, or "ON"/"OFF" for filters

        AlertDialog(
            onDismissRequest = { showAddAutomationDialog = false },
            title = {
                Text(
                    text = "Setup Offline Automation",
                    fontWeight = FontWeight.Bold,
                    color = CyberAccent
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = autoName,
                        onValueChange = { autoName = it },
                        label = { Text("Automation Name") }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (selectedTriggerType == "TIME") CyberCyan else Color.DarkGray),
                            onClick = { 
                                selectedTriggerType = "TIME"
                                triggerVal = "22:00"
                            }
                        ) {
                            Text("Time Event")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (selectedTriggerType == "BATTERY") CyberCyan else Color.DarkGray),
                            onClick = { 
                                selectedTriggerType = "BATTERY"
                                triggerVal = "20"
                            }
                        ) {
                            Text("Battery Event")
                        }
                    }

                    OutlinedTextField(
                        value = triggerVal,
                        onValueChange = { triggerVal = it },
                        label = { Text(if (selectedTriggerType == "TIME") "Trigger Hour (HH:mm)" else "Battery Threshold (<= %)") }
                    )

                    Text("Trigger Actions:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (selectedActionType == "WALLPAPER") CyberMagenta else Color.DarkGray),
                            onClick = { 
                                selectedActionType = "WALLPAPER"
                                actionValue = "#3F51B5,#00E5FF" // Cool gradient default
                            }
                        ) {
                            Text("Wallpaper", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (selectedActionType == "PRIVACY_FILTER") CyberMagenta else Color.DarkGray),
                            onClick = { 
                                selectedActionType = "PRIVACY_FILTER"
                                actionValue = "ON"
                            }
                        ) {
                            Text("Overlay", fontSize = 11.sp  , maxLines = 1)
                        }
                    }

                    OutlinedTextField(
                        value = actionValue,
                        onValueChange = { actionValue = it },
                        label = { Text(if (selectedActionType == "WALLPAPER") "Gradient Colors (Comma)" else "Filter Target State (ON/OFF)") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val auto = AutomationEntity(
                            name = autoName,
                            triggerType = selectedTriggerType,
                            triggerValue = triggerVal,
                            actionType = selectedActionType,
                            actionValue = actionValue
                        )
                        viewModel.addAutomation(auto)
                        showAddAutomationDialog = false
                    }
                ) {
                    Text("Save Action")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAutomationDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = CyberCard
        )
    }
}

// ------------------- Reuseable Styled Cards -------------------
@Composable
fun CardSection(
    title: String,
    icon: ImageVector,
    isPro: Boolean = false,
    isUnlocked: Boolean = true,
    onUpgradeClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(20.dp))
                    Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (isPro) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isUnlocked) Color(0x33FF9800) else Color(0xFFFF9800),
                    ) {
                        Text(
                            text = "PRO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Divider(color = CyberDivider, modifier = Modifier.padding(vertical = 12.dp))

            if (isPro && !isUnlocked) {
                // Blur Panel overlay placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .background(Color(0x1AFFFFFF), shape = RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = PremiumGold, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Premium Customization Features Hidden",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Access display layers, device timers, and usage counters.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumGold),
                            onClick = onUpgradeClick
                        ) {
                            Text("Upgrade to Pro", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                content()
            }
        }
    }
}

// ------------------- View 1: Wallpapers & Icons Pane -------------------
@Composable
fun WallpaperCustomizerView(context: Context, showMessage: (String) -> Unit) {
    var iconLabelInput by remember { mutableStateOf("Aura Quick") }
    
    // Preset dynamic color gradients. Real-time rendering inside system canvas
    val presets = listOf(
        Pair("#EE0979", "#FF6A00"), // Sunset Ember
        Pair("#00E5FF", "#7B1FA2"), // Cyber Violet
        Pair("#00FF87", "#60EFFF"), // Ocean Splash
        Pair("#111111", "#444444")  // Stealth Slate
    )
    
    var selectedGradientIndex by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Elegant Active Wallpaper Preset:", fontSize = 13.sp, color = Color.Gray)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            presets.forEachIndexed { index, pair ->
                val startColor = Color(android.graphics.Color.parseColor(pair.first))
                val endColor = Color(android.graphics.Color.parseColor(pair.second))
                
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(startColor, endColor)))
                        .border(
                            width = if (selectedGradientIndex == index) 3.dp else 1.dp,
                            color = if (selectedGradientIndex == index) CyberCyan else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { selectedGradientIndex = index }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                onClick = {
                    val pair = presets[selectedGradientIndex]
                    applyWallpaperLocally(context, android.graphics.Color.parseColor(pair.first), android.graphics.Color.parseColor(pair.second), showMessage)
                }
            ) {
                Text("Set System Live Screen", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Divider(color = CyberDivider, modifier = Modifier.padding(vertical = 4.dp))

        Text("Create Custom Homescreen Shortcut Icon:", fontSize = 13.sp, color = Color.Gray)
        OutlinedTextField(
            value = iconLabelInput,
            onValueChange = { iconLabelInput = it },
            label = { Text("Shortcut Label") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = Color.DarkGray
            )
        )

        Button(
            colors = ButtonDefaults.buttonColors(containerColor = CyberMagenta),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                    if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                        val pinShortcutInfo = ShortcutInfo.Builder(context, "shortcut_" + System.currentTimeMillis())
                            .setShortLabel(iconLabelInput)
                            .setIcon(Icon.createWithResource(context, android.R.drawable.star_on))
                            .setIntent(Intent(context, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                            })
                            .build()
                        shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                        showMessage("Pin Shortcut Request sent!")
                    }
                } else {
                    showMessage("Requires Android 8.0+")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Inject Custom Shortcut Icon", fontWeight = FontWeight.Bold)
        }
    }
}

private fun applyWallpaperLocally(context: Context, startColorInt: Int, endColorInt: Int, showMessage: (String) -> Unit) {
    try {
        val wm = WallpaperManager.getInstance(context)
        // Set bitmap Wallpaper dimensions
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                startColorInt, endColorInt, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        wm.setBitmap(bitmap)
        showMessage("Real-time background wallpaper successfully set!")
    } catch (e: Exception) {
        showMessage("Failed to apply wallpaper: " + e.message)
    }
}

// ------------------- View 2: Shake Flashlight Gesture Panel -------------------
@Composable
fun ShakeFlashlightView(settings: SettingsEntity, onUpdate: (SettingsEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Shake Detection State", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Force shake phone to toggle LED flash", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(
                checked = settings.isShakeFlashlightEnabled,
                onCheckedChange = { onUpdate(settings.copy(isShakeFlashlightEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = CyberAccent)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text("Shake Impulse Threshold: ${settings.shakeSensitivity.toInt()}", fontSize = 12.sp, color = Color.LightGray)
        Slider(
            value = settings.shakeSensitivity,
            onValueChange = { onUpdate(settings.copy(shakeSensitivity = it)) },
            valueRange = 8.0f..25.0f,
            colors = SliderDefaults.colors(
                thumbColor = CyberAccent,
                activeTrackColor = CyberAccent
            )
        )
    }
}

// ------------------- View 3: Double Tap Gestures Panel -------------------
@Composable
fun DoubleTapGesturesView(
    context: Context,
    settings: SettingsEntity,
    onGrantAdmin: () -> Unit,
    showMessage: (String) -> Unit,
    onUpdate: (SettingsEntity) -> Unit
) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, AdminReceiver::class.java)
    val isAdminActive = dpm.isAdminActive(adminComponent)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Option 1: Double Tap to Sleep (Admin Lock)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Double-Tap to Sleep", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Closes screen layout using system Device Admin", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(
                checked = settings.isDoubleTapSleepEnabled && isAdminActive,
                onCheckedChange = {
                    if (it) {
                        if (!isAdminActive) {
                            onGrantAdmin()
                        } else {
                            onUpdate(settings.copy(isDoubleTapSleepEnabled = true))
                        }
                    } else {
                        onUpdate(settings.copy(isDoubleTapSleepEnabled = false))
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)
            )
        }

        // Action Trigger Button if enabled
        if (settings.isDoubleTapSleepEnabled && isAdminActive) {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                onClick = {
                    try {
                        dpm.lockNow()
                    } catch (e: Exception) {
                        showMessage("Admin lockout error: " + e.message)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Force Lock Sleep State", color = Color.White)
            }
        }

        Divider(color = CyberDivider, modifier = Modifier.padding(vertical = 4.dp))

        // Option 2: Active Double-Tap to Wake (AMOLED Black Overlay)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Double-Tap to Wake Workaround", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Simulates low-power screen blackout (OLED off) with touch polling active", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(
                checked = settings.isDoubleTapWakeEnabled,
                onCheckedChange = { onUpdate(settings.copy(isDoubleTapWakeEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = CyberMagenta)
            )
        }

        if (settings.isDoubleTapWakeEnabled) {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = CyberMagenta),
                onClick = {
                    // Start screen black gesture overlay
                    val sleepIntent = Intent(context, GestureService::class.java).apply {
                        action = GestureService.ACTION_TRIGGER_SLEEP_WINDOW
                    }
                    context.startService(sleepIntent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Trigger OLED Sleep Canvas Overlay", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------- View 4: Privacy Screen Shading Panel -------------------
@Composable
fun PrivacyOverlayPane(settings: SettingsEntity, showMessage: (String) -> Unit, onUpdate: (SettingsEntity) -> Unit) {
    val context = LocalContext.current
    
    // Check if Overlay permission is granted
    val hasOverlayPermission = Settings.canDrawOverlays(context)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Privacy Filter Shader Overlay", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Renders viewing angle protection filters on viewport", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(
                checked = settings.isPrivacyOverlayEnabled && hasOverlayPermission,
                onCheckedChange = {
                    if (it) {
                        if (!hasOverlayPermission) {
                            showMessage("Grant 'Display over other apps' permissions")
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            context.startActivity(intent)
                        } else {
                            onUpdate(settings.copy(isPrivacyOverlayEnabled = true))
                        }
                    } else {
                        onUpdate(settings.copy(isPrivacyOverlayEnabled = false))
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text("Anti-Peeping Filter Opacity: ${(settings.privacyOverlayOpacity * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
        Slider(
            value = settings.privacyOverlayOpacity,
            onValueChange = { onUpdate(settings.copy(privacyOverlayOpacity = it)) },
            valueRange = 0.1f..0.8f,
            colors = SliderDefaults.colors(
                thumbColor = CyberCyan,
                activeTrackColor = CyberCyan
            )
        )

        Text("Select Viewing Pattern Protection Grid:", fontSize = 13.sp, color = Color.Gray)
        val patterns = listOf("None Solid Dark", "Matrix Fine Grid", "Amber Warming Shading", "Diagonal Shield Stripes")
        patterns.forEachIndexed { index, name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(settings.copy(privacyPatternIndex = index)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = settings.privacyPatternIndex == index,
                    onClick = { onUpdate(settings.copy(privacyPatternIndex = index)) },
                    colors = RadioButtonDefaults.colors(selectedColor = CyberCyan)
                )
                Text(text = name, fontSize = 13.sp, color = Color.LightGray)
            }
        }
    }
}

// ------------------- View 5: Smart Automations Panel -------------------
@Composable
fun AutomationsPane(
    automations: List<AutomationEntity>,
    onAddClick: () -> Unit,
    onDeleteClick: (AutomationEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Automated Triggers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            TextButton(onClick = onAddClick) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Aut", modifier = Modifier.size(16.dp))
                    Text("Add Rule", color = CyberCyan, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (automations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No automations registered. Standard time or power events can trigger presets.",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            automations.forEach { auto ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x1F2A2A2A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = auto.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                            Text(
                                text = "Trigger: ${auto.triggerType} (${auto.triggerValue}) -> Action: ${auto.actionType}",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }

                        IconButton(onClick = { onDeleteClick(auto) }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------- View 6: Digital Wellbeing Pane -------------------
@Composable
fun WellbeingPane(
    totalMinutes: Int,
    usageList: List<com.example.viewmodel.AppUsageInfo>,
    onCheckStats: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Daily Screen Time Counter", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Total foreground usage logs compiled locally", fontSize = 11.sp, color = Color.Gray)
            }
            IconButton(onClick = onCheckStats) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync tele", tint = CyberCyan)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular visual progress indicator
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = Color.DarkGray,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx())
                    )
                    drawArc(
                        color = CyberMagenta,
                        startAngle = -90f,
                        sweepAngle = (totalMinutes.toFloat() / 360f * 360f).coerceIn(10f, 360f),
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx())
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${totalMinutes / 60}h ${totalMinutes % 60}m",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(text = "Today", fontSize = 8.sp, color = Color.Gray)
                }
            }

            // Screen list items details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                usageList.take(3).forEach { usage ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = usage.packageName, fontSize = 12.sp, color = Color.White)
                            Text(text = "${usage.minutesUsed} mins", fontSize = 11.sp, color = Color.Gray)
                        }
                        // Custom Progress bar
                        LinearProgressIndicator(
                            progress = { usage.percentage },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = CyberCyan,
                            trackColor = Color.DarkGray,
                        )
                    }
                }
            }
        }
    }
}
