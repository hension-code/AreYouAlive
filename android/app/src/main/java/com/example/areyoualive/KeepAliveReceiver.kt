package com.example.areyoualive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.areyoualive.UserActivityManager

/**
 * 广播接收器 Listener
 * 监听系统级事件，目前主要是监听"用户解锁屏幕" (ACTION_USER_PRESENT)。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = UserActivityManager(context)
        
        when (intent.action) {
            Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                // 当用户解锁屏幕 或 屏幕点亮 时
                // 只要屏幕亮了，就说明人还在（或者至少有交互意图），刷新活跃时间
                val actionName = if (intent.action == Intent.ACTION_USER_PRESENT) "解锁屏幕" else "屏幕点亮"
                manager.updateLastActiveTime()
                manager.updateSyncLogByEvent(actionName)
                
                // 优化：立即触发一次性后台任务上报活跃状态
                if (manager.isEnabled) {
                    val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<CheckAliveWorker>()
                        .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) // 尝试加速执行
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueue(oneTimeRequest)
                    android.util.Log.d("KeepAliveReceiver", "$actionName detected. Triggering immediate heartbeat sync.")
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // 开机自启动逻辑
                if (manager.isEnabled) {
                    android.util.Log.d("KeepAliveReceiver", "Boot completed. Restarting monitoring...")
                    
                    // 启动周期性后台任务
                    val workRequest = androidx.work.PeriodicWorkRequestBuilder<CheckAliveWorker>(AppConfig.CHECK_INTERVAL_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        "LifeCheck",
                        androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                        workRequest
                    )
                    
                    // 启动前台服务以持续监听解锁事件
                    val serviceIntent = Intent(context, KeepAliveService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
