package com.example.areyoualive

import android.os.Bundle
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextFieldDefaults
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 主Activity - 使用 Jetpack Compose 构建 UI
 */
class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val manager = UserActivityManager(this)
        
        // 启动前台服务以持续监听解锁事件
        // (前台服务可以在应用退到后台后继续运行)
        if (manager.isEnabled) {
            startKeepAliveService()
        }
        
        setContent {
            AreYouAliveApp(manager = manager, onStartMonitoring = {
                // 启动后台监测任务
                val workRequest = PeriodicWorkRequestBuilder<CheckAliveWorker>(AppConfig.CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "LifeCheck",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
                // 同时启动前台服务
                startKeepAliveService()
            })
        }
    }
    
    private fun startKeepAliveService() {
        val serviceIntent = android.content.Intent(this, KeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreYouAliveApp(manager: UserActivityManager, onStartMonitoring: () -> Unit) {
    val navController = rememberNavController()
    
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC5),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        )
    ) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    manager = manager,
                    onNavigateToSettings = { navController.navigate("settings") },
                    onStartMonitoring = onStartMonitoring
                )
            }
            composable("settings") {
                SettingsScreen(
                    manager = manager,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * 主页 - 显示状态和快速操作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    manager: UserActivityManager,
    onNavigateToSettings: () -> Unit,
    onStartMonitoring: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // 监听生命周期，当用户从设置页面返回时自动刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // === 权限检查与申请 ===
    val permissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 权限请求回调，这里不需要做特殊处理，状态会通过 UI 刷新反映
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        
        // 1. 通知权限 (Android 13+) - 用于前台服务通知和报警
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // 2. 运动识别权限 (Android 10+) - 用于步数检测
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    var isEnabled by remember { mutableStateOf(manager.isEnabled) }
    var lastActiveTime by remember { mutableStateOf(manager.getLastActiveTime()) }

    // 前台实时刷新：只要应用在前台且开启监测，就持续更新本地活跃时间并刷新 UI
    LaunchedEffect(isEnabled) {
        if (isEnabled) {
            while (true) {
                manager.updateLastActiveTime() // 更新本地 SharedPreferences
                lastActiveTime = manager.getLastActiveTime() // 触发 UI 重绘
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // 使用 remember(refreshTrigger) 确保状态在返回时更新
    val isIgnoringBattery = remember(refreshTrigger) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val lastActiveStr = sdf.format(Date(lastActiveTime))
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活着吗", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isEnabled) Color(0xFF1B5E20) else Color(0xFF424242)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isEnabled) "监测中" else "未启用",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "上次活跃: $lastActiveStr",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // 电池优化检查卡片
            if (!isIgnoringBattery) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "请开启“允许后台运行”，否则监测可能因系统休眠而中断。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开设置，请手动在手机设置中开启", Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Text("去开启", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 功能按钮
            val scope = rememberCoroutineScope()
            var isValidating by remember { mutableStateOf(false) }

            Button(
                onClick = {
                    val name = manager.userName
                    val email = manager.emergencyEmail
                    val url = manager.serverUrl
                    
                    if (name.isBlank() || email.isBlank()) {
                        Toast.makeText(context, "请先在设置中完善个人姓名和紧急联系人邮箱", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    
                    if (url.isBlank()) {
                        Toast.makeText(context, "请先在设置中配置服务器地址", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    isValidating = true
                    scope.launch {
                        try {
                            val api = com.example.areyoualive.data.api.RetrofitClient.getInstance(url)
                            // 使用同步配置(registerUser)作为最终校验，确保数据库可写入
                            val config = com.example.areyoualive.data.model.MonitoringConfig(
                                deviceId = manager.deviceId,
                                userName = name,
                                timeoutHours = manager.timeoutHours,
                                emergencyEmail = email
                            )
                            val response = api.registerUser(config)
                            
                            if (response.isSuccessful) {
                                // 验证通过，启动监测
                                manager.isEnabled = true
                                manager.updateLastActiveTime()
                                isEnabled = true
                                lastActiveTime = manager.getLastActiveTime()
                                onStartMonitoring()
                                Toast.makeText(context, "配置已同步，监测已启动", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "同步失败 (${response.code()})，请检查服务器状态", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "连接错误: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isValidating = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isEnabled && !isValidating
            ) {
                if (isValidating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Text("启动监测", fontSize = 16.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = {
                    manager.isEnabled = false
                    isEnabled = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = isEnabled
            ) {
                Text("暂停监测", fontSize = 16.sp)
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 底部提示
            Text(
                text = "提示：每 ${manager.timeoutHours} 小时未使用手机将触发警报",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

/**
 * 设置页面 - 配置服务端连接
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    manager: UserActivityManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var userName by remember { mutableStateOf(manager.userName) }
    var timeoutHours by remember { mutableStateOf(manager.timeoutHours.toString()) }
    var serverUrl by remember { mutableStateOf(manager.serverUrl) }
    
    // 敏感信息
    var emailList by remember { mutableStateOf(manager.emergencyEmailList) }
    var newEmailInput by remember { mutableStateOf("") }
    
    var isDeveloperMode by remember { mutableStateOf(false) }
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    var isSaving by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null // 无点击效果
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 500) {
                        clickCount++
                    } else {
                        clickCount = 1
                    }
                    lastClickTime = now
                    
                    if (clickCount >= 5 && !isDeveloperMode) {
                        isDeveloperMode = true
                        Toast.makeText(context, "开发者模式已开启", Toast.LENGTH_SHORT).show()
                    }
                }
        ) {
            // === 一般设置 ===
            Text("基本信息", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("你的名字") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = timeoutHours,
                onValueChange = { timeoutHours = it },
                label = { Text("超时时间（小时）") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            // === 同步状态展示 ===
            Text("同步状态", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            
            // 实时刷新同步日志
            var syncLog by remember { mutableStateOf(manager.lastSyncLog) }
            LaunchedEffect(Unit) {
                while (true) {
                    val newLog = manager.lastSyncLog
                    if (syncLog != newLog) {
                        syncLog = newLog
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
            
            Text(
                text = syncLog,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // === 服务端设置 (开发者模式可见) ===
            if (isDeveloperMode) {
                Text("连接设置 (开发者)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址 (需包含 http://)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(AppConfig.DEFAULT_SERVER_URL) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 测试连接按钮
                var isTesting by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        isTesting = true
                        scope.launch {
                            try {
                                val api = com.example.areyoualive.data.api.RetrofitClient.getInstance(serverUrl)
                                val response = api.ping()
                                if (response.isSuccessful) {
                                    Toast.makeText(context, "连接成功: ${response.body()?.message}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "连接失败: ${response.code()}", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法连接到服务器: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isTesting && serverUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接", fontSize = 14.sp)
                    }
                }
                
                // 设备ID展示
                OutlinedTextField(
                    value = manager.deviceId,
                    onValueChange = { },
                    label = { Text("设备 ID (自动生成)") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = Color.White,
                        disabledLabelColor = Color.Gray
                    ),
                    enabled = false
                )
                
                Spacer(modifier = Modifier.height(24.dp))
            }

            // === 邮件凭证 (服务端代发) ===
            Text("紧急联系人邮箱 (已支持多选)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "报警邮件将发送至以下联系人，请确保邮箱正确。",
                fontSize = 13.sp,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 添加邮箱的输入框 + 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newEmailInput,
                    onValueChange = { newEmailInput = it },
                    label = { Text("添加邮箱") },
                    placeholder = { Text("example@mail.com") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newEmailInput.isNotBlank() && newEmailInput.contains("@")) {
                            if (!emailList.contains(newEmailInput.trim())) {
                                emailList = emailList + newEmailInput.trim()
                            }
                            newEmailInput = ""
                        } else {
                            Toast.makeText(context, "请输入有效的邮箱地址", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("添加")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 邮箱列表展示
            emailList.forEach { email ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = email, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            emailList = emailList.filter { it != email }
                        }) {
                            Text("❌", fontSize = 14.sp) // 简单明了的删除图标
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            
            // 保存按钮
            Button(
                onClick = {
                    isSaving = true
                    manager.userName = userName
                    manager.timeoutHours = timeoutHours.toIntOrNull() ?: 24
                    manager.serverUrl = serverUrl
                    manager.emergencyEmailList = emailList // 使用列表同步
                    
                    val emailStr = emailList.joinToString(",")
                    
                    // 异步调用注册接口
                    scope.launch {
                        try {
                            val config = com.example.areyoualive.data.model.MonitoringConfig(
                                deviceId = manager.deviceId,
                                userName = userName,
                                timeoutHours = manager.timeoutHours,
                                emergencyEmail = emailStr
                            )
                            val api = com.example.areyoualive.data.api.RetrofitClient.getInstance(serverUrl)
                            val response = api.registerUser(config)
                            
                            if (response.isSuccessful) {
                                Toast.makeText(context, "配置已上传至服务器！", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "上传失败: ${response.code()}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "连接错误: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text("保存并同步到服务器", fontSize = 16.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            

        }
    }
}
