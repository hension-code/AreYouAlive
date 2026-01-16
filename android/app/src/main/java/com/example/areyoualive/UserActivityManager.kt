package com.example.areyoualive

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户状态管理器
 * 负责将数据保存到 SharedPreferences 本地存储中。
 */
class UserActivityManager(context: Context) {
    // SharedPreferences 文件名为 "are_you_alive_prefs"
    private val prefs: SharedPreferences = context.getSharedPreferences("are_you_alive_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_ACTIVE = "last_active_timestamp"
        private const val KEY_TIMEOUT_HOURS = "timeout_hours"
        private const val KEY_EMAIL_ADDRESS = "email_address"
        private const val KEY_EMAIL_PASSWORD = "email_password"
        private const val KEY_EMERGENCY_EMAIL = "emergency_email"
        private const val KEY_IS_ENABLED = "is_enabled"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_STEP_COUNT = "last_step_count"
        private const val KEY_LAST_SYNC_LOG = "last_sync_log"
        private const val KEY_LAST_SYNCED_TIMESTAMP = "last_synced_timestamp"
    }

    /**
     * 更新最后活跃时间为"现在"
     * 每次解锁屏幕或点"I AM ALIVE"时调用
     */
    fun updateLastActiveTime() {
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    /**
     * 获取最后活跃时间
     */
    fun getLastActiveTime(): Long {
        // 如果从未设置过，默认为"现在"，防止刚安装就报警
        return prefs.getLong(KEY_LAST_ACTIVE, System.currentTimeMillis())
    }

    // === 配置项存取 (Getter/Setter) ===

    // 超时阈值（小时）
    var timeoutHours: Int
        get() = prefs.getInt(KEY_TIMEOUT_HOURS, AppConfig.DEFAULT_TIMEOUT_HOURS)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_HOURS, value).apply()

    // 紧急联系人邮箱 (原始字符串)
    var emergencyEmail: String
        get() = prefs.getString(KEY_EMERGENCY_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMERGENCY_EMAIL, value).apply()

    // 紧急联系人邮箱 (列表模式，方便 UI 使用)
    var emergencyEmailList: List<String>
        get() = emergencyEmail.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            emergencyEmail = value.joinToString(",")
        }

    // 用户姓名
    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()
        
    // 服务器地址
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, AppConfig.DEFAULT_SERVER_URL) ?: AppConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()
        
    // 设备唯一ID (自动生成)
    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    // 是否开启监测开关
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ENABLED, value).apply()

    // 上一次记录的步数
    var lastStepCount: Float
        get() = prefs.getFloat(KEY_LAST_STEP_COUNT, 0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_STEP_COUNT, value).apply()

    // 上一次同步到服务器的日志信息 (包含时间戳)
    var lastSyncLog: String
        get() = prefs.getString(KEY_LAST_SYNC_LOG, "无记录") ?: "无记录"
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_LOG, value).apply()

    // 上一次成功同步到服务器的【活跃数据】的时间点
    var lastSyncedTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNCED_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNCED_TIMESTAMP, value).apply()
        
    /**
     * 更新同步状态日志（用于 UI 展示）
     */
    fun updateSyncLogByEvent(event: String) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val currentTimeStr = sdf.format(java.util.Date())
        lastSyncLog = "[$currentTimeStr] 本地活跃: $event"
    }

    // 助手方法：把小时转换为毫秒
    fun getTimeoutMillis(): Long {
        return timeoutHours * 60 * 60 * 1000L
    }
}
