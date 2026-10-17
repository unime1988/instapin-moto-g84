package com.bluemachines.instapin.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bluemachines.instapin.BuildConfig
import com.bluemachines.instapin.InstaPinApp
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

data class ReleaseVersionEntry(
    val versionTag: String,
    val title: String,
    val changelogNotes: String,
    val publishedAt: String,
    val apkDownloadUrl: String = ""
)

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val changelogBody: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val isNewerThanCurrent: Boolean,
    val recentReleasesHistory: List<ReleaseVersionEntry> = emptyList()
)

/**
 * 1-Tap Over-The-Air Updater + "Time Machine" Version Rollback & Auto JSON Backup Service:
 * - Before installing any update, automatically:
 *   1. Exports a full JSON snapshot of all Boards, Moods, Pins & Analyses (`InstaPin-AutoBackup.json`).
 *   2. Copies the currently running APK (`context.applicationInfo.sourceDir`) to `filesDir/rollback/InstaPin-previous-working.apk`.
 * - Because `versionCode = 100` is fixed across builds, the user can 1-tap rollback to either:
 *   - Their cached local `InstaPin-previous-working.apk` (instant, works offline), OR
 *   - Any historical GitHub Release version from the "All Versions / Time Machine" list!
 */
object AppUpdaterService {

    private const val REPO_OWNER = "unime1988"
    private const val REPO_NAME = "instapin-moto-g84"
    private const val RELEASES_LIST_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10"
    private const val DIRECT_LATEST_APK_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest/download/InstaPin-MotoG84.apk"

    val builtInChangelogHistory: List<ReleaseVersionEntry> = listOf(
        ReleaseVersionEntry(
            versionTag = "v1.0.9",
            title = "Security Hardening, Multi-Provider BYOK, 1-Tap Rollback Time Machine & Pinterest Masonry UI",
            changelogNotes = "• Hardware Android Keystore AES-256-GCM encryption for API keys + HTTPS-only network security.\n" +
                "• Multi-Provider BYOK: Google Gemini, OpenAI (GPT-4o), Groq (Llama 3.3), and Anthropic Claude + 1-Tap Connection Test.\n" +
                "• 1-Tap Version Rollback ('Time Machine') + Automatic Local APK & JSON Database Backup before every update.\n" +
                "• 2-Column Pinterest Masonry Grid with Reel thumbnails, Draggable Floating Edge Pill, and Live Theme/Font Switcher.",
            publishedAt = "Latest",
            apkDownloadUrl = DIRECT_LATEST_APK_URL
        ),
        ReleaseVersionEntry(
            versionTag = "v1.0.8",
            title = "Permanent Gradle Signing Binding & Version Tracker",
            changelogNotes = "• Bound signingConfigs.debug explicitly to instapin-debug.keystore for conflict-free updates.\n" +
                "• Added Previous/Current/Latest version comparison grid & in-app changelog viewer.",
            publishedAt = "Previous",
            apkDownloadUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/v1.0.8/InstaPin-MotoG84.apk"
        ),
        ReleaseVersionEntry(
            versionTag = "v1.0.6",
            title = "1-Tap Instant Board Save & Safe Escape Bar",
            changelogNotes = "• Tapping any Board chip in the overlay immediately saves the Reel & minimizes in 0.1s.\n" +
                "• Added sticky top Escape Bar with [Minimize], [Hide Pin], and [✕ Close] buttons.",
            publishedAt = "Earlier",
            apkDownloadUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/v1.0.6/InstaPin-MotoG84.apk"
        )
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun hasLocalRollbackApk(context: Context): Boolean {
        val file = File(File(context.filesDir, "rollback"), "InstaPin-previous-working.apk")
        return file.exists() && file.length() > 1_000_000L
    }

    fun hasLocalJsonBackup(context: Context): Boolean {
        val file = File(File(context.filesDir, "backups"), "InstaPin-AutoBackup.json")
        return file.exists() && file.length() > 10L
    }

    /**
     * Snapshots the currently installed APK and full Room database JSON before applying any update.
     */
    suspend fun snapshotCurrentApkAndDatabaseBeforeUpdate(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext as? InstaPinApp
            app?.preferences?.recordVersionBeforeUpdate()
            app?.repository?.exportFullBackupSnapshotToFile(context)

            val currentSourceApk = File(context.applicationInfo.sourceDir)
            if (currentSourceApk.exists()) {
                val rollbackDir = File(context.filesDir, "rollback").apply { mkdirs() }
                val backupApk = File(rollbackDir, "InstaPin-previous-working.apk")
                currentSourceApk.copyTo(backupApk, overwrite = true)
            }
        }
    }

    /**
     * Immediately triggers Android Package Installer on the cached previous working APK (`InstaPin-previous-working.apk`).
     */
    suspend fun triggerLocalRollbackInstall(context: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInstallPackagesPermission(context)
            val rollbackFile = File(File(context.filesDir, "rollback"), "InstaPin-previous-working.apk")
            if (!rollbackFile.exists()) {
                throw IllegalStateException("No local rollback APK cached yet. Select a version from 'All Versions' below to restore from GitHub.")
            }
            launchPackageInstallerForFile(context, rollbackFile)
            rollbackFile
        }
    }

    suspend fun checkLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_LIST_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstaPin-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub Release check returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val releasesArray = json.parseToJsonElement(body).jsonArray
                if (releasesArray.isEmpty()) {
                    throw IllegalStateException("No releases published yet")
                }

                val latestObj = releasesArray.first().jsonObject
                val tagName = latestObj["tag_name"]?.jsonPrimitive?.content ?: "v1.0.9"
                val name = latestObj["name"]?.jsonPrimitive?.content ?: tagName
                val releaseBody = latestObj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
                val publishedAt = latestObj["published_at"]?.jsonPrimitive?.content?.take(16)?.replace("T", " ") ?: ""

                val latestAssets = latestObj["assets"]?.jsonArray
                val latestApkUrl = latestAssets?.firstNotNullOfOrNull { el ->
                    val obj = el.jsonObject
                    val dlUrl = obj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                    if (dlUrl.endsWith(".apk", ignoreCase = true)) dlUrl else null
                } ?: DIRECT_LATEST_APK_URL

                val historyList = releasesArray.mapNotNull { el ->
                    runCatching {
                        val obj = el.jsonObject
                        val rTag = obj["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val rName = obj["name"]?.jsonPrimitive?.content ?: rTag
                        val rBody = obj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val rDate = obj["published_at"]?.jsonPrimitive?.content?.take(10) ?: ""
                        val rAssets = obj["assets"]?.jsonArray
                        val rApkUrl = rAssets?.firstNotNullOfOrNull { a ->
                            val aObj = a.jsonObject
                            val u = aObj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                            if (u.endsWith(".apk", ignoreCase = true)) u else null
                        } ?: "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$rTag/InstaPin-MotoG84.apk"

                        ReleaseVersionEntry(
                            versionTag = rTag,
                            title = rName,
                            changelogNotes = rBody.ifBlank { "Automated Moto G84 5G APK build ($rTag)" },
                            publishedAt = rDate,
                            apkDownloadUrl = rApkUrl
                        )
                    }.getOrNull()
                }

                val remoteBuildNum = tagName.substringAfterLast(".").filter { it.isDigit() }.toIntOrNull() ?: 0
                val currentBuildNum = BuildConfig.VERSION_NAME.substringAfterLast(".").filter { it.isDigit() }.toIntOrNull() ?: 0

                GitHubReleaseInfo(
                    tagName = tagName,
                    releaseName = name,
                    changelogBody = releaseBody.ifBlank {
                        builtInChangelogHistory.firstOrNull()?.changelogNotes.orEmpty()
                    },
                    publishedAt = publishedAt,
                    apkDownloadUrl = latestApkUrl,
                    isNewerThanCurrent = remoteBuildNum > currentBuildNum,
                    recentReleasesHistory = historyList.ifEmpty { builtInChangelogHistory }
                )
            }
        }
    }

    suspend fun downloadAndTriggerInstall(
        context: Context,
        apkUrl: String = DIRECT_LATEST_APK_URL,
        snapshotBeforeInstall: Boolean = true,
        onProgressPercent: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInstallPackagesPermission(context)

            if (snapshotBeforeInstall) {
                snapshotCurrentApkAndDatabaseBeforeUpdate(context)
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetApkFile = File(updatesDir, "InstaPin-MotoG84-install.apk")

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

            launchPackageInstallerForFile(context, targetApkFile)
            targetApkFile
        }
    }

    private fun ensureInstallPackagesPermission(context: Context) {
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
            throw IllegalStateException("Please allow 'Install unknown apps' for InstaPin, then tap again.")
        }
    }

    private fun launchPackageInstallerForFile(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
