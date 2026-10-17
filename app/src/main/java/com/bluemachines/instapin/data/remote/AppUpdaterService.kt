package com.bluemachines.instapin.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bluemachines.instapin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val isNewerThanCurrent: Boolean
)

/**
 * 1-Tap Over-The-Air Updater for Moto G84 5G:
 * Checks `https://api.github.com/repos/unime1988/instapin-moto-g84/releases/latest`,
 * downloads `InstaPin-MotoG84.apk`, and launches the native Android Package Installer
 * so updates happen in-place without losing any saved Room database boards or pins.
 */
object AppUpdaterService {

    private const val REPO_OWNER = "unime1988"
    private const val REPO_NAME = "instapin-moto-g84"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val DIRECT_LATEST_APK_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest/download/InstaPin-MotoG84.apk"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstaPin-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub Release check returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val tagName = root["tag_name"]?.jsonPrimitive?.content ?: "v1.0.1"
                val name = root["name"]?.jsonPrimitive?.content ?: tagName
                val publishedAt = root["published_at"]?.jsonPrimitive?.content ?: ""

                val assets = root["assets"]?.jsonArray
                val apkUrl = assets?.firstNotNullOfOrNull { el ->
                    val obj = el.jsonObject
                    val dlUrl = obj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                    if (dlUrl.endsWith(".apk", ignoreCase = true)) dlUrl else null
                } ?: DIRECT_LATEST_APK_URL

                val remoteBuildNum = tagName.substringAfterLast(".").filter { it.isDigit() }.toIntOrNull() ?: 0
                val currentBuildNum = BuildConfig.VERSION_NAME.substringAfterLast(".").filter { it.isDigit() }.toIntOrNull() ?: 0

                GitHubReleaseInfo(
                    tagName = tagName,
                    releaseName = name,
                    publishedAt = publishedAt,
                    apkDownloadUrl = apkUrl,
                    isNewerThanCurrent = remoteBuildNum > currentBuildNum
                )
            }
        }
    }

    suspend fun downloadAndTriggerInstall(
        context: Context,
        apkUrl: String = DIRECT_LATEST_APK_URL,
        onProgressPercent: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                throw IllegalStateException("Please allow 'Install unknown apps' for InstaPin, then tap Update again.")
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetApkFile = File(updatesDir, "InstaPin-MotoG84-latest.apk")

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "InstaPin-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty APK response")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(targetApkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                onProgressPercent(pct)
                            }
                        }
                        output.flush()
                    }
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetApkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            targetApkFile
        }
    }
}
