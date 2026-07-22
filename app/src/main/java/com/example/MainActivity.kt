package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.repository.SmsMessage
import com.example.data.repository.SmsThread
import com.example.data.repository.NirvanaContact
import com.example.data.database.SpamRule
import com.example.ui.NirvanaLocal
import com.example.ui.NirvanaViewModel
import com.example.ui.theme.NirvanaFont
import com.example.ui.theme.NirvanaTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: NirvanaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val fontStyle by viewModel.fontStyle.collectAsState()
            val fontSizeScale by viewModel.fontSizeScale.collectAsState()
            val language by viewModel.appLanguage.collectAsState()

            val layoutDirection = if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                NirvanaTheme(themeMode = themeMode) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NirvanaMainScreen(
                            viewModel = viewModel,
                            currentActivity = this,
                            themeMode = themeMode,
                            fontStyle = fontStyle,
                            fontSizeScale = fontSizeScale,
                            language = language
                        )
                    }
                }
            }
        }
    }

    private var hasRequestedDefaultThisResume = false

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        viewModel.checkSystemStates()
        if (viewModel.hasPermissions.value) {
            viewModel.loadAllSmsData()
        }
        if (!viewModel.isDefaultSms.value && !hasRequestedDefaultThisResume) {
            hasRequestedDefaultThisResume = true
            viewModel.requestDefaultSmsRole(this)
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        hasRequestedDefaultThisResume = false
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2026) {
            viewModel.checkSystemStates()
            if (viewModel.isDefaultSms.value) {
                Toast.makeText(this, "Nirvana is now your default SMS app!", Toast.LENGTH_SHORT).show()
                viewModel.loadAllSmsData()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        var isAppInForeground: Boolean = false
        var activeThreadAddress: String? = null
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NirvanaMainScreen(
    viewModel: NirvanaViewModel,
    currentActivity: MainActivity,
    themeMode: String,
    fontStyle: String,
    fontSizeScale: Float,
    language: String
) {
    val isDefault by viewModel.isDefaultSms.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showWelcomeDialog by viewModel.showWelcomeDialog.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Navigation State: "threads", "thread_details", "settings"
    var currentScreen by remember { mutableStateOf("threads") }
    var activeThread by remember { mutableStateOf<SmsThread?>(null) }
    var isNewChatDialogOpen by remember { mutableStateOf(false) }
    var initialChatPhone by remember { mutableStateOf("") }
    var initialChatBody by remember { mutableStateOf("") }
    var selectedFolderTab by remember { mutableStateOf(0) }
    var settingsCategoryToOpen by remember { mutableStateOf<String?>(null) }
    var sharedIncomingText by remember { mutableStateOf<String?>(null) }

    var backPressedTime by remember { mutableStateOf(0L) }

    BackHandler(enabled = true) {
        if (isNewChatDialogOpen) {
            isNewChatDialogOpen = false
        } else if (currentScreen == "thread_details") {
            currentScreen = "threads"
            activeThread = null
        } else if (currentScreen == "settings") {
            currentScreen = "threads"
            settingsCategoryToOpen = null
        } else if (selectedFolderTab != 0) {
            selectedFolderTab = 0
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - backPressedTime < 2000) {
                currentActivity.finish()
            } else {
                backPressedTime = currentTime
                val exitMsg = if (language == "fa") {
                    "برای خروج دوباره دکمه برگشت را بزنید"
                } else {
                    "Press back again to exit"
                }
                Toast.makeText(currentActivity, exitMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(activeThread) {
        MainActivity.activeThreadAddress = activeThread?.address
        activeThread?.let { thread ->
            try {
                val notificationManager = currentActivity.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                if (notificationManager != null) {
                    notificationManager.cancel(thread.address.hashCode())
                    val cleanAddr = thread.address.replace("[^0-9]".toRegex(), "")
                    if (cleanAddr.isNotBlank()) {
                        notificationManager.cancel(cleanAddr.hashCode())
                    }
                    notificationManager.cancel(2026)
                    notificationManager.cancelAll()
                }
            } catch (e: Exception) {
                // Ignore context/notificationManager exception
            }
        }
    }

    // Intent Handling (Deep Linking from Notifications, System Contacts & Share Sheet)
    LaunchedEffect(currentActivity.intent) {
        val intent = currentActivity.intent
        if (intent != null) {
            val threadId = intent.getLongExtra("thread_id", -1L)
            val notificationAddress = intent.getStringExtra("address")
            val navigateToSpam = intent.getBooleanExtra("navigate_to_spam", false)

            if (threadId != -1L && notificationAddress != null) {
                if (navigateToSpam) {
                    selectedFolderTab = 1
                }
                val isSpam = navigateToSpam
                val resolvedName = viewModel.getContactName(notificationAddress)
                activeThread = SmsThread(
                    threadId = threadId,
                    address = notificationAddress,
                    body = "",
                    date = System.currentTimeMillis(),
                    unreadCount = 0,
                    isSpam = isSpam,
                    senderName = resolvedName
                )
                viewModel.loadMessagesForThread(threadId)
                currentScreen = "thread_details"
                
                // Clear the notification extras
                intent.removeExtra("thread_id")
                intent.removeExtra("address")
                intent.removeExtra("navigate_to_spam")
            } else {
                val intentData = intent.data
                var extractedAddress: String? = null
                var extractedBody: String? = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body")

                if (intentData != null) {
                    val scheme = intentData.scheme
                    if ("sms" == scheme || "smsto" == scheme || "mms" == scheme || "mmsto" == scheme) {
                        val ssp = intentData.schemeSpecificPart
                        if (!ssp.isNullOrBlank()) {
                            val parts = ssp.split("?")
                            extractedAddress = parts.firstOrNull()?.trim()
                            if (extractedBody.isNullOrBlank() && ssp.contains("body=")) {
                                extractedBody = android.net.Uri.decode(ssp.substringAfter("body="))
                            }
                        }
                    }
                }

                if (extractedAddress.isNullOrBlank()) {
                    extractedAddress = intent.getStringExtra("sms_address")
                        ?: intent.getStringExtra("android.intent.extra.PHONE_NUMBER")
                }

                if (!extractedAddress.isNullOrBlank()) {
                    var cleanAddress = android.net.Uri.decode(extractedAddress)
                        .replace("[\u200e\u200f\u202a-\u202e]".toRegex(), "")
                        .replace("tel:", "")
                        .replace("sms:", "")
                        .replace("smsto:", "")
                        .replace("mms:", "")
                        .replace("mmsto:", "")
                        .trim()
                    
                    cleanAddress = cleanAddress
                        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
                        .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')

                    if (cleanAddress.isNotBlank()) {
                        initialChatPhone = cleanAddress
                        initialChatBody = extractedBody ?: ""
                        isNewChatDialogOpen = true
                        intent.data = null
                        intent.removeExtra("sms_address")
                        intent.removeExtra("android.intent.extra.PHONE_NUMBER")
                    }
                }
            }

            if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    sharedIncomingText = sharedText
                    intent.action = null
                    intent.removeExtra(Intent.EXTRA_TEXT)
                }
            }
        }
    }

    DisposableEffect(activeThread) {
        val reloadUiReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                viewModel.loadAllSmsData()
                activeThread?.let {
                    viewModel.loadMessagesForThread(it.threadId)
                }
            }
        }
        val filter = android.content.IntentFilter("com.example.SMS_RELOAD_UI")
        androidx.core.content.ContextCompat.registerReceiver(
            currentActivity,
            reloadUiReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        onDispose {
            try {
                currentActivity.unregisterReceiver(reloadUiReceiver)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // Multi-Permission Request Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsRead = permissions[Manifest.permission.READ_SMS] ?: false
        
        viewModel.checkSystemStates()
        if (smsRead) {
            viewModel.onPermissionsGranted()
        }
    }

    // Direct launch of permissions request when missing
    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
    }

    // Periodically check system default SMS app state if the app is not default yet
    LaunchedEffect(isDefault) {
        if (!isDefault) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                viewModel.checkSystemStates()
                if (viewModel.isDefaultSms.value) {
                    break
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            if (hasPermissions && currentScreen != "thread_details") {
                NirvanaBottomBar(
                    currentScreen = currentScreen,
                    onNavigate = { screen ->
                        currentScreen = screen
                    },
                    language = language,
                    fontStyle = fontStyle,
                    fontSizeScale = fontSizeScale
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermissions) {
                // Permission Request Screen
                NirvanaPermissionPanel(
                    onGrantClick = {
                        permissionsLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_CONTACTS
                            )
                        )
                    },
                    language = language,
                    fontStyle = fontStyle,
                    fontSizeScale = fontSizeScale
                )
            } else {
                // Main Application Layout with Animated Navigation
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        slideInHorizontally(
                            initialOffsetX = { if (language == "fa") -it else it },
                            animationSpec = spring()
                        ) with slideOutHorizontally(
                            targetOffsetX = { if (language == "fa") it else -it },
                            animationSpec = spring()
                        )
                    }
                ) { targetScreen ->
                    when (targetScreen) {
                        "threads" -> {
                                NirvanaThreadsScreen(
                                    viewModel = viewModel,
                                    isDefault = isDefault,
                                    onRequestDefault = { viewModel.requestDefaultSmsRole(currentActivity) },
                                    onThreadSelect = { thread ->
                                        activeThread = thread
                                        viewModel.loadMessagesForThread(thread.threadId)
                                        currentScreen = "thread_details"
                                    },
                                    onFabClick = { isNewChatDialogOpen = true },
                                    language = language,
                                    fontStyle = fontStyle,
                                    fontSizeScale = fontSizeScale,
                                    selectedFolderTab = selectedFolderTab,
                                    onRedirectToSettingsSecurity = {
                                        currentScreen = "settings"
                                        settingsCategoryToOpen = "security"
                                    },
                                    onTabSelect = { selectedFolderTab = it }
                                )
                        }
                        "thread_details" -> {
                            val threadToDisplay = activeThread
                            if (threadToDisplay != null) {
                                NirvanaThreadDetailsScreen(
                                    viewModel = viewModel,
                                    initialThread = threadToDisplay,
                                    onBack = {
                                        currentScreen = "threads"
                                        activeThread = null
                                    },
                                    language = language,
                                    fontStyle = fontStyle,
                                    fontSizeScale = fontSizeScale
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize())
                            }
                        }
                        "settings" -> {
                            NirvanaSettingsScreen(
                                viewModel = viewModel,
                                currentTheme = themeMode,
                                fontStyle = fontStyle,
                                fontSizeScale = fontSizeScale,
                                language = language,
                                initialCategory = settingsCategoryToOpen,
                                onNavigateToThread = { thread ->
                                    activeThread = thread
                                    currentScreen = "thread_details"
                                },
                                onBack = {
                                    currentScreen = "threads"
                                    settingsCategoryToOpen = null
                                }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (language == "fa") "درحال پردازش و همگام‌سازی..." else "Processing & Syncing...",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Start New Conversation Dialog
            if (isNewChatDialogOpen) {
                NirvanaNewChatDialog(
                    viewModel = viewModel,
                    initialPhoneNumber = initialChatPhone,
                    initialMessageBody = initialChatBody,
                    onDismiss = {
                        isNewChatDialogOpen = false
                        initialChatPhone = ""
                        initialChatBody = ""
                    },
                    onStartChat = { addresses, body, subId ->
                        isNewChatDialogOpen = false
                        initialChatPhone = ""
                        initialChatBody = ""
                        coroutineScope.launch {
                            val addressList = addresses.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            if (addressList.isEmpty()) return@launch

                            var lastThread: SmsThread? = null
                            for (address in addressList) {
                                val threadId = viewModel.getOrCreateThreadId(address)
                                val name = viewModel.getContactName(address)
                                
                                val thread = SmsThread(
                                    threadId = threadId,
                                    address = address,
                                    body = body.ifBlank { "..." },
                                    date = System.currentTimeMillis(),
                                    unreadCount = 0,
                                    isSpam = false,
                                    senderName = name
                                )
                                lastThread = thread
                                
                                if (body.isNotBlank()) {
                                    viewModel.sendSmsMessage(address, body, threadId, subId)
                                } else {
                                    viewModel.loadMessagesForThread(threadId)
                                }
                            }

                            lastThread?.let {
                                activeThread = it
                                currentScreen = "thread_details"
                            }
                        }
                    },
                    language = language,
                    fontStyle = fontStyle,
                    fontSizeScale = fontSizeScale
                )
            }

            // Welcome to Nirvana Dialog
            if (showWelcomeDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissWelcomeDialog() },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.dismissWelcomeDialog() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (language == "fa") "متوجه شدم و ورود" else "Get Started",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                            )
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Welcome",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    title = {
                        Text(
                            text = if (language == "fa") "به نیروانا خوش آمدید! 🌟" else "Welcome to Nirvana!",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (language == "fa") 
                                    "پیام‌رسان هوشمند، امن و ضد تبلیغ شما با موفقیت راه‌اندازی شد. تمام پیام‌ها از این پس فیلتر، تفکیک و در محیطی زیبا سازمان‌دهی می‌شوند."
                                    else "Your smart, secure and ad-blocking messenger has been successfully configured. Messages will now be beautifully filtered and categorized.",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                )
            }
        }

        if (sharedIncomingText != null) {
            NirvanaForwardMessageDialog(
                viewModel = viewModel,
                messageText = sharedIncomingText!!,
                onDismiss = { sharedIncomingText = null },
                language = language,
                fontStyle = fontStyle,
                fontSizeScale = fontSizeScale
            )
        }
    }
}

@Composable
fun NirvanaBottomBar(
    currentScreen: String,
    onNavigate: (String) -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    NavigationBar(
        tonalElevation = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavigationBarItem(
            selected = currentScreen == "threads",
            onClick = { onNavigate("threads") },
            icon = { Icon(Icons.Filled.Sms, contentDescription = "Chats") },
            label = {
                Text(
                    text = NirvanaLocal.get("conversations", language),
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Medium)
                )
            },
            modifier = Modifier.testTag("nav_chats")
        )
        NavigationBarItem(
            selected = currentScreen == "settings",
            onClick = { onNavigate("settings") },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            label = {
                Text(
                    text = NirvanaLocal.get("settings", language),
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Medium)
                )
            },
            modifier = Modifier.testTag("nav_settings")
        )
    }
}

@Composable
fun NirvanaPermissionPanel(
    onGrantClick: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App logo container with a nice glowing effect
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                color = Color.White,
                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 48, FontWeight.ExtraBold)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = NirvanaLocal.get("app_title", language),
            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 26, FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = NirvanaLocal.get("no_permission", language),
            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onGrantClick,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(52.dp)
                .testTag("grant_permissions_btn"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                text = NirvanaLocal.get("grant_permission", language),
                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NirvanaThreadsScreen(
    viewModel: NirvanaViewModel,
    isDefault: Boolean,
    onRequestDefault: () -> Unit,
    onThreadSelect: (SmsThread) -> Unit,
    onFabClick: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float,
    selectedFolderTab: Int,
    onRedirectToSettingsSecurity: () -> Unit,
    onTabSelect: (Int) -> Unit
) {
    val threads by viewModel.threads.collectAsState()
    val spamThreads by viewModel.spamThreads.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchBarExpanded by remember { mutableStateOf(false) }
    val promoText by viewModel.promoText.collectAsState()
    val promoUrl by viewModel.promoUrl.collectAsState()

    var threadToHide by remember { mutableStateOf<SmsThread?>(null) }
    var showHidePinPrompt by remember { mutableStateOf(false) }
    var hidePinInput by remember { mutableStateOf("") }
    var hidePinError by remember { mutableStateOf(false) }

    // Hidden Chats holding FAB states
    var showHiddenChatsAuthDialog by remember { mutableStateOf(false) }
    var hiddenChatsUnlockPinAttempt by remember { mutableStateOf("") }
    var hiddenChatsUnlockError by remember { mutableStateOf(false) }
    var showHiddenChatsListDialog by remember { mutableStateOf(false) }
    var showAddHiddenContactDialog by remember { mutableStateOf(false) }
    var showPinWarningDialog by remember { mutableStateOf(false) }

    // Advanced search states
    var isAdvancedSearchOpen by remember { mutableStateOf(false) }
    var searchSender by remember { mutableStateOf("") }
    var searchKeyword by remember { mutableStateOf("") }
    var searchDateRange by remember { mutableStateOf("all") } // "all", "today", "week", "month"

    val now = System.currentTimeMillis()
    val activeThreadsList = if (selectedFolderTab == 0) threads else spamThreads
    val filteredThreads = activeThreadsList.filter { thread ->
        val keywordMatch = if (searchKeyword.isNotEmpty()) {
            thread.body.contains(searchKeyword, ignoreCase = true)
        } else {
            searchQuery.isEmpty() || 
            thread.body.contains(searchQuery, ignoreCase = true) ||
            thread.address.contains(searchQuery) ||
            thread.senderName?.contains(searchQuery, ignoreCase = true) == true
        }

        val senderMatch = if (searchSender.isNotEmpty()) {
            thread.address.contains(searchSender) ||
            thread.senderName?.contains(searchSender, ignoreCase = true) == true
        } else {
            true
        }

        val dateMatch = when (searchDateRange) {
            "today" -> thread.date >= (now - 24 * 3600 * 1000)
            "week" -> thread.date >= (now - 7 * 24 * 3600 * 1000)
            "month" -> thread.date >= (now - 30 * 24 * 3600 * 1000)
            else -> true
        }

        keywordMatch && senderMatch && dateMatch
    }

    val isSelectionMode = remember { mutableStateOf(false) }
    val selectedThreadIds = remember { mutableStateListOf<Long>() }

    BackHandler(enabled = isSelectionMode.value) {
        isSelectionMode.value = false
        selectedThreadIds.clear()
    }

    LaunchedEffect(selectedFolderTab) {
        isSelectionMode.value = false
        selectedThreadIds.clear()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // App Header / Search Toolbar
        if (isSelectionMode.value) {
            Surface(
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            isSelectionMode.value = false
                            selectedThreadIds.clear()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (language == "fa") {
                                "${toPersianDigits(selectedThreadIds.size.toString())} انتخاب شده"
                            } else {
                                "${selectedThreadIds.size} Selected"
                            },
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val allIds = filteredThreads.map { it.threadId }
                        val isAllSelected = selectedThreadIds.size == filteredThreads.size && filteredThreads.isNotEmpty()

                        Button(
                            onClick = {
                                if (isAllSelected) {
                                    selectedThreadIds.clear()
                                } else {
                                    selectedThreadIds.clear()
                                    selectedThreadIds.addAll(allIds)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isAllSelected) {
                                    if (language == "fa") "لغو همه" else "Deselect All"
                                } else {
                                    if (language == "fa") "انتخاب همه" else "Select All"
                                },
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold)
                            )
                        }

                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                if (selectedThreadIds.isNotEmpty()) {
                                    val targetSpamStatus = selectedFolderTab == 0
                                    viewModel.toggleSpamStatusForThreads(selectedThreadIds.toList(), targetSpamStatus)
                                    isSelectionMode.value = false
                                    selectedThreadIds.clear()
                                    val actionMsg = if (targetSpamStatus) {
                                        if (language == "fa") "به لیست ضد تبلیغ منتقل شدند" else "Moved to anti-spam"
                                    } else {
                                        if (language == "fa") "به چت‌های عادی بازگردانده شدند" else "Restored to normal chats"
                                    }
                                    Toast.makeText(context, actionMsg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = selectedThreadIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = if (selectedFolderTab == 0) Icons.Default.Block else Icons.Default.Check,
                                contentDescription = if (selectedFolderTab == 0) "Block Selected" else "Restore Selected",
                                tint = if (selectedThreadIds.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                if (selectedThreadIds.isNotEmpty()) {
                                    selectedThreadIds.forEach { id ->
                                        viewModel.deleteThread(id)
                                    }
                                    isSelectionMode.value = false
                                    selectedThreadIds.clear()
                                }
                            },
                            enabled = selectedThreadIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (selectedThreadIds.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                if (isSearchBarExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isSearchBarExpanded = false
                                searchQuery = ""
                                isAdvancedSearchOpen = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = NirvanaLocal.get("search_hint", language),
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("search_sms_field"),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            singleLine = true
                        )

                        IconButton(
                            onClick = { isAdvancedSearchOpen = !isAdvancedSearchOpen },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = if (isAdvancedSearchOpen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Advanced Search",
                                tint = if (isAdvancedSearchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "N",
                                    color = Color.White,
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = NirvanaLocal.get("app_title", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 20, FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { isSearchBarExpanded = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (language == "fa") "فارسی" else "English",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Promo/Advertisement banner on the Search Box
                if (promoText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val context = LocalContext.current
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (promoUrl.isNotBlank()) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(promoUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Campaign,
                                    contentDescription = "Promo",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = NirvanaLocal.get("promo_title", language),
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = promoText,
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (promoUrl.isNotBlank()) {
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(promoUrl))
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = NirvanaLocal.get("promo_action", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }

                // Advanced search dropdown box
                if (isAdvancedSearchOpen) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = NirvanaLocal.get("search_advanced", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Sender Textfield
                            OutlinedTextField(
                                value = searchSender,
                                onValueChange = { searchSender = it },
                                placeholder = {
                                    Text(
                                        text = NirvanaLocal.get("search_sender", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Keyword Textfield
                            OutlinedTextField(
                                value = searchKeyword,
                                onValueChange = { searchKeyword = it },
                                placeholder = {
                                    Text(
                                        text = NirvanaLocal.get("search_keyword", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Date Range Choice Chips
                            Text(
                                text = NirvanaLocal.get("search_date_range", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val dates = listOf("all", "today", "week", "month")
                                dates.forEach { dateKey ->
                                    val title = when (dateKey) {
                                        "today" -> NirvanaLocal.get("date_today", language)
                                        "week" -> NirvanaLocal.get("date_week", language)
                                        "month" -> NirvanaLocal.get("date_month", language)
                                        else -> NirvanaLocal.get("date_all", language)
                                    }

                                    FilterChip(
                                        selected = searchDateRange == dateKey,
                                        onClick = { searchDateRange = dateKey },
                                        label = {
                                            Text(
                                                text = title,
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        val isDefaultBannerDismissed by viewModel.isDefaultBannerDismissed.collectAsState()

        // Default SMS Application Prompt
        if (!isDefault && !isDefaultBannerDismissed) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("default_sms_banner"),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (language == "fa") "نیاز به تنظیم پیش‌فرض" else "Default App Required",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        IconButton(
                            onClick = { viewModel.dismissDefaultBanner() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = NirvanaLocal.get("default_sms_prompt", language),
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        lineHeight = 18.sp
                    )
                    
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Button(
                        onClick = onRequestDefault,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("set_default_sms_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SettingsSuggest,
                            contentDescription = "Set Default",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = NirvanaLocal.get("set_default", language),
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }

        val conversationsUnreadCount = remember(threads) { threads.sumOf { it.unreadCount } }
        val spamUnreadCount = remember(spamThreads) { spamThreads.sumOf { it.unreadCount } }
        val draftsMap by viewModel.draftsMap.collectAsState()
        val showContactNames by viewModel.showContactNames.collectAsState()
        val showMessagePreviewInList by viewModel.showMessagePreviewInList.collectAsState()
        val isPinchZoomEnabled by viewModel.isPinchZoomEnabled.collectAsState()

        // Folder Tabs (Normal Chats vs. Anti-Spam folder)
        TabRow(
            selectedTabIndex = selectedFolderTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedFolderTab == 0,
                onClick = { onTabSelect(0) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Message, contentDescription = "Chats")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = NirvanaLocal.get("conversations", language),
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold)
                        )
                        if (conversationsUnreadCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    text = if (language == "fa") toPersianDigits(conversationsUnreadCount.toString()) else conversationsUnreadCount.toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("tab_conversations")
            )
            Tab(
                selected = selectedFolderTab == 1,
                onClick = { onTabSelect(1) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = "Anti-Spam")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = NirvanaLocal.get("anti_spam", language),
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold)
                        )
                        if (spamUnreadCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    text = if (language == "fa") toPersianDigits(spamUnreadCount.toString()) else spamUnreadCount.toString(),
                                    color = MaterialTheme.colorScheme.onError,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("tab_anti_spam")
            )
        }

        val promoText by viewModel.promoText.collectAsState()
        val promoUrl by viewModel.promoUrl.collectAsState()

        // Chat Threads List
        Box(
            modifier = Modifier
                .weight(1f)
        ) {
            if (filteredThreads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (selectedFolderTab == 0) Icons.Filled.MailOutline else Icons.Filled.CheckCircle,
                            contentDescription = "Empty Folder",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (selectedFolderTab == 0) {
                                NirvanaLocal.get("empty_threads", language)
                            } else {
                                NirvanaLocal.get("empty_spam", language)
                            },
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isPinchZoomEnabled, fontSizeScale) {
                            if (isPinchZoomEnabled) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.size >= 2) {
                                            event.changes.forEach { it.consume() }
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f && !zoom.isNaN()) {
                                                val newScale = (fontSizeScale * zoom).coerceIn(0.7f, 1.8f)
                                                if (kotlin.math.abs(newScale - fontSizeScale) > 0.012f) {
                                                    viewModel.setFontSize(newScale)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredThreads, key = { it.threadId }) { thread ->
                        NirvanaThreadRowItem(
                            thread = thread,
                            language = language,
                            fontStyle = fontStyle,
                            fontSizeScale = fontSizeScale,
                            onClick = {
                                if (isSelectionMode.value) {
                                    if (selectedThreadIds.contains(thread.threadId)) {
                                        selectedThreadIds.remove(thread.threadId)
                                    } else {
                                        selectedThreadIds.add(thread.threadId)
                                    }
                                } else {
                                    onThreadSelect(thread)
                                }
                            },
                            onToggleSpam = { viewModel.toggleSpamStatusForThread(thread) },
                            onDelete = { viewModel.deleteThread(thread.threadId) },
                            isSelectionMode = isSelectionMode.value,
                            isSelected = selectedThreadIds.contains(thread.threadId),
                            draftText = draftsMap[thread.threadId],
                            showContactNames = showContactNames,
                            showMessagePreviewInList = showMessagePreviewInList,
                            onHide = {
                                val currentPin = viewModel.securePin.value
                                if (currentPin.isEmpty()) {
                                    onRedirectToSettingsSecurity()
                                } else {
                                    threadToHide = thread
                                    showHidePinPrompt = true
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode.value) {
                                    isSelectionMode.value = true
                                    selectedThreadIds.add(thread.threadId)
                                }
                            }
                        )
                    }
                }
            }

            if (showHidePinPrompt && threadToHide != null) {
                val currentPin = viewModel.securePin.value
                val useBiometric = viewModel.useBiometric.value
                AlertDialog(
                    onDismissRequest = {
                        showHidePinPrompt = false
                        threadToHide = null
                        hidePinInput = ""
                        hidePinError = false
                    },
                    title = {
                        Text(
                            text = if (language == "fa") "تایید هویت" else "Security Verification",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold)
                        )
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (language == "fa") "جهت مخفی‌سازی گفتگو، رمز ۴ رقمی خود را وارد کنید." else "Please enter your 4-digit passcode to hide this conversation.",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = hidePinInput,
                                onValueChange = {
                                    if (it.length <= 4) {
                                        hidePinInput = it
                                        hidePinError = false
                                        if (it == currentPin) {
                                            viewModel.hideThread(threadToHide!!.threadId)
                                            showHidePinPrompt = false
                                            threadToHide = null
                                            hidePinInput = ""
                                        }
                                    }
                                },
                                label = { Text(if (language == "fa") "رمز عبور" else "Passcode") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )

                            if (hidePinError) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (language == "fa") "رمز عبور نادرست است" else "Incorrect passcode",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (useBiometric) {
                                Spacer(modifier = Modifier.height(12.dp))
                                IconButton(
                                    onClick = {
                                        viewModel.hideThread(threadToHide!!.threadId)
                                        showHidePinPrompt = false
                                        threadToHide = null
                                        hidePinInput = ""
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Fingerprint,
                                        contentDescription = "Fingerprint",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (hidePinInput == currentPin) {
                                    viewModel.hideThread(threadToHide!!.threadId)
                                    showHidePinPrompt = false
                                    threadToHide = null
                                    hidePinInput = ""
                                } else {
                                    hidePinError = true
                                }
                            }
                        ) {
                            Text(text = if (language == "fa") "تایید" else "Verify")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showHidePinPrompt = false
                                threadToHide = null
                                hidePinInput = ""
                                hidePinError = false
                            }
                        ) {
                            Text(text = if (language == "fa") "انصراف" else "Cancel")
                        }
                    }
                )
            }

            // Start New Chat FAB
            Box(
                modifier = Modifier
                    .align(if (language == "fa") Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(18.dp)
                    .size(56.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                    .clip(CircleShape)
                    .testTag("start_chat_fab")
                    .combinedClickable(
                        onClick = {
                            onFabClick()
                        },
                        onLongClick = {
                            val currentPin = viewModel.securePin.value
                            if (currentPin.isEmpty()) {
                                showPinWarningDialog = true
                            } else {
                                showHiddenChatsAuthDialog = true
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AddComment,
                    contentDescription = "Start Chat",
                    tint = Color.White
                )
            }

            // Pin Warning Dialog
            if (showPinWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showPinWarningDialog = false },
                    title = {
                        Text(
                            text = if (language == "fa") "تنظیم رمز عبور" else "Set Passcode",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold)
                        )
                    },
                    text = {
                        Text(
                            text = if (language == "fa") 
                                "برای دسترسی به چت‌های مخفی، ابتدا باید رمز عبور را از بخش تنظیمات -> امنیت فعال کنید." 
                                else "To access hidden chats, you must first set a passcode in Settings -> Security.",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showPinWarningDialog = false
                                onRedirectToSettingsSecurity()
                            }
                        ) {
                            Text(text = if (language == "fa") "تنظیم رمز" else "Set Passcode")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showPinWarningDialog = false }
                        ) {
                            Text(text = if (language == "fa") "انصراف" else "Cancel")
                        }
                    }
                )
            }

            // Hidden Chats Auth Dialog
            if (showHiddenChatsAuthDialog) {
                val currentPin = viewModel.securePin.value
                val useBiometric = viewModel.useBiometric.value
                AlertDialog(
                    onDismissRequest = {
                        showHiddenChatsAuthDialog = false
                        hiddenChatsUnlockPinAttempt = ""
                        hiddenChatsUnlockError = false
                    },
                    title = {
                        Text(
                            text = if (language == "fa") "ورود به چت‌های مخفی" else "Hidden Chats Access",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold)
                        )
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (language == "fa") "لطفاً رمز عبور ۴ رقمی خود را وارد کنید." else "Please enter your 4-digit passcode to access hidden chats.",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = hiddenChatsUnlockPinAttempt,
                                onValueChange = {
                                    if (it.length <= 4) {
                                        hiddenChatsUnlockPinAttempt = it
                                        hiddenChatsUnlockError = false
                                        if (it == currentPin) {
                                            showHiddenChatsAuthDialog = false
                                            hiddenChatsUnlockPinAttempt = ""
                                            showHiddenChatsListDialog = true
                                        }
                                    }
                                },
                                label = { Text(if (language == "fa") "رمز عبور" else "Passcode") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )

                            if (hiddenChatsUnlockError) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (language == "fa") "رمز عبور نادرست است" else "Incorrect passcode",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (useBiometric) {
                                Spacer(modifier = Modifier.height(12.dp))
                                IconButton(
                                    onClick = {
                                        showHiddenChatsAuthDialog = false
                                        hiddenChatsUnlockPinAttempt = ""
                                        showHiddenChatsListDialog = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Fingerprint,
                                        contentDescription = "Fingerprint",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (hiddenChatsUnlockPinAttempt == currentPin) {
                                    showHiddenChatsAuthDialog = false
                                    hiddenChatsUnlockPinAttempt = ""
                                    showHiddenChatsListDialog = true
                                } else {
                                    hiddenChatsUnlockError = true
                                }
                            }
                        ) {
                            Text(text = if (language == "fa") "تایید" else "Verify")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showHiddenChatsAuthDialog = false
                                hiddenChatsUnlockPinAttempt = ""
                                hiddenChatsUnlockError = false
                            }
                        ) {
                            Text(text = if (language == "fa") "انصراف" else "Cancel")
                        }
                    }
                )
            }

            // Hidden Chats List Dialog
            if (showHiddenChatsListDialog) {
                val hiddenThreads by viewModel.hiddenThreads.collectAsState()
                val hiddenPhoneNumbers by viewModel.hiddenPhoneNumbers.collectAsState()
                val context = LocalContext.current
                
                AlertDialog(
                    onDismissRequest = {
                        showHiddenChatsListDialog = false
                    },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (language == "fa") "گفتگوهای مخفی" else "Hidden Conversations",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold)
                            )
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Hidden",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                        ) {
                            // Button to Add New Hidden Contact
                            OutlinedButton(
                                onClick = { showAddHiddenContactDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Add Hidden Contact",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (language == "fa") "افزودن مخاطب به لیست مخفی" else "Add Contact to Hidden List",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold)
                                )
                            }

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (hiddenThreads.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = if (language == "fa") "گفتگوهای فعال مخفی شده:" else "Active Hidden Conversations:",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }

                                    items(hiddenThreads) { thread ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .clickable {
                                                    showHiddenChatsListDialog = false
                                                    onThreadSelect(thread)
                                                }
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = thread.senderName ?: thread.address,
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = thread.body,
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                            
                                            IconButton(
                                                onClick = {
                                                    viewModel.unhideThread(thread.threadId)
                                                    Toast.makeText(
                                                        context,
                                                        if (language == "fa") "گفتگو از حالت مخفی خارج شد" else "Conversation unhidden",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.LockOpen,
                                                    contentDescription = "Unhide",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (hiddenPhoneNumbers.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = if (language == "fa") "شماره‌های تماس مخفی شده:" else "Hidden Phone Numbers:",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                        )
                                    }

                                    items(hiddenPhoneNumbers.toList()) { phone ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = phone,
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            
                                            IconButton(
                                                onClick = {
                                                    viewModel.removeHiddenPhoneNumber(phone)
                                                    Toast.makeText(
                                                        context,
                                                        if (language == "fa") "شماره از لیست مخفی حذف شد" else "Number removed from hidden list",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Remove",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (hiddenThreads.isEmpty() && hiddenPhoneNumbers.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (language == "fa") "هیچ گفتگو یا شماره مخفی یافت نشد." else "No hidden conversations or numbers found.",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showHiddenChatsListDialog = false
                            }
                        ) {
                            Text(text = if (language == "fa") "بستن" else "Close")
                        }
                    }
                )
            }

            if (showAddHiddenContactDialog) {
                NirvanaAddHiddenContactDialog(
                    viewModel = viewModel,
                    onDismiss = { showAddHiddenContactDialog = false },
                    language = language,
                    fontStyle = fontStyle,
                    fontSizeScale = fontSizeScale
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NirvanaThreadRowItem(
    thread: SmsThread,
    language: String,
    fontStyle: String,
    fontSizeScale: Float,
    onClick: () -> Unit,
    onToggleSpam: () -> Unit,
    onDelete: () -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onHide: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    draftText: String? = null,
    showContactNames: Boolean = true,
    showMessagePreviewInList: Boolean = true
) {
    var expandedMenu by remember { mutableStateOf(false) }

    val initialLetter = (thread.senderName ?: thread.address).trim().take(1).uppercase()
    val isUnread = thread.unreadCount > 0
    val hasDraft = !draftText.isNullOrBlank()

    val displayName = if (showContactNames) {
        thread.senderName ?: thread.address
    } else {
        if (thread.senderName == "تماس بی‌پاسخ" || thread.senderName?.startsWith("تماس بی‌پاسخ") == true) {
            "تماس بی‌پاسخ"
        } else {
            thread.address
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    expandedMenu = true
                }
            )
            .background(
                if (isUnread) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Circle Avatar
            val bankInfo = detectBank(thread.address, thread.senderName)
            if (thread.photoUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thread.photoUri)
                            .crossfade(true)
                            .build()
                    ),
                    contentDescription = "Contact Avatar",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else if (bankInfo != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(bankInfo.color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bankInfo.shortName,
                        color = bankInfo.textColor,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (initialLetter.matches("[A-Z0-9]".toRegex())) initialLetter else "👤",
                        color = Color.White,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Text Info Panel
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = NirvanaFont.getTextStyle(
                            fontStyle, 
                            fontSizeScale, 
                            15, 
                            if (isUnread) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Text(
                        text = formatTimestamp(thread.date, language),
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

                if (showMessagePreviewInList) {
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val displaySnippet = if (hasDraft) {
                            if (language == "fa") "پیش‌نویس: $draftText" else "Draft: $draftText"
                        } else {
                            thread.body
                        }
                        val snippetColor = if (hasDraft) {
                            MaterialTheme.colorScheme.error
                        } else if (isUnread) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        }

                        Text(
                            text = displaySnippet,
                            style = NirvanaFont.getTextStyle(
                                fontStyle, 
                                fontSizeScale, 
                                13, 
                                if (hasDraft || isUnread) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = snippetColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                    if (isUnread) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = thread.unreadCount.toString(),
                                color = Color.White,
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }

        // Long Press Context Menu
        DropdownMenu(
            expanded = expandedMenu,
            onDismissRequest = { expandedMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (thread.isSpam) NirvanaLocal.get("unblock", language) else NirvanaLocal.get("block", language),
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                    )
                },
                onClick = {
                    expandedMenu = false
                    onToggleSpam()
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (thread.isSpam) Icons.Filled.MarkEmailRead else Icons.Filled.Block,
                        contentDescription = "Spam Action"
                    )
                }
            )
            if (onHide != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fa") "مخفی کردن گفتگو" else "Hide Conversation",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    },
                    onClick = {
                        expandedMenu = false
                        onHide()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Hide Conversation"
                        )
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (language == "fa") "انتخاب و چند گزینه‌ای" else "Select / Multi-select",
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                    )
                },
                onClick = {
                    expandedMenu = false
                    if (onLongClick != null) onLongClick()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Select"
                    )
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = NirvanaLocal.get("delete", language),
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                    )
                },
                onClick = {
                    expandedMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete Thread",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}

sealed class ChatTimelineItem {
    data class DateSeparator(val dateKey: String, val id: String) : ChatTimelineItem()
    data class MessageItem(val message: SmsMessage) : ChatTimelineItem()
}

@Composable
fun NirvanaThreadDetailsScreen(
    viewModel: NirvanaViewModel,
    initialThread: SmsThread,
    onBack: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    val messages by viewModel.activeMessages.collectAsState()
    val pendingMsg by viewModel.pendingDelayedMessage.collectAsState()
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current

    val allThreads by viewModel.threads.collectAsState()
    val allSpamThreads by viewModel.spamThreads.collectAsState()
    val allHiddenThreads by viewModel.hiddenThreads.collectAsState()

    val thread = remember(allThreads, allSpamThreads, allHiddenThreads, initialThread) {
        val found = (allThreads + allSpamThreads + allHiddenThreads).find { it.threadId == initialThread.threadId || it.address == initialThread.address }
        found ?: initialThread
    }

    val savedDraft = remember(thread.threadId) { viewModel.getDraft(thread.threadId) }
    var messageBody by remember(thread.threadId) { mutableStateOf(savedDraft) }

    LaunchedEffect(thread.threadId, messageBody) {
        viewModel.saveDraft(thread.threadId, messageBody)
    }

    DisposableEffect(thread.threadId) {
        onDispose {
            viewModel.saveDraft(thread.threadId, messageBody)
        }
    }

    BackHandler(enabled = true) {
        viewModel.saveDraft(thread.threadId, messageBody)
        onBack()
    }

    val timelineItems = remember(messages, language) {
        val list = mutableListOf<ChatTimelineItem>()
        var lastDateKey = ""
        messages.forEachIndexed { index, message ->
            val dateKey = if (language == "fa") {
                JalaliCalendar.getPersianDateString(message.date)
            } else {
                val sdf = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.ENGLISH)
                sdf.format(java.util.Date(message.date))
            }
            if (dateKey != lastDateKey) {
                list.add(ChatTimelineItem.DateSeparator(dateKey, "sep_${message.id}_${message.date}_$index"))
                lastDateKey = dateKey
            }
            list.add(ChatTimelineItem.MessageItem(message))
        }
        list
    }

    var showActionMenu by remember { mutableStateOf(false) }
    var forwardingMessageText by remember { mutableStateOf<String?>(null) }
    var replyingToMessage by remember { mutableStateOf<SmsMessage?>(null) }
    val isSwipeToReplyEnabled by viewModel.isSwipeToReplyEnabled.collectAsState()
    val bubbleColorScheme by viewModel.bubbleColorScheme.collectAsState()
    val isPinchZoomEnabled by viewModel.isPinchZoomEnabled.collectAsState()

    // Multi-SIM selection
    val activeSims by viewModel.activeSims.collectAsState()
    var selectedSim by remember { mutableStateOf<com.example.data.repository.SimInfo?>(null) }
    LaunchedEffect(activeSims) {
        if (activeSims.isNotEmpty() && selectedSim == null) {
            selectedSim = activeSims.first()
        }
    }

    // Sticker and scheduling states
    var showStickersPanel by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var scheduleDelayText by remember { mutableStateOf("15") } // seconds
    val quickStickers = listOf("✨", "🌸", "🌟", "❤️", "👍", "😂", "🎉", "🚀", "🔥", "💯", "😎", "🤔", "👏", "🥳", "سلام", "ممنون", "خیلی عالی", "چطوری؟")

    // Scroll to the end of the list when new messages load
    LaunchedEffect(timelineItems.size) {
        if (timelineItems.isNotEmpty()) {
            lazyListState.scrollToItem(timelineItems.size - 1)
        }
    }

    // Schedule message dialog
    if (showScheduleDialog) {
        var scheduleDaysOffset by remember { mutableStateOf(0) } // 0 = Today, 1 = Tomorrow, 2 = Day after tomorrow, 3 = In 3 days, 5 = In 5 days, 7 = In 1 week
        val initialCal = java.util.Calendar.getInstance()
        initialCal.add(java.util.Calendar.MINUTE, 5) // default is 5 mins in the future
        
        var scheduleHour by remember { mutableStateOf(initialCal.get(java.util.Calendar.HOUR_OF_DAY)) }
        var scheduleMinute by remember { mutableStateOf(initialCal.get(java.util.Calendar.MINUTE)) }
        
        val calculatedTargetTime = remember(scheduleDaysOffset, scheduleHour, scheduleMinute) {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, scheduleDaysOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, scheduleHour)
            cal.set(java.util.Calendar.MINUTE, scheduleMinute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            
            var targetMillis = cal.timeInMillis
            val currentMillis = System.currentTimeMillis()
            if (targetMillis <= currentMillis && scheduleDaysOffset == 0) {
                targetMillis = currentMillis + 60_000 // force at least 1 min in the future
            }
            targetMillis
        }

        val delaySeconds = remember(calculatedTargetTime) {
            val diff = (calculatedTargetTime - System.currentTimeMillis()) / 1000L
            if (diff < 1L) 1L else diff
        }

        Dialog(onDismissRequest = { showScheduleDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = NirvanaLocal.get("schedule_at", language),
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = messageBody,
                        onValueChange = { messageBody = it },
                        placeholder = {
                            Text(
                                text = NirvanaLocal.get("type_msg", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(10.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Date Selector Label
                    Text(
                        text = if (language == "fa") "انتخاب تاریخ:" else "Select Date:",
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                        modifier = Modifier.align(Alignment.Start),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    val dayOptions = if (language == "fa") {
                        listOf("امروز", "فردا", "پس‌فردا", "۳ روز بعد", "۵ روز بعد", "۱ هفته بعد")
                    } else {
                        listOf("Today", "Tomorrow", "In 2 days", "In 3 days", "In 5 days", "In 1 week")
                    }
                    val dayOffsets = listOf(0, 1, 2, 3, 5, 7)
                    
                    var dayMenuExpanded by remember { mutableStateOf(false) }
                    val currentDayLabel = dayOptions[dayOffsets.indexOf(scheduleDaysOffset).coerceIn(0, dayOffsets.size - 1)]
                    
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { dayMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = currentDayLabel,
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        }
                        DropdownMenu(
                            expanded = dayMenuExpanded,
                            onDismissRequest = { dayMenuExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        ) {
                            dayOptions.forEachIndexed { idx, label ->
                                DropdownMenuItem(
                                    text = { Text(label, style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)) },
                                    onClick = {
                                        scheduleDaysOffset = dayOffsets[idx]
                                        dayMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Time Selection Label
                    Text(
                        text = if (language == "fa") "ساعت و دقیقه:" else "Hour & Minute:",
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                        modifier = Modifier.align(Alignment.Start),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hour Field
                        OutlinedTextField(
                            value = String.format("%02d", scheduleHour),
                            onValueChange = { newValue ->
                                val h = newValue.toIntOrNull()
                                if (h != null && h in 0..23) {
                                    scheduleHour = h
                                } else if (newValue.isEmpty()) {
                                    scheduleHour = 0
                                }
                            },
                            modifier = Modifier.width(70.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        
                        Text(
                            text = ":",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        
                        // Minute Field
                        OutlinedTextField(
                            value = String.format("%02d", scheduleMinute),
                            onValueChange = { newValue ->
                                val m = newValue.toIntOrNull()
                                if (m != null && m in 0..59) {
                                    scheduleMinute = m
                                } else if (newValue.isEmpty()) {
                                    scheduleMinute = 0
                                }
                            },
                            modifier = Modifier.width(70.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(18.dp))
                    
                    // Display Calculated Date and Time
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (language == "fa") "زمان دقیق ارسال:" else "Scheduled Send Time:",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatDetailedTimestamp(calculatedTargetTime, language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showScheduleDialog = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (language == "fa") "انصراف" else "Cancel",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.addScheduledMessage(thread.address, messageBody, delaySeconds, thread.threadId)
                                messageBody = ""
                                showScheduleDialog = false
                            },
                            enabled = messageBody.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = NirvanaLocal.get("save", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }

    if (forwardingMessageText != null) {
        NirvanaForwardMessageDialog(
            viewModel = viewModel,
            messageText = forwardingMessageText!!,
            onDismiss = { forwardingMessageText = null },
            language = language,
            fontStyle = fontStyle,
            fontSizeScale = fontSizeScale
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Conversation Header
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        viewModel.saveDraft(thread.threadId, messageBody)
                        onBack()
                    },
                    modifier = Modifier.testTag("conversation_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                val bankInfo = detectBank(thread.address, thread.senderName)
                if (thread.photoUri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(thread.photoUri)
                                .crossfade(true)
                                .build()
                        ),
                        contentDescription = "Contact Avatar",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else if (bankInfo != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(bankInfo.color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = bankInfo.shortName,
                            color = bankInfo.textColor,
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.senderName ?: thread.address,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = thread.address,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // Phone Call Icon Button
                IconButton(
                    onClick = {
                        try {
                            val callIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${thread.address}"))
                            context.startActivity(callIntent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, if (language == "fa") "امکان برقراری تماس وجود ندارد" else "Cannot make phone call", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Call Contact",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Three dot actions menu
                IconButton(onClick = { showActionMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                }

                DropdownMenu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (thread.isSpam) NirvanaLocal.get("unblock", language) else NirvanaLocal.get("block", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showActionMenu = false
                            viewModel.toggleSpamStatusForThread(thread)
                            onBack()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (thread.isSpam) Icons.Filled.MarkEmailRead else Icons.Filled.Block,
                                contentDescription = "Spam status toggle"
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = NirvanaLocal.get("delete", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showActionMenu = false
                            viewModel.deleteThread(thread.threadId)
                            onBack()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete chat",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }

        // Spam Warning Banner if thread is classified as spam
        if (thread.isSpam) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Spam",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (language == "fa") "این گفتگو به عنوان اسپم تبلیغاتی تفکیک شده است." 
                                   else "This chat is filtered in Anti-Spam folder.",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.toggleSpamStatusForThread(thread)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (language == "fa") "تایید و انتقال به چت عادی" else "Approve & Move",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // Messages Timeline
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(isPinchZoomEnabled, fontSizeScale) {
                    if (isPinchZoomEnabled) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
                                    event.changes.forEach { it.consume() }
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f && !zoom.isNaN()) {
                                        val newScale = (fontSizeScale * zoom).coerceIn(0.7f, 1.8f)
                                        if (kotlin.math.abs(newScale - fontSizeScale) > 0.012f) {
                                            viewModel.setFontSize(newScale)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = timelineItems,
                key = { item ->
                    when (item) {
                        is ChatTimelineItem.DateSeparator -> item.id
                        is ChatTimelineItem.MessageItem -> "msg_${item.message.id}_${item.message.date}"
                    }
                }
            ) { item ->
                when (item) {
                    is ChatTimelineItem.DateSeparator -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            ) {
                                Text(
                                    text = item.dateKey,
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    is ChatTimelineItem.MessageItem -> {
                        NirvanaMessageBubble(
                            message = item.message,
                            language = language,
                            fontStyle = fontStyle,
                            fontSizeScale = fontSizeScale,
                            onDeleteMessage = {
                                viewModel.deleteMessage(item.message.id, thread.threadId)
                            },
                            onForwardMessage = {
                                forwardingMessageText = item.message.body
                            },
                            onSaveMessage = {
                                viewModel.saveMessage(
                                    sender = item.message.address,
                                    senderName = thread.senderName,
                                    body = item.message.body,
                                    timestamp = item.message.date
                                )
                                Toast.makeText(
                                    context,
                                    if (language == "fa") "پیام ذخیره شد" else "Message saved",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onReplyMessage = {
                                replyingToMessage = item.message
                            },
                            isSwipeToReplyEnabled = isSwipeToReplyEnabled,
                            bubbleColorScheme = bubbleColorScheme
                        )
                    }
                }
            }
        }

        // Bottom Input Toolbar
        Surface(
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Reply Preview Banner
                replyingToMessage?.let { replyMsg ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Reply,
                                    contentDescription = "Reply",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (language == "fa") "پاسخ به:" else "Replying to:",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = getCleanMessageContent(replyMsg.body),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Cancel reply",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Delayed sending countdown banner
                pendingMsg?.let { pm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(NirvanaLocal.get("sending_in", language), pm.secondsRemaining),
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    viewModel.sendNowDelayed()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = NirvanaLocal.get("send_now", language),
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                )
                            }
                            Button(
                                onClick = {
                                    messageBody = pm.body
                                    viewModel.cancelDelayedSend()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = NirvanaLocal.get("cancel_send", language),
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // Dual SIM selector if multiple SIMs are detected
                if (activeSims.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${NirvanaLocal.get("sim_select", language)}:",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        activeSims.forEach { sim ->
                            FilterChip(
                                selected = selectedSim?.id == sim.id,
                                onClick = { selectedSim = sim },
                                label = {
                                    Text(
                                        text = "SIM ${sim.slotIndex + 1} (${sim.carrierName})",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Medium)
                                    )
                                }
                            )
                        }
                    }
                }

                // Sticker panel
                if (showStickersPanel) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${NirvanaLocal.get("sticker_title", language)}:",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            items(quickStickers) { sticker ->
                                SuggestionChip(
                                    onClick = {
                                        messageBody = messageBody + sticker
                                    },
                                    label = {
                                        Text(
                                            text = sticker,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sticker Panel Toggle Button
                    IconButton(
                        onClick = { showStickersPanel = !showStickersPanel },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (showStickersPanel) Icons.Filled.Close else Icons.Filled.SentimentSatisfiedAlt,
                            contentDescription = "Stickers",
                            tint = if (showStickersPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Scheduled Message Dialog Toggle Button
                    IconButton(
                        onClick = { showScheduleDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = "Schedule Send",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = messageBody,
                            onValueChange = { 
                                messageBody = it 
                                viewModel.saveDraft(thread.threadId, it)
                            },
                            placeholder = {
                                Text(
                                    text = NirvanaLocal.get("type_msg", language),
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_input_field"),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        )

                        val (smsSegments, smsRemaining) = remember(messageBody) {
                            if (messageBody.isEmpty()) {
                                Pair(1, 70)
                            } else {
                                val isGsm = messageBody.all { it.code <= 127 }
                                val singleLimit = if (isGsm) 160 else 70
                                val multiLimit = if (isGsm) 153 else 67
                                val len = messageBody.length
                                if (len <= singleLimit) {
                                    Pair(1, singleLimit - len)
                                } else {
                                    val seg = ((len - 1) / multiLimit) + 1
                                    val rem = (seg * multiLimit) - len
                                    Pair(seg, rem)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "$smsSegments/$smsRemaining",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Beautiful Circular Send Button
                    FilledIconButton(
                        onClick = {
                            val finalBody = if (replyingToMessage != null) {
                                val cleanTargetBody = getCleanMessageContent(replyingToMessage!!.body)
                                val cleanSnippet = cleanTargetBody.replace("\n", " ").take(50)
                                "↩️ [پاسخ به: $cleanSnippet...]\n$messageBody"
                            } else {
                                messageBody
                            }
                            viewModel.sendSmsMessage(thread.address, finalBody, thread.threadId, selectedSim?.id)
                            messageBody = ""
                            viewModel.saveDraft(thread.threadId, "")
                            replyingToMessage = null
                            showStickersPanel = false
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_send_btn"),
                        enabled = messageBody.isNotBlank(),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }
    }
}

private data class LocalTextSegment(
    val text: String,
    val start: Int,
    val end: Int,
    val isNumber: Boolean,
    val isUrl: Boolean
)

@Composable
fun CopyableNumbersText(
    text: String,
    textColor: Color,
    fontStyle: String,
    fontSizeScale: Float,
    onClickNumber: (String) -> Unit,
    onFallbackClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val segments = remember(text) {
        val segList = mutableListOf<LocalTextSegment>()

        // Find numbers (English and Persian digits, optional leading +)
        val numMatcher = java.util.regex.Pattern.compile("(\\+?[0-9۰-۹]{3,13})").matcher(text)
        while (numMatcher.find()) {
            segList.add(LocalTextSegment(numMatcher.group(), numMatcher.start(), numMatcher.end(), isNumber = true, isUrl = false))
        }

        // Find URLs
        val urlMatcher = java.util.regex.Pattern.compile("(https?://[^\\s]+|www\\.[^\\s]+)").matcher(text)
        while (urlMatcher.find()) {
            segList.add(LocalTextSegment(urlMatcher.group(), urlMatcher.start(), urlMatcher.end(), isNumber = false, isUrl = true))
        }

        // Sort segments by start index and filter overlaps
        val sortedSegments = segList.sortedWith(compareBy({ it.start }, { -it.end }))
        val nonOverlapping = mutableListOf<LocalTextSegment>()
        var currentEnd = 0
        for (seg in sortedSegments) {
            if (seg.start >= currentEnd) {
                nonOverlapping.add(seg)
                currentEnd = seg.end
            }
        }
        nonOverlapping
    }

    val annotatedString = remember(text, textColor, segments, primaryColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            var lastIndex = 0
            for (seg in segments) {
                if (seg.start > lastIndex) {
                    append(text.substring(lastIndex, seg.start))
                }

                if (seg.isNumber) {
                    pushStringAnnotation(tag = "COPYABLE_NUMBER", annotation = seg.text)
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            color = if (textColor == Color.White) Color(0xFFFFEB3B) else primaryColor
                        )
                    )
                    append(seg.text)
                    pop()
                    pop()
                } else if (seg.isUrl) {
                    pushStringAnnotation(tag = "CLICKABLE_URL", annotation = seg.text)
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            color = if (textColor == Color.White) Color(0xFF80DEEA) else Color(0xFF0288D1)
                        )
                    )
                    append(seg.text)
                    pop()
                    pop()
                }

                lastIndex = seg.end
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    androidx.compose.material3.Text(
        text = annotatedString,
        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14).copy(color = textColor),
        onTextLayout = { textLayoutResult = it },
        modifier = androidx.compose.ui.Modifier
            .pointerInput(annotatedString) {
                detectTapGestures(
                    onLongPress = {
                        onLongClick()
                    },
                    onTap = { pos: androidx.compose.ui.geometry.Offset ->
                        val layout = textLayoutResult
                        if (layout != null) {
                            val offset = layout.getOffsetForPosition(pos)
                            
                            val urlAnnotations = annotatedString.getStringAnnotations(tag = "CLICKABLE_URL", start = offset, end = offset)
                            val clickedUrl = urlAnnotations.firstOrNull()?.item
                            
                            val numAnnotations = annotatedString.getStringAnnotations(tag = "COPYABLE_NUMBER", start = offset, end = offset)
                            val clickedNumber = numAnnotations.firstOrNull()?.item

                            if (clickedUrl != null) {
                                try {
                                    var formattedUrl = clickedUrl
                                    if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                                        formattedUrl = "http://" + formattedUrl
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(formattedUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Could not open link", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else if (clickedNumber != null) {
                                onClickNumber(clickedNumber)
                            } else {
                                onFallbackClick()
                            }
                        } else {
                            onFallbackClick()
                        }
                    }
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

fun getCleanMessageContent(body: String): String {
    var text = body.trim()
    while (text.contains("↩️ [")) {
        val lineBreakIndex = text.indexOf('\n')
        if (lineBreakIndex != -1) {
            val candidate = text.substring(lineBreakIndex + 1).trim()
            if (candidate.isNotBlank()) {
                text = candidate
            } else {
                break
            }
        } else {
            val endIdx = text.indexOf(']')
            if (endIdx != -1 && endIdx < text.length - 1) {
                text = text.substring(endIdx + 1).trim()
            } else {
                break
            }
        }
    }
    return text.ifBlank { body }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NirvanaMessageBubble(
    message: SmsMessage,
    language: String,
    fontStyle: String,
    fontSizeScale: Float,
    onDeleteMessage: () -> Unit,
    onForwardMessage: () -> Unit,
    onSaveMessage: () -> Unit,
    onReplyMessage: (() -> Unit)? = null,
    isSwipeToReplyEnabled: Boolean = true,
    bubbleColorScheme: String = "default"
) {
    val isSent = message.type == 2 // 1 = Received, 2 = Sent
    val alignment = if (isSent) {
        if (language == "fa") Alignment.CenterStart else Alignment.CenterEnd
    } else {
        if (language == "fa") Alignment.CenterEnd else Alignment.CenterStart
    }

    val (bubbleColor, textColor) = when (bubbleColorScheme) {
        "classic_blue" -> if (isSent) Pair(Color(0xFF1976D2), Color.White) else Pair(Color(0xFFECEFF1), Color(0xFF263238))
        "emerald_mint" -> if (isSent) Pair(Color(0xFF00897B), Color.White) else Pair(Color(0xFFE0F2F1), Color(0xFF004D40))
        "purple_lavender" -> if (isSent) Pair(Color(0xFF7B1FA2), Color.White) else Pair(Color(0xFFF3E5F5), Color(0xFF4A148C))
        "sunset_warm" -> if (isSent) Pair(Color(0xFFE65100), Color.White) else Pair(Color(0xFFFFF3E0), Color(0xFF3E2723))
        "modern_dark" -> if (isSent) Pair(Color(0xFF3F51B5), Color.White) else Pair(Color(0xFF37474F), Color.White)
        "rose_pink" -> if (isSent) Pair(Color(0xFFD81B60), Color.White) else Pair(Color(0xFFFCE4EC), Color(0xFF880E4F))
        else -> {
            if (isSent) Pair(MaterialTheme.colorScheme.primary, Color.White)
            else Pair(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    var showDetailedTime by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "swipe_reply_anim"
    )

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(
                if (isSwipeToReplyEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX < -100f && onReplyMessage != null) {
                                    onReplyMessage()
                                }
                                offsetX = 0f
                            },
                            onDragCancel = { offsetX = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount < 0 || offsetX < 0) {
                                    offsetX = (offsetX + dragAmount).coerceIn(-160f, 0f)
                                }
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { translationX = animatedOffsetX }
        ) {
            Column(
                horizontalAlignment = if (isSent) {
                    if (language == "fa") Alignment.Start else Alignment.End
                } else {
                    if (language == "fa") Alignment.End else Alignment.Start
                },
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
            Box {
                val isMissedCallMsg = message.body.contains("تماس بی پاسخ") || 
                    message.body.contains("تماس بی‌پاسخ") || 
                    message.body.contains("تماس از") || 
                    message.body.contains("missed call", ignoreCase = true) || 
                    message.body.contains("تماسبان") || 
                    message.body.contains("تماس‌بان")

                val missedCallNumber = remember(message.body, message.address) {
                    val foundNumber = "(\\+?[0-9۰-۹]{3,13})".toRegex().find(message.body)?.value
                    val num = foundNumber ?: message.address
                    num.replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
                       .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
                       .replace("[^0-9+]".toRegex(), "")
                }

                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isSent) 20.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 20.dp
                    ),
                    shadowElevation = 1.dp,
                    tonalElevation = 3.dp,
                    border = if (!isSent) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)) else null,
                    modifier = Modifier.combinedClickable(
                        onClick = { showDetailedTime = !showDetailedTime },
                        onLongClick = { showMenu = true }
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val isRepliedMsg = message.body.startsWith("↩️ [") || message.body.contains("↩️ [")
                        val lineBreakIndex = message.body.indexOf('\n')
                        val rawQuoteLine = if (isRepliedMsg && lineBreakIndex != -1) message.body.substring(0, lineBreakIndex).trim() else if (isRepliedMsg) message.body else ""
                        val mainResponsePart = if (isRepliedMsg && lineBreakIndex != -1) message.body.substring(lineBreakIndex + 1).trim() else message.body

                        val cleanQuoteText = remember(rawQuoteLine) {
                            var txt = rawQuoteLine
                            if (txt.contains("↩️ [")) {
                                val start = txt.indexOf("↩️ [") + 4
                                val end = txt.indexOf("]", start)
                                txt = if (end != -1 && end > start) txt.substring(start, end) else txt.substring(start)
                            }
                            if (txt.startsWith("پاسخ به: ")) txt = txt.removePrefix("پاسخ به: ")
                            if (txt.startsWith("Replying to: ")) txt = txt.removePrefix("Replying to: ")
                            txt.trim()
                        }

                        if (isRepliedMsg && cleanQuoteText.isNotBlank()) {
                            // Quoted Message Box
                            Surface(
                                color = if (isSent) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(32.dp)
                                            .background(
                                                color = if (isSent) Color.White else MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(2.dp)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Reply,
                                                contentDescription = null,
                                                tint = if (isSent) Color.White else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (language == "fa") "پاسخ به پیام" else "Replying to message",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                                color = if (isSent) Color.White else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = cleanQuoteText,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                            color = textColor.copy(alpha = 0.9f),
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Dashed Line Separator
                            val dashColor = if (isSent) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.35f)
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .height(1.dp)
                            ) {
                                val stroke = Stroke(
                                    width = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                )
                                drawLine(
                                    color = dashColor,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = stroke.width,
                                    pathEffect = stroke.pathEffect
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        CopyableNumbersText(
                            text = if (isRepliedMsg && mainResponsePart.isNotBlank()) mainResponsePart else message.body,
                            textColor = textColor,
                            fontStyle = fontStyle,
                            fontSizeScale = fontSizeScale,
                            onClickNumber = { rawNumber ->
                                val cleanNumber = rawNumber
                                    .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
                                    .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
                                    .replace("[^0-9+]".toRegex(), "")
                                if (cleanNumber.length >= 3) {
                                    try {
                                        val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$cleanNumber"))
                                        context.startActivity(dialIntent)
                                    } catch (e: Exception) {
                                        clipboardManager.setText(AnnotatedString(rawNumber))
                                        Toast.makeText(context, if (language == "fa") "کپی شد: $rawNumber" else "Copied: $rawNumber", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    clipboardManager.setText(AnnotatedString(rawNumber))
                                    Toast.makeText(
                                        context,
                                        if (language == "fa") "کپی شد: $rawNumber" else "Copied: $rawNumber",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onFallbackClick = {
                                if (isMissedCallMsg && missedCallNumber.length >= 3) {
                                    try {
                                        val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$missedCallNumber"))
                                        context.startActivity(dialIntent)
                                    } catch (e: Exception) {
                                        showDetailedTime = !showDetailedTime
                                    }
                                } else {
                                    showDetailedTime = !showDetailedTime
                                }
                            },
                            onLongClick = {
                                showMenu = true
                            }
                        )

                        if (isMissedCallMsg && missedCallNumber.length >= 3) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSent) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .clickable {
                                        try {
                                            val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$missedCallNumber"))
                                            context.startActivity(dialIntent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, if (language == "fa") "خطا در برقراری تماس" else "Call failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Phone,
                                    contentDescription = "Dial",
                                    tint = if (isSent) Color.White else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (language == "fa") "تماس با $missedCallNumber" else "Call $missedCallNumber",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                                    color = if (isSent) Color.White else MaterialTheme.colorScheme.primary,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                )
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (onReplyMessage != null && isSwipeToReplyEnabled) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (language == "fa") "پاسخ به این پیام" else "Reply to Message",
                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onReplyMessage()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Reply,
                                    contentDescription = "Reply"
                                )
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (language == "fa") "کپی کردن متن" else "Copy Text",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showMenu = false
                            clipboardManager.setText(AnnotatedString(message.body))
                            Toast.makeText(
                                context,
                                if (language == "fa") "پیام کپی شد" else "Message copied",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy"
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (language == "fa") "ذخیره در پیام‌های ذخیره شده" else "Save to Saved Messages",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onSaveMessage()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = "Save Message"
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (language == "fa") "هدایت پیام (فوروارد)" else "Forward Message",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onForwardMessage()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Forward"
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (language == "fa") "حذف پیام" else "Delete Message",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDeleteMessage()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showDetailedTime) {
                        formatDetailedTimestamp(message.date, language)
                    } else {
                        formatTimestamp(message.date, language)
                    },
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10),
                    color = if (showDetailedTime) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
                if (isSent) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val isDelivered = message.status == 0
                    val ticks = if (isDelivered) "✓✓" else "✓"
                    val ticksColor = if (isDelivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    Text(
                        text = ticks,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                        color = ticksColor
                    )
                }
            }
        }
        if (animatedOffsetX < -20f && onReplyMessage != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Reply,
                contentDescription = "Reply",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
}

@Composable
fun NirvanaSettingsScreen(
    viewModel: NirvanaViewModel,
    currentTheme: String,
    fontStyle: String,
    fontSizeScale: Float,
    language: String,
    initialCategory: String? = null,
    onNavigateToThread: ((SmsThread) -> Unit)? = null,
    onBack: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(initialCategory) }

    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            selectedCategory = initialCategory
        }
    }

    BackHandler(enabled = true) {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onBack()
        }
    }
    val autoSpam by viewModel.isAutoSpamFilterEnabled.collectAsState()
    val customRules by viewModel.customSpamRules.collectAsState()

    var newRulePattern by remember { mutableStateOf("") }
    var newRuleType by remember { mutableStateOf("KEYWORD") } // "KEYWORD" or "SENDER"

    // Saved Messages states
    val savedMessagesState by viewModel.savedMessages.collectAsState()
    var forwardSavedText by remember { mutableStateOf<String?>(null) }

    // Security & Hidden Chats states
    val securePinState by viewModel.securePin.collectAsState()
    val useBiometricState by viewModel.useBiometric.collectAsState()
    val hiddenThreadsState by viewModel.hiddenThreads.collectAsState()

    var inputPinOld by remember { mutableStateOf("") }
    var inputPinNew by remember { mutableStateOf("") }
    var isPasscodeVisible by remember { mutableStateOf(false) }

    var isUnlocked by remember { mutableStateOf(false) }
    var unlockPinAttempt by remember { mutableStateOf("") }
    var showUnlockError by remember { mutableStateOf(false) }

    // CPanel Ad Server states
    val currentPromoText by viewModel.promoText.collectAsState()
    val currentPromoUrl by viewModel.promoUrl.collectAsState()
    val currentPromoFetchUrl by viewModel.promoFetchUrl.collectAsState()

    var editPromoText by remember { mutableStateOf(currentPromoText) }
    var editPromoUrl by remember { mutableStateOf(currentPromoUrl) }
    var editPromoFetchUrl by remember { mutableStateOf(currentPromoFetchUrl) }

    LaunchedEffect(currentPromoText, currentPromoUrl, currentPromoFetchUrl) {
        editPromoText = currentPromoText
        editPromoUrl = currentPromoUrl
        editPromoFetchUrl = currentPromoFetchUrl
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (forwardSavedText != null) {
            NirvanaForwardMessageDialog(
                viewModel = viewModel,
                messageText = forwardSavedText!!,
                onDismiss = { forwardSavedText = null },
                language = language,
                fontStyle = fontStyle,
                fontSizeScale = fontSizeScale
            )
        }
        if (selectedCategory == null) {
            // Main Categories Menu with Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = NirvanaLocal.get("settings", language),
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 22, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = if (language == "fa") TextAlign.Right else TextAlign.Left
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Category 1: Appearance & Theme
                item {
                    CategoryItem(
                        title = if (language == "fa") "ظاهر و پوسته برنامه" else "Appearance & Themes",
                        description = if (language == "fa") "تغییر تم رنگی، استایل فونت و اندازه قلم نرم‌افزار" else "App color palette, font styles, and size scale",
                        icon = Icons.Filled.Palette,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = { selectedCategory = "appearance" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 2: Anti-Spam & Filtering
                item {
                    CategoryItem(
                        title = if (language == "fa") "فیلترینگ و ضدتبلیغ" else "Anti-Spam & Filters",
                        description = if (language == "fa") "کنترل خودکار پیام‌های اسپم تبلیغاتی و لیست کلمات فیلتر شده" else "Auto block ads and custom spam rule management",
                        icon = Icons.Filled.Block,
                        iconColor = MaterialTheme.colorScheme.error,
                        onClick = { selectedCategory = "spam" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 3: General & Language
                item {
                    CategoryItem(
                        title = if (language == "fa") "تنظیمات عمومی و زبان" else "General Settings",
                        description = if (language == "fa") "تغییر زبان نرم‌افزار به فارسی یا انگلیسی" else "Change language or restore default settings",
                        icon = Icons.Filled.Language,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        onClick = { selectedCategory = "general" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 4: Server & Admin Dashboard
                item {
                    CategoryItem(
                        title = if (language == "fa") "پنل مدیریت و اتصال به هاست" else "cPanel Host & Admin Panel",
                        description = if (language == "fa") "اتصال به هاست سی‌پنل جهت همگام‌سازی تبلیغات و آمار" else "Sync remote configurations and view app installs stats",
                        icon = Icons.Filled.AdminPanelSettings,
                        iconColor = MaterialTheme.colorScheme.tertiary,
                        onClick = { selectedCategory = "cpanel" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 5: Security & Hidden Chats
                item {
                    CategoryItem(
                        title = if (language == "fa") "امنیت و گفتگوهای مخفی" else "Security & Hidden Chats",
                        description = if (language == "fa") "تعیین رمز عبور ۴ رقمی، اثر انگشت و مدیریت گفتگوهای مخفی شده" else "Set 4-digit PIN, fingerprint, and manage hidden conversations",
                        icon = Icons.Filled.Lock,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = { selectedCategory = "security" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 6: Contact Us & Ad Support
                item {
                    CategoryItem(
                        title = if (language == "fa") "ارتباط با ما و ثبت بازخورد" else "Contact Us & Feedback",
                        description = if (language == "fa") "پشتیبانی تلگرام، واتس‌اپ و تماس جهت بازخورد و ثبت تبلیغات" else "Telegram, WhatsApp, and call support for ads and feedback",
                        icon = Icons.Filled.ContactSupport,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = { selectedCategory = "contact" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }

                // Category 7: Saved Messages
                item {
                    CategoryItem(
                        title = if (language == "fa") "پیام‌های ذخیره شده" else "Saved Messages",
                        description = if (language == "fa") "مشاهده و مدیریت پیام‌های ذخیره شده به همراه فرستنده و زمان" else "View and manage saved messages with sender info and timestamp",
                        icon = Icons.Filled.Save,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = { selectedCategory = "saved_messages" },
                        language = language,
                        fontStyle = fontStyle,
                        fontSizeScale = fontSizeScale
                    )
                }
            }
        } else {
            // Sub-page view
            val categoryTitle = when (selectedCategory) {
                "appearance" -> if (language == "fa") "ظاهر و پوسته برنامه" else "Appearance & Themes"
                "spam" -> if (language == "fa") "فیلترینگ و ضدتبلیغ" else "Anti-Spam & Filters"
                "general" -> if (language == "fa") "تنظیمات عمومی و زبان" else "General Settings"
                "cpanel" -> if (language == "fa") "پنل مدیریت و اتصال به هاست" else "cPanel Host & Admin Panel"
                "security" -> if (language == "fa") "امنیت و گفتگوهای مخفی" else "Security & Hidden Chats"
                "contact" -> if (language == "fa") "ارتباط با ما و ثبت بازخورد" else "Contact Us & Feedback"
                "saved_messages" -> if (language == "fa") "پیام‌های ذخیره شده" else "Saved Messages"
                else -> ""
            }

            // Sub-page header with Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedCategory = null }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = categoryTitle,
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 20, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                when (selectedCategory) {
                    "appearance" -> {
                        // Theme selector card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = NirvanaLocal.get("theme", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    val themesList = listOf("lavender", "oceanic", "royal", "light", "dark")
                                    themesList.forEach { theme ->
                                        val themeTitle = when (theme) {
                                            "lavender" -> NirvanaLocal.get("theme_lavender", language)
                                            "oceanic" -> NirvanaLocal.get("theme_oceanic", language)
                                            "royal" -> NirvanaLocal.get("theme_royal", language)
                                            "light" -> NirvanaLocal.get("theme_light", language)
                                            else -> NirvanaLocal.get("theme_dark", language)
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.setTheme(theme) }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = themeTitle,
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14)
                                            )
                                            RadioButton(
                                                selected = currentTheme == theme,
                                                onClick = { viewModel.setTheme(theme) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Message Bubble Color Scheme Selection Card
                        item {
                            val currentBubbleScheme by viewModel.bubbleColorScheme.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (language == "fa") "رنگ‌بندی حباب‌های پیام" else "Message Bubble Colors",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (language == "fa") "ترکیب رنگ دلخواه برای پیام‌های ارسالی و دریافتی را انتخاب کنید" else "Choose your preferred color palette for sent & received bubbles",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    val schemes = listOf(
                                        Triple("default", if (language == "fa") "پیش‌فرض پوسته" else "Default Theme", Pair(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondaryContainer)),
                                        Triple("classic_blue", if (language == "fa") "آبی و طوسی کلاسیک" else "Classic Blue & Slate", Pair(Color(0xFF1976D2), Color(0xFFECEFF1))),
                                        Triple("emerald_mint", if (language == "fa") "زمردی و نعنایی" else "Emerald & Mint", Pair(Color(0xFF00897B), Color(0xFFE0F2F1))),
                                        Triple("purple_lavender", if (language == "fa") "بنفش و یاسی" else "Royal Purple & Lavender", Pair(Color(0xFF7B1FA2), Color(0xFFF3E5F5))),
                                        Triple("sunset_warm", if (language == "fa") "غروب خورشید (نارنجی و کرم)" else "Sunset Orange & Cream", Pair(Color(0xFFE65100), Color(0xFFFFF3E0))),
                                        Triple("modern_dark", if (language == "fa") "نیلی و دودی (مدرن)" else "Indigo & Dark Slate", Pair(Color(0xFF3F51B5), Color(0xFF37474F))),
                                        Triple("rose_pink", if (language == "fa") "رز و صورتی ملایم" else "Rose & Soft Pink", Pair(Color(0xFFD81B60), Color(0xFFFCE4EC)))
                                    )

                                    schemes.forEach { (key, title, colors) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.setBubbleColorScheme(key) }
                                                .padding(vertical = 8.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = colors.first,
                                                    modifier = Modifier.size(20.dp),
                                                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.15f))
                                                ) {}
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Surface(
                                                    shape = CircleShape,
                                                    color = colors.second,
                                                    modifier = Modifier.size(20.dp),
                                                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.15f))
                                                ) {}
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = title,
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Medium)
                                                )
                                            }
                                            RadioButton(
                                                selected = currentBubbleScheme == key,
                                                onClick = { viewModel.setBubbleColorScheme(key) }
                                            )
                                        }
                                    }

                                    // Live preview of both sent and received bubbles
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = if (language == "fa") "پیش‌نمایش زنده چت:" else "Live Chat Preview:",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    val (pSentBg, pSentText, pRecBg, pRecText) = when (currentBubbleScheme) {
                                        "classic_blue" -> listOf(Color(0xFF1976D2), Color.White, Color(0xFFECEFF1), Color(0xFF263238))
                                        "emerald_mint" -> listOf(Color(0xFF00897B), Color.White, Color(0xFFE0F2F1), Color(0xFF004D40))
                                        "purple_lavender" -> listOf(Color(0xFF7B1FA2), Color.White, Color(0xFFF3E5F5), Color(0xFF4A148C))
                                        "sunset_warm" -> listOf(Color(0xFFE65100), Color.White, Color(0xFFFFF3E0), Color(0xFF3E2723))
                                        "modern_dark" -> listOf(Color(0xFF3F51B5), Color.White, Color(0xFF37474F), Color.White)
                                        "rose_pink" -> listOf(Color(0xFFD81B60), Color.White, Color(0xFFFCE4EC), Color(0xFF880E4F))
                                        else -> listOf(
                                            MaterialTheme.colorScheme.primary,
                                            Color.White,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Received bubble preview
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            Surface(
                                                color = pRecBg,
                                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                                                shadowElevation = 1.dp
                                            ) {
                                                Text(
                                                    text = if (language == "fa") "سلام! این نمونه پیام دریافتی است." else "Hello! This is a received message preview.",
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                    color = pRecText,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                                )
                                            }
                                        }

                                        // Sent bubble preview
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            Surface(
                                                color = pSentBg,
                                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                                                shadowElevation = 1.dp
                                            ) {
                                                Text(
                                                    text = if (language == "fa") "عالیه! پیام ارسالی شما این شکلی میشه ✨" else "Great! Your sent message looks like this ✨",
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                    color = pSentText,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Font selection card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = NirvanaLocal.get("font", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    val fontsList = listOf("Default", "SansSerif", "Serif", "Monospace")
                                    fontsList.forEach { font ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.setFont(font) }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = font,
                                                style = NirvanaFont.getTextStyle(font, fontSizeScale, 14)
                                            )
                                            RadioButton(
                                                selected = fontStyle == font,
                                                onClick = { viewModel.setFont(font) }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "${NirvanaLocal.get("font_size", language)}: ${(fontSizeScale * 100).toInt()}%",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Medium)
                                    )
                                    Slider(
                                        value = fontSizeScale,
                                        onValueChange = { viewModel.setFontSize(it) },
                                        valueRange = 0.8f..1.4f,
                                        steps = 5
                                    )
                                }
                            }
                        }
                    }

                    "spam" -> {
                        // Spam Filter control panel card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = NirvanaLocal.get("spam_filter", language),
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                        )
                                        Switch(
                                            checked = autoSpam,
                                            onCheckedChange = { viewModel.toggleAutoSpamFilter(it) }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = NirvanaLocal.get("anti_advertising_explanation", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        // Spam rules builder card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = NirvanaLocal.get("add_rule", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Type Choice (Keyword vs Sender prefix)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { newRuleType = "KEYWORD" },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (newRuleType == "KEYWORD") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text(
                                                text = NirvanaLocal.get("keyword", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = if (newRuleType == "KEYWORD") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = { newRuleType = "SENDER" },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (newRuleType == "SENDER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text(
                                                text = NirvanaLocal.get("sender", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = if (newRuleType == "SENDER") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = newRulePattern,
                                        onValueChange = { newRulePattern = it },
                                        placeholder = {
                                            Text(
                                                text = NirvanaLocal.get("rule_pattern", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("rule_input_field"),
                                        shape = RoundedCornerShape(8.dp)
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Button(
                                        onClick = {
                                            viewModel.addNewSpamRule(newRulePattern, newRuleType, isBlacklist = true)
                                            newRulePattern = ""
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("add_rule_btn"),
                                        enabled = newRulePattern.isNotBlank()
                                    ) {
                                        Text(
                                            text = NirvanaLocal.get("add", language),
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(18.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = NirvanaLocal.get("custom_rules_title", language),
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold)
                                        )
                                        TextButton(onClick = { viewModel.resetToDefaultSpamRules() }) {
                                            Text(
                                                text = NirvanaLocal.get("reset_rules", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }

                                    // Render Custom Rules list inside settings
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    ) {
                                        customRules.take(20).forEach { rule ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = rule.pattern,
                                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                                    )
                                                    Text(
                                                        text = if (rule.type == "KEYWORD") "Keyword filter" else "Sender shortcode filter",
                                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                }
                                                IconButton(onClick = { viewModel.deleteSpamRule(rule) }) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Delete,
                                                        contentDescription = "Delete",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "general" -> {
                        // Language toggle card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (language == "fa") "زبان برنامه (Language)" else "App Language",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.setLanguage("fa") },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (language == "fa") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text(text = "فارسی", color = if (language == "fa") Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Button(
                                            onClick = { viewModel.setLanguage("en") },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (language == "en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text(text = "English", color = if (language == "en") Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // Show Message Preview Switch Card
                        item {
                            val showMessagePreviewInList by viewModel.showMessagePreviewInList.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (language == "fa") "نمایش متن پیام در لیست گفتگوها" else "Show Message Preview in List",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (language == "fa") "پیش‌نمایش متن پیام در زیر نام مخاطب نشان داده شود" else "Display message snippet preview under contact name",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Switch(
                                            checked = showMessagePreviewInList,
                                            onCheckedChange = { viewModel.setShowMessagePreviewInList(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Show Contact Name Switch Card
                        item {
                            val showContactNames by viewModel.showContactNames.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (language == "fa") "نمایش نام مخاطب" else "Display Contact Names",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (language == "fa") "در صورت غیرفعال بودن، فقط شماره تلفن در گفتگوها نمایش داده می‌شود" else "If disabled, only phone numbers will be shown",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Switch(
                                            checked = showContactNames,
                                            onCheckedChange = { viewModel.setShowContactNames(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Reply Option Toggle Switch Card
                        item {
                            val isSwipeToReplyEnabled by viewModel.isSwipeToReplyEnabled.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (language == "fa") "قابلیت ریپلای (پاسخ به پیام)" else "Enable Message Reply",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (language == "fa") "امکان ریپلای پیام با کشیدن یا انتخاب از منو" else "Allow replying to messages by swiping or menu option",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Switch(
                                            checked = isSwipeToReplyEnabled,
                                            onCheckedChange = { viewModel.setSwipeToReplyEnabled(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Pinch-to-Zoom Font Size Setting Card
                        item {
                            val isPinchZoomEnabled by viewModel.isPinchZoomEnabled.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (language == "fa") "تغییر اندازه فونت با دو انگشت (پینچ زوم)" else "Pinch to Zoom Font Size",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (language == "fa") "بزرگ‌نمایی و کوچک‌نمایی متن با حرکت دو انگشت روی لیست پیام‌ها و گفت‌وگوها" else "Zoom font size in/out by pinching on conversation or message lists",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Switch(
                                            checked = isPinchZoomEnabled,
                                            onCheckedChange = { viewModel.setPinchZoomEnabled(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Configurable Delayed Send Card
                        item {
                            val delayedSeconds by viewModel.delayedSendSeconds.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = NirvanaLocal.get("delayed_send_duration_title", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = NirvanaLocal.get("delayed_send_duration_desc", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Slider(
                                            value = delayedSeconds.toFloat(),
                                            onValueChange = { viewModel.setDelayedSendSeconds(it.toInt()) },
                                            valueRange = 0f..6f,
                                            steps = 5,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = if (delayedSeconds == 0) {
                                                if (language == "fa") "غیرفعال" else "Disabled"
                                            } else {
                                                "$delayedSeconds ${NirvanaLocal.get("seconds_unit", language)}"
                                            },
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(80.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        // Notification Content Privacy Toggle Card
                        item {
                            val hideNotificationContent by viewModel.hideNotificationContent.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = NirvanaLocal.get("hide_notification_content_title", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = NirvanaLocal.get("hide_notification_content_desc", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Switch(
                                            checked = hideNotificationContent,
                                            onCheckedChange = { viewModel.setHideNotificationContent(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Delivery Report Toggle Card
                        item {
                            val isDeliveryReportEnabled by viewModel.isDeliveryReportEnabled.collectAsState()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = NirvanaLocal.get("delivery_report_title", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = NirvanaLocal.get("delivery_report_desc", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Switch(
                                            checked = isDeliveryReportEnabled,
                                            onCheckedChange = { viewModel.setDeliveryReportEnabled(it) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "cpanel" -> {
                        // Admin Settings & CPanel Ad Host Configuration Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AdminPanelSettings,
                                            contentDescription = "Admin",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text = NirvanaLocal.get("admin_settings", language),
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Remote CPanel API Fetching URL Field
                                    Text(
                                        text = NirvanaLocal.get("promo_server_url", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = editPromoFetchUrl,
                                        onValueChange = { editPromoFetchUrl = it },
                                        placeholder = { Text("https://yourcpanel.com/api/ad.json") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Save / Fetch buttons row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.fetchPromoFromCpanel(editPromoFetchUrl)
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Filled.CloudDownload, contentDescription = "Fetch", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = NirvanaLocal.get("promo_fetch_btn", language),
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Edit Promo Text Textfield
                                    Text(
                                        text = NirvanaLocal.get("promo_text_label", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = editPromoText,
                                        onValueChange = { editPromoText = it },
                                        placeholder = { Text("Type banner message...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 2,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Edit Promo Click Link Textfield
                                    Text(
                                        text = NirvanaLocal.get("promo_url_label", language),
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = editPromoUrl,
                                        onValueChange = { editPromoUrl = it },
                                        placeholder = { Text("https://example.com/promo") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Save Local Settings Button
                                    Button(
                                        onClick = {
                                            viewModel.savePromoSettings(editPromoText, editPromoUrl, editPromoFetchUrl)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Filled.Save, contentDescription = "Save", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = NirvanaLocal.get("save", language),
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "security" -> {
                        // Card 1: Set or Change PIN
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (securePinState.isEmpty()) {
                                            if (language == "fa") "تنظیم رمز عبور جدید" else "Set New Passcode"
                                        } else {
                                            if (language == "fa") "تغییر رمز عبور" else "Change Passcode"
                                        },
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    if (securePinState.isNotEmpty()) {
                                        // Change existing PIN
                                        OutlinedTextField(
                                            value = inputPinOld,
                                            onValueChange = { if (it.length <= 4) inputPinOld = it },
                                            label = { Text(if (language == "fa") "رمز عبور فعلی" else "Current Passcode") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                            ),
                                            visualTransformation = if (isPasscodeVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    OutlinedTextField(
                                        value = inputPinNew,
                                        onValueChange = { if (it.length <= 4) inputPinNew = it },
                                        label = { 
                                            Text(
                                                if (securePinState.isEmpty()) {
                                                    if (language == "fa") "رمز عبور ۴ رقمی جدید" else "New 4-digit Passcode"
                                                } else {
                                                    if (language == "fa") "رمز عبور ۴ رقمی جدید" else "New 4-digit Passcode"
                                                }
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                        ),
                                        visualTransformation = if (isPasscodeVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        shape = RoundedCornerShape(8.dp)
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Show/Hide password checkbox/switch
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { isPasscodeVisible = !isPasscodeVisible }
                                        ) {
                                            Checkbox(
                                                checked = isPasscodeVisible,
                                                onCheckedChange = { isPasscodeVisible = it }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (language == "fa") "نمایش رمز" else "Show PIN",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11)
                                            )
                                        }

                                        val context = LocalContext.current
                                        Button(
                                            onClick = {
                                                if (inputPinNew.length != 4) {
                                                    Toast.makeText(context, if (language == "fa") "رمز عبور باید دقیقاً ۴ رقم باشد" else "Passcode must be exactly 4 digits", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    if (securePinState.isNotEmpty()) {
                                                        if (inputPinOld != securePinState) {
                                                            Toast.makeText(context, if (language == "fa") "رمز عبور فعلی نادرست است" else "Current passcode is incorrect", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            viewModel.updateSecurePin(inputPinNew)
                                                            inputPinOld = ""
                                                            inputPinNew = ""
                                                            Toast.makeText(context, if (language == "fa") "رمز عبور با موفقیت تغییر کرد" else "Passcode updated successfully", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        viewModel.updateSecurePin(inputPinNew)
                                                        inputPinNew = ""
                                                        Toast.makeText(context, if (language == "fa") "رمز عبور تعیین شد" else "Passcode configured successfully", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = if (language == "fa") "ذخیره" else "Save PIN",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Card 2: Biometrics
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (language == "fa") "استفاده از اثر انگشت" else "Use Fingerprint Biometrics",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (language == "fa") "فعال‌سازی حسگر اثر انگشت برای دسترسی سریع‌تر به چت‌ها" else "Unlock hidden chats instantly using fingerprint sensors",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = useBiometricState,
                                        onCheckedChange = { viewModel.updateUseBiometric(it) }
                                    )
                                }
                            }
                        }

                        // Card 3: Hidden Conversations
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = "Hidden Chats",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = if (language == "fa") "گفتگوهای مخفی شده" else "Hidden Conversations",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Campaign,
                                                contentDescription = "Info",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (language == "fa") "راهنما: با نگه داشتن دکمه پیام جدید به پیام‌های مخفی منتقل می‌شوید." else "Tip: By holding the New Message button, you will be redirected to hidden messages.",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    val context = LocalContext.current

                                    if (securePinState.isEmpty()) {
                                        Text(
                                            text = if (language == "fa") "برای مشاهده گفتگوهای مخفی ابتدا باید رمز عبور تنظیم کنید." else "Set a passcode first to view hidden conversations.",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else if (!isUnlocked) {
                                        // Not authenticated yet
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = if (language == "fa") "جهت دسترسی به گفتگوهای مخفی، قفل را باز کنید." else "Please authenticate to open hidden chats.",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )

                                            OutlinedTextField(
                                                value = unlockPinAttempt,
                                                onValueChange = { 
                                                    if (it.length <= 4) {
                                                        unlockPinAttempt = it
                                                        showUnlockError = false
                                                        if (it == securePinState) {
                                                            isUnlocked = true
                                                            unlockPinAttempt = ""
                                                        }
                                                    }
                                                },
                                                label = { Text(if (language == "fa") "رمز عبور ۴ رقمی" else "4-digit Passcode") },
                                                modifier = Modifier.fillMaxWidth(0.6f),
                                                singleLine = true,
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                                ),
                                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                                shape = RoundedCornerShape(8.dp)
                                            )

                                            if (showUnlockError) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = if (language == "fa") "رمز عبور اشتباه است" else "Incorrect passcode",
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Button(
                                                    onClick = {
                                                        if (unlockPinAttempt == securePinState) {
                                                            isUnlocked = true
                                                            unlockPinAttempt = ""
                                                        } else {
                                                            showUnlockError = true
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = if (language == "fa") "تایید رمز" else "Verify PIN",
                                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12)
                                                    )
                                                }

                                                if (useBiometricState) {
                                                    IconButton(
                                                        onClick = {
                                                            // Fingerprint quick simulation
                                                            Toast.makeText(context, if (language == "fa") "اثر انگشت با موفقیت تایید شد ✅" else "Fingerprint verified ✅", Toast.LENGTH_SHORT).show()
                                                            isUnlocked = true
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Fingerprint,
                                                            contentDescription = "Fingerprint Authentication",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(36.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Authenticated! Show list of hidden threads
                                        if (hiddenThreadsState.isEmpty()) {
                                            Text(
                                                text = if (language == "fa") "هیچ گفتگوی مخفی وجود ندارد." else "No hidden conversations found.",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                        } else {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                hiddenThreadsState.forEach { thread ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp)
                                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
                                                            .padding(8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = thread.senderName ?: thread.address,
                                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14, FontWeight.Bold)
                                                            )
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = thread.body,
                                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }

                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            // Unhide Button
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.unhideThread(thread.threadId)
                                                                    Toast.makeText(context, if (language == "fa") "گفتگو از حالت مخفی خارج شد" else "Conversation unhidden", Toast.LENGTH_SHORT).show()
                                                                }
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.LockOpen,
                                                                    contentDescription = "Unhide Chat",
                                                                    tint = MaterialTheme.colorScheme.primary
                                                                )
                                                            }

                                                            // Navigate/View Button
                                                            IconButton(
                                                                onClick = {
                                                                    onNavigateToThread?.invoke(thread)
                                                                }
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.ChatBubble,
                                                                    contentDescription = "View Chat",
                                                                    tint = MaterialTheme.colorScheme.secondary
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = { isUnlocked = false },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = if (language == "fa") "قفل کردن مجدد" else "Lock Again",
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "contact" -> {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (language == "fa") "ارتباط با پشتیبانی نیروانا" else "Contact Nirvana Support",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (language == "fa") "جهت ارسال بازخورد، گزارش خطا و یا ثبت تبلیغات در پیام‌رسان با ما در ارتباط باشید:" else "Get in touch for feedback, bug reports, or registering advertisements:",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))

                                    val context = LocalContext.current

                                    // Button 1: Phone Call
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:+989123456789"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Phone, contentDescription = "Call")
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (language == "fa") "تماس مستقیم با ما" else "Direct Phone Call",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Button 2: Telegram Chat
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/NirvanaSmsSupport"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Send, contentDescription = "Telegram", tint = Color.White)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (language == "fa") "چت در تلگرام (پشتیبانی و تبلیغات)" else "Chat on Telegram",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Button 3: WhatsApp Chat
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/989123456789"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Forum, contentDescription = "WhatsApp", tint = Color.White)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (language == "fa") "چت در واتس‌اپ" else "Chat on WhatsApp",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "saved_messages" -> {
                        if (savedMessagesState.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.Save,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = if (language == "fa") "هیچ پیام ذخیره شده‌ای وجود ندارد." else "No saved messages found.",
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(savedMessagesState) { savedMsg ->
                                val dateStr = if (language == "fa") {
                                    JalaliCalendar.getPersianDateString(savedMsg.savedAt)
                                } else {
                                    val sdf = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH)
                                    sdf.format(java.util.Date(savedMsg.savedAt))
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        // Sender and saved time header
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Filled.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = savedMsg.senderName ?: savedMsg.sender,
                                                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                if (savedMsg.senderName != null) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "(${savedMsg.sender})",
                                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Text(
                                                text = dateStr,
                                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Message Body
                                        Text(
                                            text = savedMsg.body,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Action buttons (Forward and Delete)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    forwardSavedText = savedMsg.body
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Share,
                                                    contentDescription = "Forward Message",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.deleteSavedMessage(savedMsg.id)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Delete Saved Message",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryItem(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = iconColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 15, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 16.sp
                )
            }

            // Arrow icon indicating clickability
            Icon(
                imageVector = if (language == "fa") Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
                contentDescription = "Go",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun NirvanaNewChatDialog(
    viewModel: NirvanaViewModel,
    initialPhoneNumber: String = "",
    initialMessageBody: String = "",
    onDismiss: () -> Unit,
    onStartChat: (String, String, Int?) -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    var phoneInput by remember(initialPhoneNumber) { mutableStateOf(initialPhoneNumber) }
    var initialMessage by remember(initialMessageBody) { mutableStateOf(initialMessageBody) }
    var selectedContacts by remember { mutableStateOf<Set<NirvanaContact>>(emptySet()) }

    val contacts by viewModel.contacts.collectAsState()
    val hiddenPhoneNumbers by viewModel.hiddenPhoneNumbers.collectAsState()
    val activeSims by viewModel.activeSims.collectAsState()
    var selectedSim by remember { mutableStateOf<com.example.data.repository.SimInfo?>(null) }

    LaunchedEffect(activeSims) {
        if (activeSims.isNotEmpty() && selectedSim == null) {
            selectedSim = activeSims.first()
        }
    }

    val filteredContacts = remember(contacts, phoneInput, hiddenPhoneNumbers) {
        val visibleContacts = contacts.filter { !hiddenPhoneNumbers.contains(it.phoneNumber) }
        if (phoneInput.isBlank()) {
            visibleContacts
        } else {
            visibleContacts.filter {
                it.name.contains(phoneInput, ignoreCase = true) ||
                it.phoneNumber.contains(phoneInput)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("new_chat_dialog"),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = NirvanaLocal.get("new_chat", language),
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Display selected contacts scrollable list
                if (selectedContacts.isNotEmpty()) {
                    Text(
                        text = if (language == "fa") "گیرندگان انتخاب شده:" else "Selected recipients:",
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(selectedContacts.toList()) { contact ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = contact.name,
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    IconButton(
                                        onClick = { selectedContacts = selectedContacts - contact },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Display manual entry field
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        placeholder = {
                            Text(
                                text = NirvanaLocal.get("recipient", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_chat_recipient_field"),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (language == "fa") "انتخاب مخاطبین (امکان انتخاب چند مخاطب)" else "Select contacts (multi-select)",
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(vertical = 4.dp)
                )

                // Render contact list suggestions matching phoneInput
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        if (filteredContacts.isNotEmpty()) {
                            items(filteredContacts) { contact ->
                                val isSelected = selectedContacts.any { it.phoneNumber == contact.phoneNumber }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSelected) {
                                                selectedContacts = selectedContacts.filter { it.phoneNumber != contact.phoneNumber }.toSet()
                                            } else {
                                                selectedContacts = selectedContacts + contact
                                                phoneInput = ""
                                            }
                                        }
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Person,
                                        contentDescription = "Contact",
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                        )
                                        Text(
                                            text = contact.phoneNumber,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        } else if (phoneInput.isBlank()) {
                            item {
                                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (language == "fa") "مخاطبی یافت نشد" else "No contacts found",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = initialMessage,
                        onValueChange = { initialMessage = it },
                        placeholder = {
                            Text(
                                text = NirvanaLocal.get("type_msg", language),
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 14)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("new_chat_body_field"),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 3
                    )

                    val (newChatSegments, newChatRemaining) = remember(initialMessage) {
                        if (initialMessage.isEmpty()) {
                            Pair(1, 70)
                        } else {
                            val isGsm = initialMessage.all { it.code <= 127 }
                            val singleLimit = if (isGsm) 160 else 70
                            val multiLimit = if (isGsm) 153 else 67
                            val len = initialMessage.length
                            if (len <= singleLimit) {
                                Pair(1, singleLimit - len)
                            } else {
                                val seg = ((len - 1) / multiLimit) + 1
                                val rem = (seg * multiLimit) - len
                                Pair(seg, rem)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "$newChatSegments/$newChatRemaining",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dual SIM selector if multiple SIMs are detected
                if (activeSims.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${NirvanaLocal.get("sim_select", language)}:",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        activeSims.forEach { sim ->
                            FilterChip(
                                selected = selectedSim?.id == sim.id,
                                onClick = { selectedSim = sim },
                                label = {
                                    Text(
                                        text = "SIM ${sim.slotIndex + 1} (${sim.carrierName})",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Medium)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (language == "fa") "انصراف" else "Cancel",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    }
                    Button(
                        onClick = {
                            val selectedNums = selectedContacts.map { it.phoneNumber }
                            val typedNums = phoneInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val recipients = (selectedNums + typedNums).distinct().joinToString(",")
                            onStartChat(recipients, initialMessage, selectedSim?.id)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_chat_confirm_btn"),
                        enabled = phoneInput.isNotBlank() || selectedContacts.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (initialMessage.isBlank()) {
                                NirvanaLocal.get("start_chat", language)
                            } else {
                                NirvanaLocal.get("send", language)
                            },
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NirvanaForwardMessageDialog(
    viewModel: NirvanaViewModel,
    messageText: String,
    onDismiss: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    var searchQuery by remember { mutableStateOf("") }
    val contacts by viewModel.contacts.collectAsState()
    val hiddenPhoneNumbers by viewModel.hiddenPhoneNumbers.collectAsState()
    
    val activeSims by viewModel.activeSims.collectAsState()
    var selectedSim by remember { mutableStateOf<com.example.data.repository.SimInfo?>(null) }
    LaunchedEffect(activeSims) {
        if (activeSims.isNotEmpty() && selectedSim == null) {
            selectedSim = activeSims.first()
        }
    }

    val filteredContacts = remember(contacts, searchQuery, hiddenPhoneNumbers) {
        val visibleContacts = contacts.filter { !hiddenPhoneNumbers.contains(it.phoneNumber) }
        if (searchQuery.isBlank()) {
            visibleContacts
        } else {
            visibleContacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery)
            }
        }
    }

    var selectedContactForForward by remember { mutableStateOf<NirvanaContact?>(null) }
    var manualNumberInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (language == "fa") "هدایت پیام (فوروارد)" else "Forward Message",
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 18, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (messageText.length > 100) messageText.take(100) + "..." else messageText,
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        manualNumberInput = it
                    },
                    placeholder = {
                        Text(
                            text = if (language == "fa") "نام یا شماره تماس..." else "Name or phone number...",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (language == "fa") "انتخاب از مخاطبین:" else "Select Contact:",
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (filteredContacts.isEmpty() && manualNumberInput.isBlank()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (language == "fa") "مخاطبی یافت نشد." else "No contacts found.",
                                style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 12),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(6.dp)
                        ) {
                            if (manualNumberInput.isNotBlank() && manualNumberInput.all { it.isDigit() || it == '+' }) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedContactForForward = NirvanaContact(manualNumberInput, manualNumberInput)
                                            }
                                            .background(
                                                if (selectedContactForForward?.phoneNumber == manualNumberInput)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Send,
                                            contentDescription = "Send",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = (if (language == "fa") "ارسال مستقیم به: " else "Send directly to: ") + manualNumberInput,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                        )
                                    }
                                }
                            }

                            items(filteredContacts) { contact ->
                                val isSelected = selectedContactForForward?.phoneNumber == contact.phoneNumber
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedContactForForward = contact
                                        }
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary 
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), 
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.name.take(1).uppercase(),
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.primary,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = contact.name,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = contact.phoneNumber,
                                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeSims.size > 1) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${NirvanaLocal.get("sim_select", language)}:",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11, FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        activeSims.forEach { sim ->
                            FilterChip(
                                selected = selectedSim?.id == sim.id,
                                onClick = { selectedSim = sim },
                                label = {
                                    Text(
                                        text = "SIM ${sim.slotIndex + 1} (${sim.carrierName})",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10, FontWeight.Medium)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                val context = LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (language == "fa") "انصراف" else "Cancel",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    }
                    Button(
                        onClick = {
                            val contact = selectedContactForForward
                            if (contact != null) {
                                viewModel.sendSmsMessage(
                                    address = contact.phoneNumber,
                                    body = messageText,
                                    threadId = null,
                                    subId = selectedSim?.id
                                )
                                Toast.makeText(
                                    context,
                                    if (language == "fa") "پیام با موفقیت هدایت (فوروارد) شد به: ${contact.name}" 
                                    else "Message forwarded successfully to: ${contact.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                            }
                        },
                        enabled = selectedContactForForward != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (language == "fa") "ارسال" else "Forward",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

fun toPersianDigits(text: String): String {
    val persianChars = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    val englishChars = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    var result = text
    for (i in 0..9) {
        result = result.replace(englishChars[i], persianChars[i])
    }
    return result
}

fun formatTimestamp(timestamp: Long, language: String): String {
    return try {
        val date = Date(timestamp)
        val format = if (language == "fa") "HH:mm" else "hh:mm a"
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        val formatted = sdf.format(date)
        if (language == "fa") toPersianDigits(formatted) else formatted
    } catch (e: Exception) {
        ""
    }
}

data class BankInfo(
    val name: String,
    val shortName: String,
    val color: Color,
    val textColor: Color = Color.White
)

fun detectBank(address: String, name: String?): BankInfo? {
    val cleanAddress = address.uppercase(Locale.ROOT)
    val cleanName = (name ?: "").uppercase(Locale.ROOT)
    
    return when {
        cleanAddress.contains("MELLI") || cleanName.contains("MELLI") || cleanName.contains("ملی") -> 
            BankInfo("بانک ملی", "ملی", Color(0xFF0F3B84))
        
        cleanAddress.contains("MELLAT") || cleanName.contains("MELLAT") || cleanName.contains("ملت") -> 
            BankInfo("بانک ملت", "ملت", Color(0xFFE10613))
            
        cleanAddress.contains("SADERAT") || cleanName.contains("SADERAT") || cleanName.contains("صادرات") -> 
            BankInfo("بانک صادرات", "صادرات", Color(0xFF153F77))
            
        cleanAddress.contains("TEJARAT") || cleanName.contains("TEJARAT") || cleanName.contains("تجارت") -> 
            BankInfo("بانک تجارت", "تجارت", Color(0xFF00649F))
            
        cleanAddress.contains("SEPAH") || cleanName.contains("SEPAH") || cleanName.contains("سپه") -> 
            BankInfo("بانک سپه", "سپه", Color(0xFF0D5E30))
            
        cleanAddress.contains("BLUE") || cleanName.contains("BLUE") || cleanName.contains("بلو") -> 
            BankInfo("بلوبانک", "بلو", Color(0xFF0066FF))
            
        cleanAddress.contains("KESHAVARZI") || cleanName.contains("KESHAVARZI") || cleanName.contains("کشاورزی") -> 
            BankInfo("بانک کشاورزی", "کشاورز", Color(0xFF2E7D32))
            
        cleanAddress.contains("PASARGAD") || cleanName.contains("PASARGAD") || cleanName.contains("پاسارگاد") -> 
            BankInfo("بانک پاسارگاد", "پاسار", Color(0xFF2C3E50), Color(0xFFFFD600))
            
        cleanAddress.contains("PARSIAN") || cleanName.contains("PARSIAN") || cleanName.contains("پارسیان") -> 
            BankInfo("بانک پارسیان", "پارسی", Color(0xFF880E4F))
            
        cleanAddress.contains("SAMAN") || cleanName.contains("SAMAN") || cleanName.contains("سامان") -> 
            BankInfo("بانک سامان", "سامان", Color(0xFF0288D1))
            
        cleanAddress.contains("MASKAN") || cleanName.contains("MASKAN") || cleanName.contains("مسکن") -> 
            BankInfo("بانک مسکن", "مسکن", Color(0xFFE65100))
            
        cleanAddress.contains("REFAH") || cleanName.contains("REFAH") || cleanName.contains("رفاه") -> 
            BankInfo("بانک رفاه", "رفاه", Color(0xFF002266))
            
        cleanAddress.contains("SHAHR") || cleanName.contains("SHAHR") || cleanName.contains("شهر") -> 
            BankInfo("بانک شهر", "شهر", Color(0xFFC2185B))

        cleanAddress.contains("RESALAT") || cleanName.contains("RESALAT") || cleanName.contains("رسالت") || cleanName.contains("قرض الحسنه رسالت") -> 
            BankInfo("بانک رسالت", "رسالت", Color(0xFF004D40), Color.White)

        // Mobile Operators and other Official Brands:
        cleanAddress.contains("IRANCELL") || cleanName.contains("IRANCELL") || cleanName.contains("ایرانسل") ->
            BankInfo("ایرانسل", "MTN", Color(0xFFFDCB02), Color.Black)
            
        cleanAddress.contains("MCI") || cleanAddress.contains("HAMRAH") || cleanName.contains("HAMRAH") || cleanName.contains("همراه") || cleanAddress.contains("TAMA") ->
            BankInfo("همراه اول", "MCI", Color(0xFF00C5CD))
            
        cleanAddress.contains("RIGHTEL") || cleanName.contains("RIGHTEL") || cleanName.contains("رایتل") ->
            BankInfo("رایتل", "Righ", Color(0xFF9E1B61))
            
        cleanAddress.contains("ADLIRAN") || cleanName.contains("ADLIRAN") || cleanName.contains("عدل ایران") ->
            BankInfo("عدلیران", "عدل", Color(0xFF3F51B5))
            
        cleanAddress.contains("POLICE") || cleanName.contains("POLICE") || cleanName.contains("پلیس") || cleanAddress.contains("NAJA") ->
            BankInfo("پلیس", "پلیس", Color(0xFF1B5E20))
            
        else -> null
    }
}

object JalaliCalendar {
    fun g2j(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val g_day_no = 365 * (gy - 1600) + (if (gm > 2) (gy - 1600 + 3) / 4 else (gy - 1600 + 2) / 4) - (gy - 1600 + 99) / 100 + (gy - 1600 + 399) / 400
        val g_d_m = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 335)
        var totalDays = g_day_no + g_d_m[gm - 1] + gd - 1
        var j_day_no = totalDays - 79
        val j_np = j_day_no / 12053
        j_day_no %= 12053
        var jy = 979 + 33 * j_np + 4 * (j_day_no / 1461)
        j_day_no %= 1461
        if (j_day_no >= 366) {
            jy += (j_day_no - 1) / 365
            j_day_no = (j_day_no - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (j_day_no < 186) {
            jm = 1 + (j_day_no / 31)
            jd = 1 + (j_day_no % 31)
        } else {
            jm = 7 + ((j_day_no - 186) / 30)
            jd = 1 + ((j_day_no - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }

    fun getPersianDateString(timestamp: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        val gy = cal.get(java.util.Calendar.YEAR)
        val gm = cal.get(java.util.Calendar.MONTH) + 1
        val gd = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val (jy, jm, jd) = g2j(gy, gm, gd)
        val monthNames = arrayOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
        )
        return "$jd ${monthNames[jm - 1]} $jy"
    }
}

fun formatDetailedTimestamp(timestamp: Long, language: String): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = timestamp
    val hour = String.format("%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))
    val minute = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
    val timeStr = "$hour:$minute"
    
    if (language == "fa") {
        val persianDate = JalaliCalendar.getPersianDateString(timestamp)
        val result = "$persianDate - ساعت $timeStr"
        return toPersianDigits(result)
    } else {
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH)
        return "${sdf.format(Date(timestamp))} - $timeStr"
    }
}

@Composable
fun NirvanaAddHiddenContactDialog(
    viewModel: NirvanaViewModel,
    onDismiss: () -> Unit,
    language: String,
    fontStyle: String,
    fontSizeScale: Float
) {
    var searchQuery by remember { mutableStateOf("") }
    val contacts by viewModel.contacts.collectAsState()
    val hiddenPhoneNumbers by viewModel.hiddenPhoneNumbers.collectAsState()

    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (language == "fa") "مخفی کردن مخاطب جدید" else "Hide New Contact",
                    style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 16, FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = if (language == "fa") "نام یا شماره تماس..." else "Name or phone number...",
                            style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        if (searchQuery.isNotBlank() && searchQuery.all { it.isDigit() || it == '+' }) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.addHiddenPhoneNumber(searchQuery)
                                            onDismiss()
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = (if (language == "fa") "مخفی کردن شماره: " else "Hide Number: ") + searchQuery,
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                    )
                                }
                            }
                        }

                        items(filteredContacts) { contact ->
                            val alreadyHidden = hiddenPhoneNumbers.contains(contact.phoneNumber)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !alreadyHidden) {
                                        viewModel.addHiddenPhoneNumber(contact.phoneNumber)
                                        onDismiss()
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (alreadyHidden) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.name.take(1).uppercase(),
                                        color = if (alreadyHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold),
                                        color = if (alreadyHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = contact.phoneNumber,
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 11),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                if (alreadyHidden) {
                                    Text(
                                        text = if (language == "fa") "مخفی شده" else "Hidden",
                                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 10),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Add",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (language == "fa") "بستن" else "Close",
                        style = NirvanaFont.getTextStyle(fontStyle, fontSizeScale, 13, FontWeight.Bold)
                    )
                }
            }
        }
    }
}
