package com.example.tfgy999

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjectionResultCode: Int? = null
    private var mediaProjectionData: Intent? = null
    private var selectedFrameRate = 120 // 默认帧率
    private var selectedMethod = "默认模式（小白推荐）"
    private var enableFrameBoost by mutableStateOf(false)
    private var enableLowEndFrameBoost by mutableStateOf(false)
    private var enableFloatingWindow by mutableStateOf(false)
    private val receivers = mutableListOf<BroadcastReceiver>()
    private val TAG = "MainActivity"

    // 权限请求码
    companion object {
        const val REQUEST_CODE_PERMISSIONS = 100
        const val REQUEST_CODE_OVERLAY = 101
        const val REQUEST_CODE_SCREEN_CAPTURE = 102
    }

    // 所需运行时权限（屏幕捕获经 MediaProjection 单独请求；CAMERA/存储权限无使用，已移除）
    private val requiredPermissions = arrayOf(
        android.Manifest.permission.WAKE_LOCK
    )

    // 屏幕捕获权限请求
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            startServices()
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "屏幕捕获权限授予，服务启动")
        } else {
            Log.w(TAG, "屏幕捕获权限被拒绝")
            Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    // 悬浮窗权限请求
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            if (enableFloatingWindow) startFloatingWindowService()
            Log.i(TAG, "悬浮窗权限已授予，开始服务")
        } else {
            Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            enableFloatingWindow = false
            Log.w(TAG, "悬浮窗权限被拒绝，开关状态已重置")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 检查并请求所有运行时权限
        checkAndRequestPermissions()

        setContent {
            FrameBoostScreen()
        }

        registerReceiverSafely(fpsUpdateReceiver, IntentFilter("com.example.tfgy999.FPS_UPDATE"))
        Log.i(TAG, "MainActivity创建完成，广播接收器注册")
    }

    // 检查并请求运行时权限
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, REQUEST_CODE_PERMISSIONS)
        }
    }

    // 修复后的 onRequestPermissionsResult
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, // 明确指定为 Array<String>
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show()
                Log.w(TAG, "被拒绝的权限: $deniedPermissions")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receivers.forEach { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "尝试注销未注册的接收器: ${e.message}")
            }
        }
        receivers.clear()
        Log.i(TAG, "MainActivity销毁，广播接收器清理完成")
    }

    // 安全注册广播接收器
    private fun registerReceiverSafely(receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receivers.add(receiver)
    }

    // FPS 更新接收器
    private val fpsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val capturedFps = it.getFloatExtra("capturedFps", 0f)
                val interpolatedFps = it.getFloatExtra("interpolatedFps", 0f)
                val totalFps = it.getFloatExtra("totalFps", 0f)
                Log.d(TAG, "收到FPS更新 - 捕获: $capturedFps FPS, 插值: $interpolatedFps FPS, 总计: $totalFps FPS")
            }
        }
    }

    // 启动相关服务
    private fun startServices() {
        if (mediaProjectionResultCode == null || mediaProjectionData == null) return

        if (enableFrameBoost) {
            val frameRate = if (selectedFrameRate <= 0) 60 else selectedFrameRate
            val intent = Intent(this, AutoFrameBoostService::class.java).apply {
                putExtra("resultCode", mediaProjectionResultCode)
                putExtra("data", mediaProjectionData)
                putExtra("targetFrameRate", frameRate)
                putExtra("interpolationMethod", selectedMethod)
                putExtra("enableFrameBoost", true)
            }
            startServiceCompat(intent)
            Log.i(TAG, "启动听风独家屏幕实时插帧")
        }
    }

    // 启动悬浮窗服务
    @SuppressLint("ObsoleteSdkInt")
    private fun startFloatingWindowService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            val intent = Intent(this, FloatingWindowService::class.java)
            startServiceCompat(intent)
            Log.i(TAG, "启动悬浮窗服务")
        }
    }

    // 停止悬浮窗服务
    private fun stopFloatingWindowService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        stopService(intent)
        Log.i(TAG, "停止悬浮窗服务")
    }

    // 请求悬浮窗权限
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
            Log.i(TAG, "请求悬浮窗权限")
        }
    }

    // 检查服务是否运行
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    // 兼容启动服务
    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // 主界面
    @Composable
    fun FrameBoostScreen() {
        var isServiceRunning by remember {
            mutableStateOf(
                isServiceRunning(AutoFrameBoostService::class.java)
            )
        }
        var frameRateExpanded by remember { mutableStateOf(false) }
        var methodExpanded by remember { mutableStateOf(false) }
        val frameRateOptions = listOf(60, 90, 120, 144)
        val interpolationMethods = listOf(
            "默认模式（小白推荐）",
            "光流辅助插值（实验中）",
            "简单帧混合（实验中）"
        )
        val scope = rememberCoroutineScope()
        var showDialog by remember { mutableStateOf(false) }
        var dialogTitle by remember { mutableStateOf("") }
        var dialogMessage by remember { mutableStateOf("") }
        var enableHistoryDisplay by remember { mutableStateOf(false) } // 默认关闭

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "听风屏幕实时插帧19.0（支持全游戏）",
                fontSize = 24.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启动听风屏幕实时插帧（全能版）", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = enableFrameBoost,
                    onCheckedChange = { enable ->
                        if (enable && enableLowEndFrameBoost) {
                            Toast.makeText(this@MainActivity, "不能同时开启高端和中低端插帧补帧", Toast.LENGTH_SHORT).show()
                        } else {
                            enableFrameBoost = enable
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示高级悬浮窗", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = enableFloatingWindow,
                    onCheckedChange = { enable ->
                        scope.launch {
                            if (enable) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                    requestOverlayPermission()
                                } else {
                                    startFloatingWindowService()
                                }
                            } else {
                                stopFloatingWindowService()
                            }
                            enableFloatingWindow = enable
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // “显示历史补帧插帧记录”开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示历史补帧插帧记录", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = enableHistoryDisplay,
                    onCheckedChange = { enableHistoryDisplay = it }
                )
            }
            // 红色小字移到开关下方
            Text(
                "关闭可减少不必要的资源消耗",
                fontSize = 12.sp,
                color = Color.Red
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (enableFrameBoost || enableLowEndFrameBoost) {
                Box {
                    OutlinedButton(onClick = { frameRateExpanded = true }) {
                        Text("目标帧率: $selectedFrameRate FPS")
                    }
                    DropdownMenu(
                        expanded = frameRateExpanded,
                        onDismissRequest = { frameRateExpanded = false }
                    ) {
                        frameRateOptions.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("$rate FPS") },
                                onClick = {
                                    selectedFrameRate = rate
                                    frameRateExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Box {
                    OutlinedButton(onClick = { methodExpanded = true }) {
                        Text("插帧方法: ${selectedMethod.take(10)}...")
                    }
                    DropdownMenu(
                        expanded = methodExpanded,
                        onDismissRequest = { methodExpanded = false }
                    ) {
                        interpolationMethods.forEach { method ->
                            DropdownMenuItem(
                                text = { Text(method) },
                                onClick = {
                                    selectedMethod = method
                                    methodExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    scope.launch {
                        if (isServiceRunning) {
                            stopService(Intent(this@MainActivity, AutoFrameBoostService::class.java))
                            stopFloatingWindowService()
                            isServiceRunning = false
                            mediaProjectionResultCode = null
                            mediaProjectionData = null
                            Toast.makeText(this@MainActivity, "服务已停止", Toast.LENGTH_SHORT).show()
                            Log.i(TAG, "所有服务已停止")
                        } else if (enableFrameBoost || enableLowEndFrameBoost) {
                            val intent = mediaProjectionManager.createScreenCaptureIntent()
                            screenCaptureLauncher.launch(intent)
                            isServiceRunning = true
                        } else {
                            dialogTitle = "提示"
                            dialogMessage = "请至少启用一项功能"
                            showDialog = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(if (isServiceRunning) "停止服务" else "启动服务", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            // “查看历史补帧插帧记录”按钮
            Button(
                onClick = {
                    val intent = Intent(this@MainActivity, HistoryActivity::class.java)
                    startActivity(intent)
                    Log.i(TAG, "跳转到历史记录页面")
                },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("查看历史补帧插帧记录", fontSize = 18.sp)
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text(dialogTitle) },
                    text = { Text(dialogMessage) },
                    confirmButton = {
                        Button(onClick = { showDialog = false }) {
                            Text("确定")
                        }
                    }
                )
            }
        }
    }
}