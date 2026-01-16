package com.example.areyoualive.data.model

data class MonitoringConfig(
    val deviceId: String,
    val userName: String,
    val timeoutHours: Int,
    val emergencyEmail: String
)

data class HeartbeatRequest(
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApiResponse(
    val status: String,
    val message: String?,
    val error: String?
)
