package com.bluemachines.instapin.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class InstagramPairingResponse(
    val deviceToken: String,
    val pairingCode: String,
    val expiresAt: Long,
    val instagramUsername: String,
    val instagramChatUrl: String
)

@Serializable
data class InstagramPairingStatus(
    val paired: Boolean,
    val pairedAt: Long? = null
)

@Serializable
data class InstagramInboxItem(
    val id: String,
    val instagramUrl: String,
    val title: String = "",
    val note: String = "",
    val createdAt: Long
)

@Serializable
data class InstagramInboxResponse(
    val items: List<InstagramInboxItem> = emptyList()
)

data class InstagramChatCaptureState(
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val pairingCode: String = "",
    val pairingExpiresAt: Long = 0L,
    val instagramChatUrl: String = "https://ig.me/m/instapin_app",
    val statusMessage: String = "Not connected",
    val lastSyncedCount: Int = 0
)

class InstagramChatCaptureService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val BASE_URL = "https://instapin-instagram-webhook.unime1988.workers.dev"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun createPairing(): InstagramPairingResponse = request(
        path = "/v1/pairing",
        method = "POST"
    )

    suspend fun getPairingStatus(deviceToken: String): InstagramPairingStatus = request(
        path = "/v1/pairing/status",
        deviceToken = deviceToken
    )

    suspend fun getInbox(deviceToken: String, after: Long = 0L): InstagramInboxResponse = request(
        path = "/v1/inbox?after=$after",
        deviceToken = deviceToken
    )

    suspend fun acknowledge(deviceToken: String, itemId: String) {
        requestWithoutBody(
            path = "/v1/inbox/$itemId/ack",
            method = "POST",
            deviceToken = deviceToken
        )
    }

    suspend fun disconnect(deviceToken: String) {
        requestWithoutBody(
            path = "/v1/device",
            method = "DELETE",
            deviceToken = deviceToken
        )
    }

    private suspend inline fun <reified T> request(
        path: String,
        method: String = "GET",
        deviceToken: String = ""
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$BASE_URL$path")
        if (deviceToken.isNotBlank()) builder.header("Authorization", "Bearer $deviceToken")
        if (method == "POST") builder.post("{}".toRequestBody(jsonMediaType)) else builder.get()
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Chat capture request failed (${response.code})")
            json.decodeFromString<T>(response.body?.string().orEmpty())
        }
    }

    private suspend fun requestWithoutBody(
        path: String,
        method: String,
        deviceToken: String
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer $deviceToken")
        if (method == "DELETE") builder.delete() else builder.post("{}".toRequestBody(jsonMediaType))
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Chat capture request failed (${response.code})")
        }
    }
}
