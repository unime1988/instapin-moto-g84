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

data class ReleaseVersionEntry(
    val versionTag: String,
    val title: String,
    val changelogNotes: String,
    val publishedAt: String
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
 * 1-Tap Over-The-Air Updater & Changelog Inspector for Moto G84 5G:
 * Checks `https://api.github.com/repos/unime1988/instapin-moto-g84/releases`,
 * compares the Previous Version, Current Installed Version, and Latest GitHub Version,
 * displays full human-readable Changelogs, and downloads & installs `InstaPin-MotoG84.apk` in 1 tap.
 */
object AppUpdaterService {

    private const val REPO_OWNER = "unime1988"
    private const val REPO_NAME = "instapin-moto-g84"
    private const val RELEASES_LIST_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=8"
    private const val DIRECT_LATEST_APK_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest/download/InstaPin-MotoG84.apk"

    /**
     * Built-in release history so the user always sees what changed across every version even offline.
     */
    val builtInChangelogHistory: List<ReleaseVersionEntry> = listOf(
        ReleaseVersionEntry(
            versionTag = "v1.0.7",
            title = "Version History Tracker & Live GitHub Changelog Viewer",
            changelogNotes = "• Shows Previous Installed Version, Current Active Version, and Newest GitHub Update side-by-side.\n" +
                "• Displays full what's-new changelog notes before and after every update.\n" +
                "• Auto-checks GitHub Releases when opening Moto G84 Setup.",
            publishedAt = "Latest"
        ),
        ReleaseVersionEntry(
            versionTag = "v1.0.6",
            title = "1-Tap Instant Board Save & Safe Escape Bar",
            changelogNotes = "• Tapping any Board chip in the overlay now immediately saves the Reel & minimizes in 0.1s!\n" +
                "• Added sticky top Escape Bar with [Minimize], [Hide Pin], and [✕ Close] buttons so the screen is never trapped.\n" +
                "• Added a small [✕] button on the corner of the floating red pin itself.",
            publishedAt = "Previous"
        ),
        ReleaseVersionEntry(
            versionTag = "v1.0.5",
            title = "Auto-Hover on Instagram & Explicit Bubble Sizing",
            changelogNotes = "• Automatically launches the floating red pin when Instagram opens.\n" +
                "• Fixed overlay window bounds (76x88dp collapsed) so Instagram scrolls freely.",
            publishedAt = "Earlier"
        ),
        ReleaseVersionEntry(
            versionTag = "v1.0.3",
            title = "1-Tap In-App GitHub Updater & Permanent Signing Key",
            changelogNotes = "• Added 1-Tap Over-The-Air APK download & install from GitHub Releases.\n" +
                "• Locked in deterministic signing keystore so updates preserve all saved Boards & Pins.",
            publishedAt = "Initial OTA"
        )
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

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
                val tagName = latestObj["tag_name"]?.jsonPrimitive?.content ?: "v1.0.7"
                val name = latestObj["name"]?.jsonPrimitive?.content ?: tagName
                val releaseBody = latestObj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
                val publishedAt = latestObj["published_at"]?.jsonPrimitive?.content?.take(16)?.replace("T", " ") ?: ""

                val assets = latestObj["assets"]?.jsonArray
                val apkUrl = assets?.firstNotNullOfOrNull { el ->
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
                        ReleaseVersionEntry(
                            versionTag = rTag,
                            title = rName,
                            changelogNotes = rBody.ifBlank { "Automated Moto G84 5G APK build ($rTag)" },
                            publishedAt = rDate
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
                    apkDownloadUrl = apkUrl,
                    isNewerThanCurrent = remoteBuildNum > currentBuildNum,
                    recentReleasesHistory = historyList.ifEmpty { builtInChangelogHistory }
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
