package com.bluemachines.instapin.data.analytics

import android.annotation.SuppressLint
import android.os.Build
import com.bluemachines.instapin.BuildConfig
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.remote.InstagramChatCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Privacy-first anonymous usage analytics.
 *
 * What it sends: named action events carrying only a small allowlist of scalar
 * properties (counts, enum names, booleans) under a random per-install UUID.
 * What it never sends: Reel URLs, captions, notes, board/mood/section names,
 * author handles, search text, pairing codes, API keys, clipboard content,
 * or any hardware/account identifier.
 *
 * The prop sanitizer is defense-in-depth: even if a caller accidentally passes
 * a content-like key, it is dropped before anything leaves the device.
 */
@SuppressLint("StaticFieldLeak")
object AnalyticsClient {

    private const val ANALYTICS_URL = InstagramChatCaptureService.BASE_URL + "/analytics"
    private const val INGEST_KEY = "instapin-analytics-v1"
    private const val MAX_QUEUED_EVENTS = 300
    private const val MAX_BATCH = 50
    private const val FLUSH_THRESHOLD = 15
    private const val MAX_PROPS = 12
    private const val MAX_STRING_PROP_LEN = 120

    private val EVENT_NAME_REGEX = Regex("^[a-z0-9_]{1,40}$")
    private val PROP_KEY_REGEX = Regex("^[a-z0-9_]{1,30}$")

    // Substring match on the lowercased key: anything content-shaped is refused.
    private val DENIED_PROP_KEY_PARTS = listOf(
        "url", "link", "uri", "shortcode", "caption", "note", "query", "text",
        "handle", "author", "token", "code", "name", "title", "file", "path",
        "niche", "key", "clipboard", "secret", "password", "mail", "phone"
    )

    private data class QueuedEvent(
        val event: String,
        val props: Map<String, Any>,
        val clientTs: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val queueMutex = Mutex()
    private val pendingEvents = ArrayDeque<QueuedEvent>()

    @Volatile private var preferences: AppPreferences? = null
    @Volatile private var analyticsEnabled = true
    @Volatile private var installId = ""
    @Volatile private var initialized = false
    private var flushRunning = false

    fun init(prefs: AppPreferences) {
        if (initialized) return
        initialized = true
        preferences = prefs
        scope.launch {
            analyticsEnabled = prefs.isAnalyticsEnabled()
            installId = prefs.getOrCreateAnalyticsInstallId()
            queueMutex.withLock {
                pendingEvents.addAll(decodeQueue(prefs.loadPendingAnalyticsEvents()))
                trimQueueLocked()
            }
            flush()
        }
    }

    /** Enqueue an anonymous event. Props are sanitized before touching the network. */
    fun track(event: String, vararg props: Pair<String, Any?>) {
        if (!initialized || !analyticsEnabled || !EVENT_NAME_REGEX.matches(event)) return
        val cleanProps = sanitizeProps(props.toMap())
        scope.launch {
            queueMutex.withLock {
                pendingEvents.addLast(QueuedEvent(event, cleanProps, System.currentTimeMillis()))
                trimQueueLocked()
                persistQueueLocked()
            }
            if (pendingEvents.size >= FLUSH_THRESHOLD) flush()
        }
    }

    /** Sends any queued events. Safe to call from lifecycle callbacks. */
    fun flush() {
        if (preferences == null) return
        if (!initialized || !analyticsEnabled || installId.isBlank()) return
        scope.launch {
            queueMutex.withLock {
                if (flushRunning || pendingEvents.isEmpty()) return@withLock
                flushRunning = true
            }
            try {
                while (true) {
                    val batch = queueMutex.withLock { pendingEvents.take(MAX_BATCH) }
                    if (batch.isEmpty() || !sendBatch(batch)) break
                    queueMutex.withLock {
                        repeat(batch.size) { pendingEvents.removeFirst() }
                        persistQueueLocked()
                    }
                }
            } finally {
                queueMutex.withLock { flushRunning = false }
            }
        }
    }

    /** Opt-in / opt-out. Sends a single consent event before stopping when disabling. */
    suspend fun setEnabled(enabled: Boolean) {
        val prefs = preferences ?: return
        if (enabled == analyticsEnabled) return
        if (!enabled) {
            track("analytics_toggled", "enabled" to false)
            flushBlocking()
            analyticsEnabled = false
            prefs.setAnalyticsEnabled(false)
            queueMutex.withLock {
                pendingEvents.clear()
                persistQueueLocked()
            }
        } else {
            analyticsEnabled = true
            prefs.setAnalyticsEnabled(true)
            track("analytics_toggled", "enabled" to true)
            flush()
        }
    }

    private suspend fun flushBlocking() {
        if (preferences == null || installId.isBlank()) return
        while (true) {
            val batch = queueMutex.withLock { pendingEvents.take(MAX_BATCH) }
            if (batch.isEmpty() || !sendBatch(batch)) break
            queueMutex.withLock {
                repeat(batch.size) { pendingEvents.removeFirst() }
                persistQueueLocked()
            }
        }
    }

    private fun sanitizeProps(props: Map<String, Any?>): Map<String, Any> {
        val clean = LinkedHashMap<String, Any>()
        for ((key, value) in props) {
            if (clean.size >= MAX_PROPS) break
            if (value == null) continue
            val k = key.lowercase()
            if (!PROP_KEY_REGEX.matches(k)) continue
            if (DENIED_PROP_KEY_PARTS.any { k.contains(it) }) continue
            when (value) {
                is Boolean -> clean[k] = value
                is Int, is Long, is Double, is Float -> clean[k] = value
                is String -> if (value.isNotBlank()) clean[k] = value.take(MAX_STRING_PROP_LEN)
            }
        }
        return clean
    }

    private fun toJsonPrimitive(value: Any): JsonPrimitive = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private suspend fun sendBatch(batch: List<QueuedEvent>): Boolean {
        return runCatching {
            val payload = buildJsonObject {
                put("install_id", installId)
                put("app_version", BuildConfig.VERSION_NAME)
                put("platform", "android")
                put("os_api", Build.VERSION.SDK_INT)
                put("events", buildJsonArray {
                    batch.forEach { ev ->
                        add(buildJsonObject {
                            put("e", ev.event)
                            put("t", ev.clientTs)
                            put("p", JsonObject(ev.props.mapValues { toJsonPrimitive(it.value) }))
                        })
                    }
                })
            }
            val request = Request.Builder()
                .url(ANALYTICS_URL)
                .header("Content-Type", "application/json")
                .header("X-Analytics-Key", INGEST_KEY)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private suspend fun persistQueueLocked() {
        val prefs = preferences ?: return
        val serialized = buildJsonArray {
            pendingEvents.take(MAX_QUEUED_EVENTS).forEach { ev ->
                add(buildJsonObject {
                    put("e", ev.event)
                    put("t", ev.clientTs)
                    put("p", JsonObject(ev.props.mapValues { toJsonPrimitive(it.value) }))
                })
            }
        }.toString()
        prefs.savePendingAnalyticsEvents(serialized)
    }

    private fun trimQueueLocked() {
        while (pendingEvents.size > MAX_QUEUED_EVENTS) pendingEvents.removeFirst()
    }

    private fun decodeQueue(raw: String): List<QueuedEvent> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val obj = el.jsonObject
                val event = obj["e"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val ts = obj["t"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                val props = obj["p"]?.jsonObject?.mapNotNull { (k, v) ->
                    val prim = v.jsonPrimitive
                    when {
                        prim.booleanOrNull != null -> k to prim.booleanOrNull!!
                        prim.longOrNull != null -> k to prim.longOrNull!!
                        prim.doubleOrNull != null -> k to prim.doubleOrNull!!
                        else -> k to prim.content
                    }
                }?.toMap().orEmpty()
                QueuedEvent(event, props, ts)
            }
        }.getOrDefault(emptyList())
    }
}
