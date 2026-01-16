package com.example.areyoualive

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 紧急报警 Activity
 * 当接近超时的时候弹出。
 * 特性：锁屏上方显示、点亮屏幕、强制震动。
 */
class EmergencyActivity : ComponentActivity() {

    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- 核心逻辑: 即使锁屏也要显示 ---
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 取消通知栏的通知 (ID 999 与 CheckAliveWorker 中一致)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(999)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        startEmergencyVibration()

        setContent {
            var isSending by remember { mutableStateOf(false) }
            
            EmergencyScreen(
                onConfirmAlive = {
                    isSending = true
                    val manager = UserActivityManager(this)
                    manager.updateLastActiveTime()
                    
                    // 核心建议：立即发送一次心跳同步到后端，解除警报
                    val heartbeat = com.example.areyoualive.data.model.HeartbeatRequest(deviceId = manager.deviceId)
                    val api = com.example.areyoualive.data.api.RetrofitClient.getInstance(manager.serverUrl)
                    
                    lifecycleScope.launch {
                        try {
                            api.sendHeartbeat(heartbeat)
                        } catch (e: Exception) {
                            // 即使发送失败也允许关闭，WorkManager 会在之后重试
                        } finally {
                            stopVibration()
                            finish()
                        }
                    }
                },
                isSending = isSending
            )
        }
    }

    private fun startEmergencyVibration() {
        if (vibrator?.hasVibrator() == true) {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVibration()
    }
}

@Composable
fun EmergencyScreen(onConfirmAlive: () -> Unit, isSending: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFD32F2F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "⚠️",
                fontSize = 80.sp
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "你还在吗？",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "长时间未检测到活动\n即将向紧急联系人发送求助邮件",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onConfirmAlive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                enabled = !isSending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.8f)
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(color = Color(0xFFD32F2F))
                } else {
                    Text(
                        text = "我还活着！",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "点击上方按钮确认安全",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
