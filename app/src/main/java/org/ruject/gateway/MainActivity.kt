package org.ruject.gateway

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import org.ruject.gateway.data.database.ClipboardHistory
import org.ruject.gateway.data.database.LogEntity
import org.ruject.gateway.data.database.NotificationHistory
import org.ruject.gateway.data.repository.GatewayRepository
import org.ruject.gateway.services.GatewayAccessibilityService
import org.ruject.gateway.services.GatewayNotificationListenerService
import org.ruject.gateway.services.GatewayService
import org.ruject.gateway.tools.ToolRegistry
import org.ruject.gateway.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.text.toString
import kotlin.toString


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CyberBackground
                ) {
                    MainScreen()
                }
            }
        }
    }
}

class MainViewModel(context: Context) : ViewModel() {
    private val repository = GatewayRepository(context.applicationContext)

    val logs: Flow<List<LogEntity>> = repository.logs
    val clipboardHistory: Flow<List<ClipboardHistory>> = repository.clipboardHistory
    val notificationHistory: Flow<List<NotificationHistory>> = repository.notificationHistory

    var portState = mutableStateOf("8080")
    var allowExternalState = mutableStateOf(false)
    var isServerActive = mutableStateOf(false)
    var authToken = mutableStateOf("")

    init {
        viewModelScope.launch {
            portState.value = repository.getServerPort().toString()
            allowExternalState.value = repository.getAllowExternal()
            isServerActive.value = repository.getServerActive()
            authToken.value = repository.getAuthToken()
        }
    }

    fun toggleServer(context: Context) {
        viewModelScope.launch {
            val active = !isServerActive.value
            isServerActive.value = active
            repository.setServerActive(active)

            val intent = Intent(context, GatewayService::class.java)
            if (active) {
                // Save configurations first
                val p = portState.value.toIntOrNull() ?: 8080
                repository.setServerPort(p)
                repository.setAllowExternal(allowExternalState.value)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }

    fun handleExternalToggle(allow: Boolean) {
        allowExternalState.value = allow
        viewModelScope.launch {
            repository.setAllowExternal(allow)
            // If server is active, restart to apply changes
        }
    }

    fun updatePort(port: String) {
        portState.value = port
        viewModelScope.launch {
            port.toIntOrNull()?.let {
                repository.setServerPort(it)
            }
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            val newToken = UUID.randomUUID().toString().substring(0, 16)
            repository.setAuthToken(newToken)
            authToken.value = newToken
        }
    }

    fun clearLogs() = viewModelScope.launch { repository.clearLogs() }
    fun clearClipboard() = viewModelScope.launch { repository.clearClipboard() }
    fun clearNotifications() = viewModelScope.launch { repository.clearNotifications() }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel { MainViewModel(context) }

    val logs by viewModel.logs.collectAsState(initial = emptyList())
    val clipboard by viewModel.clipboardHistory.collectAsState(initial = emptyList())
    val notificationsInput by viewModel.notificationHistory.collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(0) }

    // Test terminal states
    var terminalCategory by remember { mutableStateOf("deviceinfo") }
    var terminalAction by remember { mutableStateOf("read") }
    var terminalParams by remember { mutableStateOf("{}") }
    var terminalResult by remember { mutableStateOf("") }

    val permissionsList = remember {
        mutableStateListOf(
            PermissionItem(Manifest.permission.READ_CONTACTS, "Contacts (Read/Search)", false),
            PermissionItem(Manifest.permission.WRITE_CONTACTS, "Contacts (Write/Edit)", false),
            PermissionItem(Manifest.permission.READ_CALENDAR, "Calendar (Read/Search)", false),
            PermissionItem(Manifest.permission.WRITE_CALENDAR, "Calendar (Write/Edit)", false),
            PermissionItem(Manifest.permission.SEND_SMS, "SMS (Send Telephony)", false),
            PermissionItem(Manifest.permission.READ_SMS, "SMS (Read Inbox)", false),
            PermissionItem(Manifest.permission.READ_CALL_LOG, "Call logs (Review Phone)", false),
            PermissionItem(Manifest.permission.READ_PHONE_STATE, "Phone State", false),
            PermissionItem("USAGE_STATS", "Usage Statistics", false),
            PermissionItem("NOTIFICATION_LISTENER", "Notification Listener", false),
            PermissionItem("ACCESSIBILITY", "Accessibility Tree Dump", false)
        )
    }

    // Refresh state of permissions
    fun checkPermissions() {
        permissionsList.forEachIndexed { idx, item ->
            val granted = when (item.id) {
                "USAGE_STATS" -> {
                    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    if (appOps != null) {
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            appOps.unsafeCheckOpNoThrow(
                                AppOpsManager.OPSTR_GET_USAGE_STATS,
                                Process.myUid(),
                                context.packageName
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            appOps.checkOpNoThrow(
                                AppOpsManager.OPSTR_GET_USAGE_STATS,
                                Process.myUid(),
                                context.packageName
                            )
                        }
                        mode == AppOpsManager.MODE_ALLOWED
                    } else false
                }
                "NOTIFICATION_LISTENER" -> {
                    GatewayNotificationListenerService.instance != null
                }
                "ACCESSIBILITY" -> {
                    GatewayAccessibilityService.instance != null
                }
                else -> {
                    ContextCompat.checkSelfPermission(context, item.id) == PackageManager.PERMISSION_GRANTED
                }
            }
            permissionsList[idx] = item.copy(granted = granted)
        }
    }

    LaunchedEffect(Unit) {
        checkPermissions()
    }

    // Standard edge padding
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "GATEWAY SYSTEM BRIDGE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TechCyan,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = CyberBackground
                )
            )
        },
        containerColor = CyberBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Service Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("service_status_card"),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(CyberSurfaceElevated)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "IPC Engine Host",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (viewModel.isServerActive.value) "Active & Hosting" else "Stopped",
                                    fontSize = 12.sp,
                                    color = if (viewModel.isServerActive.value) TechGreen else TextSecondary
                                )
                            }
                            Switch(
                                checked = viewModel.isServerActive.value,
                                onCheckedChange = { viewModel.toggleServer(context) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberBackground,
                                    checkedTrackColor = TechCyan,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = CyberSurfaceElevated
                                ),
                                modifier = Modifier.testTag("server_toggle")
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Configuration inputs
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = viewModel.portState.value,
                                onValueChange = { viewModel.updatePort(it) },
                                label = { Text("Port", color = TextSecondary) },
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f).testTag("port_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonBlue,
                                    unfocusedBorderColor = CyberSurfaceElevated
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = viewModel.allowExternalState.value,
                                        onCheckedChange = { viewModel.handleExternalToggle(it) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = TechCyan,
                                            checkmarkColor = CyberBackground
                                        )
                                    )
                                    Text(
                                        text = "Allow WAN",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = if (viewModel.allowExternalState.value) "Listen on all IPs (WAN)" else "Local Only (127.0.0.1)",
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bearer Security Token Code element
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                            color = CyberBackground
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text("Authentication Token", fontSize = 10.sp, color = TextSecondary)
                                    Text(
                                        text = viewModel.authToken.value,
                                        fontFamily = FontFamily.Monospace,
                                        color = TechCyan,
                                        fontSize = 14.sp
                                    )
                                }
                                Row {
                                    IconButton(onClick = { viewModel.regenerateToken() }) {
                                        Icon(Icons.Default.Refresh, "Regenerate", tint = TextPrimary)
                                    }
                                    val clip = LocalClipboardManager.current
                                    IconButton(onClick = {
                                        clip.setText(buildAnnotatedString { append(viewModel.authToken.value) })
                                        Toast.makeText(context, "Token Copied", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, "Copy", tint = TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // System Permissions Control Dashboard
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(CyberSurfaceElevated)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "System Permissions",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 16.sp
                            )
                            IconButton(onClick = { checkPermissions() }) {
                                Icon(Icons.Default.Refresh, "Refresh permissions status", tint = TechCyan)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = CyberSurfaceElevated)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            permissionsList.forEach { item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (item.granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (item.granted) TechGreen else TechAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.label,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                    }

                                    if (!item.granted) {
                                        Button(
                                            onClick = {
                                                when (item.id) {
                                                    "USAGE_STATS" -> {
                                                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    }
                                                    "NOTIFICATION_LISTENER" -> {
                                                        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    }
                                                    "ACCESSIBILITY" -> {
                                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    }
                                                    else -> {
                                                        ActivityCompat.requestPermissions(
                                                            context as ComponentActivity,
                                                            arrayOf(item.id),
                                                            200
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceElevated)
                                        ) {
                                            Text("Grant", fontSize = 11.sp, color = TechCyan)
                                        }
                                    } else {
                                        Text("Granted", fontSize = 11.sp, color = TechGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Console Tools and System Output Sniffers
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(CyberSurfaceElevated)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Interactive Terminal Logs",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Tab headers
                        ScrollableTabRow(
                            selectedTabIndex = activeTab,
                            containerColor = CyberBackground,
                            contentColor = TechCyan,
                            edgePadding = 0.dp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        ) {
                            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                                Text("API Logs", modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                            }
                            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                                Text("Clipboard", modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                            }
                            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                                Text("Notifications", modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                            }
                            Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                                Text("Sandbox Execute", modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (activeTab) {
                            0 -> { // API Logs LIST
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Recent Requests (HTTP/WS)", fontSize = 11.sp, color = TextSecondary)
                                    Text(
                                        "Clear",
                                        color = TechRed,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable { viewModel.clearLogs() }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (logs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(150.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("[ No logged API requests. Use rest tools context. ]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(250.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        logs.forEach { log ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CyberBackground)
                                                    .padding(8.dp)
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = log.apiMethod,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = if (log.status == "SUCCESS") TechCyan else TechRed,
                                                        fontSize = 12.sp
                                                    )
                                                    Text(
                                                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)),
                                                        fontSize = 10.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "In: ${log.requestPayload}",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = TextSecondary
                                                )
                                                Text(
                                                    text = "Out: ${log.responsePayload}",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = TextPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> { // Clipboard Historian
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Monitored Clipboard Changes", fontSize = 11.sp, color = TextSecondary)
                                    Text(
                                        "Clear",
                                        color = TechRed,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable { viewModel.clearClipboard() }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (clipboard.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(150.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("[ No clipboard modifications detected. ]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(250.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        clipboard.forEach { clip ->
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CyberBackground)
                                                    .padding(8.dp)
                                            ) {
                                                Text(clip.text, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1.0f))
                                                Text(
                                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(clip.timestamp)),
                                                    fontSize = 10.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> { // Notifications Sniffer
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Incoming System Notifications Capture", fontSize = 11.sp, color = TextSecondary)
                                    Text(
                                        "Clear",
                                        color = TechRed,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable { viewModel.clearNotifications() }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (notificationsInput.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(150.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("[ Notification listener empty or not running. ]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(250.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        notificationsInput.forEach { notif ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CyberBackground)
                                                    .padding(8.dp)
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(notif.packageName, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = TechCyan)
                                                    Text(
                                                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(notif.timestamp)),
                                                        fontSize = 10.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                                Text(notif.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                                                Text(notif.text, fontSize = 12.sp, color = TextSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                            3 -> { // Sandbox Execution
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = terminalCategory,
                                            onValueChange = { terminalCategory = it },
                                            label = { Text("Category", fontSize = 11.sp, color = TextSecondary) },
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 13.sp),
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TechCyan, unfocusedBorderColor = CyberSurfaceElevated)
                                        )
                                        OutlinedTextField(
                                            value = terminalAction,
                                            onValueChange = { terminalAction = it },
                                            label = { Text("Action", fontSize = 11.sp, color = TextSecondary) },
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 13.sp),
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TechCyan, unfocusedBorderColor = CyberSurfaceElevated)
                                        )
                                    }

                                    OutlinedTextField(
                                        value = terminalParams,
                                        onValueChange = { terminalParams = it },
                                        label = { Text("Parameters JSON (Map)", fontSize = 11.sp, color = TextSecondary) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 12.sp),
                                        modifier = Modifier.fillMaxWidth().height(70.dp),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TechCyan, unfocusedBorderColor = CyberSurfaceElevated)
                                    )

                                    Button(
                                        onClick = {
                                            try {
                                                val json = JSONObject(terminalParams)
                                                val paramsMap = mutableMapOf<String, Any?>()
                                                val keys = json.keys()
                                                while (keys.hasNext()) {
                                                    val k = keys.next()
                                                    paramsMap[k] = json.get(k)
                                                }
                                                val res = ToolRegistry.execute(context, terminalCategory, terminalAction, paramsMap)
                                                terminalResult = res.toString(2)
                                            } catch (e: Exception) {
                                                terminalResult = "Execution error: ${e.localizedMessage}"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TechCyan),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Run Action on Local JVM", color = CyberBackground, fontWeight = FontWeight.Bold)
                                    }

                                    if (terminalResult.isNotBlank()) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)),
                                            color = CyberBackground
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                                                Text(
                                                    text = terminalResult,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = TextPrimary,
                                                    fontSize = 11.sp
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

data class PermissionItem(
    val id: String,
    val label: String,
    val granted: Boolean
)
