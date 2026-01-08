package com.ebagesprpe.gyselbevsb

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ebagesprpe.gyselbevsb.network.ApiClient
import com.ebagesprpe.gyselbevsb.ui.components.TerminalCard
import com.ebagesprpe.gyselbevsb.ui.theme.AppColors
import com.ebagesprpe.gyselbevsb.ui.theme.PhaseDetectorTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhaseDetectorTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    
    // --- 状态管理 (State Management) ---
    var isServiceRunning by remember { mutableStateOf(MonitorService.isServiceRunning) }
    var logText by remember { mutableStateOf("Waiting for signal...\n") }
    var infoText by remember { mutableStateOf("System Ready.\n") }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // --- 辅助函数：添加带时间戳的日志 ---
    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText += "[$time] $msg\n"
    }

    fun appendInfo(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // 简单限制行数防止内存溢出 (类似原逻辑的 limit)
        if (infoText.length > 5000) {
            infoText = infoText.takeLast(2000)
        }
        infoText += "[$time] $msg\n"
    }

    // --- 权限请求 ---
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendLog("通知权限已获取，请再次点击启动")
        } else {
            appendLog("错误: 必须授权通知才能运行前台服务")
            Toast.makeText(context, "权限不足", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Broadcast Receiver (生命周期绑定) ---
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    MonitorService.LOG_ACTION -> {
                        intent.getStringExtra("msg")?.let { appendLog(it) }
                    }
                    MonitorService.INFO_ACTION -> {
                        intent.getStringExtra("msg")?.let { appendInfo(it) }
                    }
                    MonitorService.STATUS_ACTION -> {
                        isServiceRunning = intent.getBooleanExtra("running", false)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(MonitorService.LOG_ACTION)
            addAction(MonitorService.INFO_ACTION)
            addAction(MonitorService.STATUS_ACTION)
        }
        // 兼容 Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // --- 核心操作逻辑 ---
    fun checkEnvironment() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        val batteryStatus = if (isIgnored) "已忽略优化 (优)" else "受限 (需设置)"
        
        appendLog("--- 环境自检 ---")
        appendLog("电池策略: $batteryStatus")
        appendLog("Host: ${ApiClient.TARGET_HOST}")
        appendLog("API Level: ${Build.VERSION.SDK_INT}")
        Toast.makeText(context, "检查完成", Toast.LENGTH_SHORT).show()
    }

    fun startMonitorService() {
        val intent = Intent(context, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            appendLog(">>> 发送启动指令...")
            infoText = "" // 清空 Info
        } catch (e: Exception) {
            appendLog("启动失败: ${e.message}")
            e.printStackTrace()
            isServiceRunning = false
        }
    }

    fun handleStartClick() {
        // 1. 权限检查
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        // 2. 电池优化检查
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryDialog = true
            return
        }

        startMonitorService()
    }

    fun handleStopClick() {
        val intent = Intent(context, MonitorService::class.java)
        context.stopService(intent)
        isServiceRunning = false
        appendLog("正在停止服务...")
    }

    // --- UI 布局 ---
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("运行保障") },
            text = { Text("为了防止服务在后台被断开，请在设置中将本应用设为“不优化”。") },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        } catch (ex: Exception) {
                            appendLog("无法打开设置页面")
                        }
                    }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    startMonitorService()
                }) { Text("强制启动") }
            }
        )
    }

    // 主界面结构
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.BgDark)
            .padding(12.dp)
    ) {
        val maxHeight = maxHeight

        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Phase Detector Pro",
                    color = AppColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em,
                    modifier = Modifier.alignByBaseline()
                )
                
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .alignByBaseline()
                        .border(1.dp, AppColors.BadgeStroke, RoundedCornerShape(4.dp))
                        .background(AppColors.BadgeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        // 动态获取版本号
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            // 2. 控制按钮面板 [修复核心：解决按钮错位]
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.BgCard),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 启动按钮
                    Button(
                        onClick = { handleStartClick() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.BtnStart,
                            disabledContainerColor = AppColors.BtnStart.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isServiceRunning,
                        // [FIX] 减小内部边距，防止文字换行
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("启动监控", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // 停止按钮
                    Button(
                        onClick = { handleStopClick() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.BtnStop,
                            disabledContainerColor = AppColors.BtnStop.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        enabled = isServiceRunning,
                        // [FIX] 减小内部边距
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("停止服务", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // 检查按钮
                    OutlinedButton(
                        onClick = { checkEnvironment() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, AppColors.BtnCheck),
                        shape = RoundedCornerShape(8.dp),
                        // [FIX] 减小内部边距
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("环境自检", fontSize = 13.sp)
                    }
                }
            }

            // 3. Info Monitor (高度固定为屏幕 25%)
            TerminalCard(
                title = "● SYSTEM STATUS",
                content = infoText,
                titleColor = AppColors.TextInfo,
                contentColor = AppColors.TextInfo,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .height(maxHeight * 0.25f)
            )

            // 4. Log Stream (填充剩余)
            TerminalCard(
                title = "> EVENT LOG STREAM",
                content = logText,
                titleColor = AppColors.TextConsole,
                contentColor = AppColors.TextConsole,
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 4.dp)
                    .weight(1f)
            )
        }
    }
}