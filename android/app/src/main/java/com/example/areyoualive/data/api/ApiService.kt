package com.example.areyoualive.data.api

import com.example.areyoualive.data.model.ApiResponse
import com.example.areyoualive.data.model.HeartbeatRequest
import com.example.areyoualive.data.model.MonitoringConfig
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/register")
    suspend fun registerUser(@Body config: MonitoringConfig): Response<ApiResponse>

    @POST("api/heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): Response<ApiResponse>

    @retrofit2.http.GET("api/ping")
    suspend fun ping(): Response<ApiResponse>
}
