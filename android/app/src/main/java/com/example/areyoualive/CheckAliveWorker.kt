package com.example.areyoualive

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 核心后台检测任务 (Worker)
 * 由 WorkManager 调度，按配置周期运行。
 * 负责检查距离上一次活跃的时间差，并决定是否向服务器发送心跳。
 */
class CheckAliveWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = UserActivityManager(applicationContext)
        
        // 如果监测未开启，直接结束
        if (!manager.isEnabled) return Result.success()

        // 额外的活跃检测 1：如果后台任务运行时检测到屏幕是亮着的（用户正在使用）
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isInteractive) {
            android.util.Log.d("CheckAliveWorker", "Activity detected: Screen is interactive.")
            manager.updateLastActiveTime()
        }

        val lastActive = manager.getLastActiveTime()
        val lastSynced = manager.lastSyncedTimestamp
        val now = System.currentTimeMillis()

        var activityDetected = false
        
        // --- 核心活跃检测逻辑 ---
        
        // 1. 本地检测：如果本地活跃时间戳大于上次同步的时间戳，说明自上次同步以来发生了新的活跃行为
        // (例如在 worker 运行间隙，KeepAliveReceiver 监测到了屏幕解锁并更新了 lastActive)
        // 注意：必须是 > (大于)，不能是 >=。如果相等，说明这个活跃点已经同步过了，不能重复上报，
        // 否则会导致服务器无限刷新活跃时间，导致超时报警失效。
        if (lastActive > lastSynced) {
            activityDetected = true
            android.util.Log.d("CheckAliveWorker", "Activity detected: Local active time is fresher than last sync.")
        }

        // 2. 运动检测：检测运动步数是否有变化
        val currentSteps = withTimeoutOrNull(3000) { getStepCount() }
        if (currentSteps != null) {
            val lastSteps = manager.lastStepCount
            if (!activityDetected && lastSteps > 0 && currentSteps > lastSteps) {
                activityDetected = true
                android.util.Log.d("CheckAliveWorker", "Activity detected: Steps increased ($lastSteps -> $currentSteps).")
                // 如果是步数导致的活跃，立即更新本地活跃时间，以便下一次 loop 或紧急页面使用
                manager.updateLastActiveTime()
            }
            manager.lastStepCount = currentSteps
        }

        // === 判读逻辑 ===
        // 如果检测到了新的活跃，上报到服务器
        if (activityDetected) {
            val updatedLastActive = manager.getLastActiveTime() // 获取最新的活跃时间
            val serverUrl = manager.serverUrl
            val deviceId = manager.deviceId
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentTimeStr = sdf.format(Date())

            try {
                // 发送心跳请求
                val api = com.example.areyoualive.data.api.RetrofitClient.getInstance(serverUrl)
                val response = api.sendHeartbeat(com.example.areyoualive.data.model.HeartbeatRequest(deviceId))
                
                if (response.isSuccessful) {
                    // 同步成功后，将本地这个“活跃点”记录为已同步
                    manager.lastSyncedTimestamp = updatedLastActive
                    manager.lastSyncLog = "[$currentTimeStr] 活跃同步成功"
                    android.util.Log.d("CheckAliveWorker", "Heartbeat sent and synced at $updatedLastActive")
                } else {
                    manager.lastSyncLog = "[$currentTimeStr] 同步失败: ${response.code()}"
                }
            } catch (e: Exception) {
                manager.lastSyncLog = "[$currentTimeStr] 网络错误: ${e.message}"
                android.util.Log.e("CheckAliveWorker", "Heartbeat failed", e)
                // 关键修正：网络失败不应中断 Worker 执行，必须继续向下执行本地预警逻辑
                // Do NOT return Result.retry() here!
            }
        } else {
            android.util.Log.d("CheckAliveWorker", "No NEW activity detected. Sleeping...")
        }

        val timeoutMillis = manager.getTimeoutMillis() // 用户设定的超时时间
        val diff = now - lastActive // 距离最后活跃已经过去的时间

        // 警告阈值：在真正超时前预警 (按配置)
        val warningThreshold = timeoutMillis - TimeUnit.MINUTES.toMillis(AppConfig.ADVANCE_WARNING_MINUTES)

        
        // === 本地 UI 警告逻辑 ===
        // 使用 Full Screen Intent 通知，确保在后台/锁屏时能弹出 Activity
        // (Android 10+ 禁止后台直接 startActivity)
        if (diff > warningThreshold && diff < timeoutMillis) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "emergency_alert_channel"
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "紧急警报",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用于显示长时间未活跃的紧急确认弹窗"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(applicationContext, EmergencyActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle("Are You Alive?")
                .setContentText("检测到需确认安全状态！")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true) // 关键：高优先级跳转
                .setAutoCancel(true)
                .build()

            notificationManager.notify(999, notification)
            android.util.Log.d("CheckAliveWorker", "Emergency notification sent with FullScreenIntent")
        }

        return Result.success()
    }

    /**
     * 获取当前系统记录的总步数 (自启动以来)
     */
    private suspend fun getStepCount(): Float? = suspendCancellableCoroutine { continuation ->
        val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    sensorManager.unregisterListener(this)
                    if (continuation.isActive) {
                        continuation.resume(event.values[0])
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)

        continuation.invokeOnCancellation {
            sensorManager.unregisterListener(listener)
        }
    }
}
