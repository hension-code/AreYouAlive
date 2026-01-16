package com.example.areyoualive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务 - 用于持续监听系统广播事件（如解锁屏幕）
 * 前台服务可以在应用处于后台时继续运行，确保能够及时响应用户活动。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var keepAliveReceiver: KeepAliveReceiver? = null

    override fun onCreate() {
        super.onCreate()
        
        // 创建通知渠道 (Android 8.0+)
        createNotificationChannel()
        
        // 启动前台服务
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // 动态注册广播接收器
        keepAliveReceiver = KeepAliveReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Android 13+ 需要指定 RECEIVER_NOT_EXPORTED 或 RECEIVER_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keepAliveReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keepAliveReceiver, filter)
        }
        
        android.util.Log.d("KeepAliveService", "Service started, receiver registered.")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销接收器
        keepAliveReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // 可能已经被注销
            }
        }
        android.util.Log.d("KeepAliveService", "Service destroyed, receiver unregistered.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果服务被系统杀掉，尝试重新启动
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "活着吗 - 后台监测",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持应用在后台运行以监测您的活跃状态"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("活着吗 - 监测中")
            .setContentText("正在后台监测您的活跃状态")
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
