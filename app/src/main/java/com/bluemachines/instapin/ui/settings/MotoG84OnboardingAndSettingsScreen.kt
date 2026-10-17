package com.bluemachines.instapin.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.BuildConfig
import com.bluemachines.instapin.data.local.AiProviderType
import com.bluemachines.instapin.data.local.AppThemePreset
import com.bluemachines.instapin.data.local.FeedLayoutMode
import com.bluemachines.instapin.data.local.FontScalePreset
import com.bluemachines.instapin.data.local.UserSettings
import com.bluemachines.instapin.data.remote.ApiConnectionTestResult
import com.bluemachines.instapin.data.remote.AppUpdaterService
import com.bluemachines.instapin.data.remote.GitHubReleaseInfo
import com.bluemachines.instapin.data.repository.PinRepository
import com.bluemachines.instapin.service.FloatingOverlayService
import com.bluemachines.instapin.service.InstaPinAccessibilityService
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotoG84OnboardingAndSettingsScreen(
    settings: UserSettings,
    aiTestResult: ApiConnectionTestResult? = null,
    onSaveMultiProviderAi: (AiProviderType, String, String, Int, String, String) -> Unit = { _, _, _, _, _, _ -> },
    onTestAiConnection: (AiProviderType, String, String, Int, String, String) -> Unit = { _, _, _, _, _, _ -> },
    onSaveVisualAppearance: (AppThemePreset, FeedLayoutMode, FontScalePreset) -> Unit = { _, _, _ -> },
    onExportBackup: ((String) -> Unit) -> Unit = {},
    onRestoreBackup: ((String) -> Unit) -> Unit = {},
    onSaveOverlayPrefs: (Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hasOverlayPermission = Settings.canDrawOverlays(context)
    val accessibilityConnected by InstaPinAccessibilityService.isServiceConnected.collectAsState()
    val lastCaptureReport by InstaPinAccessibilityService.lastCaptureReport.collectAsState()
    val lastSaveTiming by PinRepository.lastSaveTiming.collectAsState()

    var selectedProvider by remember(settings.aiProvider) { mutableStateOf(settings.aiProvider) }
    var apiKeyInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var modelInput by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
    var scriptDurationSec by remember(settings.scriptDurationSeconds) { mutableIntStateOf(settings.scriptDurationSeconds) }
    var scriptToneInput by remember(settings.scriptToneAndLanguage) { mutableStateOf(settings.scriptToneAndLanguage) }
    var nicheInput by remember(settings.creatorNicheDescription) { mutableStateOf(settings.creatorNicheDescription) }
    var overlayRunning by remember { mutableStateOf(FloatingOverlayService.isRunning) }

    // 1-Tap In-App GitHub Updater & Time Machine Rollback states
    val currentVersionLabel = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var updateStatusText by remember {
        mutableStateOf("Checking GitHub Actions for latest version & changelog...")
    }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var showFullReleaseHistory by remember { mutableStateOf(false) }
    var backupStatusMessage by remember { mutableStateOf("") }
    var hasLocalRollbackApk by remember { mutableStateOf(AppUpdaterService.hasLocalRollbackApk(context)) }

    LaunchedEffect(Unit) {
        hasLocalRollbackApk = AppUpdaterService.hasLocalRollbackApk(context)
        AppUpdaterService.checkLatestRelease()
            .onSuccess { info ->
                latestRelease = info
                updateStatusText = if (info.isNewerThanCurrent) {
                    "Update Ready: ${info.tagName} is newer than your installed v${BuildConfig.VERSION_NAME}"
                } else {
                    "You are on the latest version (${info.tagName})"
                }
            }
            .onFailure {
                updateStatusText = "Ready for 1-Tap GitHub Update or Time-Machine Rollback"
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 1-Tap GitHub Updater + Version Rollback Time Machine Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "1-Tap Updater & Version Time Machine",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = updateStatusText,
                            color = if (latestRelease?.isNewerThanCurrent == true) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Version Comparison Grid: Previous -> Current -> Latest GitHub
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VersionRowItem(
                            label = "Previous App Version:",
                            value = settings.previousInstalledVersion,
                            valueColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        VersionRowItem(
                            label = "Current Installed Version:",
                            value = currentVersionLabel,
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                        VersionRowItem(
                            label = "Latest GitHub Release:",
                            value = latestRelease?.let { "${it.tagName} (${it.publishedAt})" } ?: "Checking...",
                            valueColor = if (latestRelease?.isNewerThanCurrent == true) Color(0xFF10B981) else Color(0xFFA78BFA)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // What's New Changelog Box + Time Machine Version Picker
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📋 Changelog (${latestRelease?.tagName ?: "v${BuildConfig.VERSION_NAME}"})",
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            TextButton(
                                onClick = { showFullReleaseHistory = !showFullReleaseHistory }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = Color(0xFFA78BFA),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (showFullReleaseHistory) "Hide Time Machine" else "All Versions / Rollback",
                                    color = Color(0xFFA78BFA),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val activeChangelog = latestRelease?.changelogBody?.ifBlank { null }
                            ?: AppUpdaterService.builtInChangelogHistory.first().changelogNotes

                        Text(
                            text = activeChangelog,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        if (showFullReleaseHistory) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "⏳ Time Machine: Tap 'Restore' on any past release to roll back in-place:",
                                color = Color(0xFFF59E0B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            val historyList = latestRelease?.recentReleasesHistory?.ifEmpty { null }
                                ?: AppUpdaterService.builtInChangelogHistory
                            historyList.forEach { entry ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${entry.versionTag} — ${entry.title}",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (entry.apkDownloadUrl.isNotBlank()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            isDownloadingUpdate = true
                                                            downloadProgress = 0
                                                            updateStatusText = "Restoring ${entry.versionTag}..."
                                                            AppUpdaterService.downloadAndTriggerInstall(
                                                                context = context,
                                                                apkUrl = entry.apkDownloadUrl,
                                                                snapshotBeforeInstall = false,
                                                                onProgressPercent = { pct -> downloadProgress = pct }
                                                            ).onSuccess {
                                                                isDownloadingUpdate = false
                                                                updateStatusText = "Ready! Confirm install for ${entry.versionTag}."
                                                            }.onFailure { err ->
                                                                isDownloadingUpdate = false
                                                                updateStatusText = "Restore failed: ${err.message}"
                                                            }
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Restore", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = entry.changelogNotes,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (isDownloadingUpdate) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        color = Color(0xFF10B981),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Downloading APK package... $downloadProgress%",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Primary Update & Check Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                updateStatusText = "Checking GitHub Releases..."
                                AppUpdaterService.checkLatestRelease()
                                    .onSuccess { info ->
                                        latestRelease = info
                                        updateStatusText = if (info.isNewerThanCurrent) {
                                            "New update available: ${info.tagName}"
                                        } else {
                                            "Up to date: ${info.tagName}"
                                        }
                                    }
                                    .onFailure { err ->
                                        updateStatusText = "Check result: ${err.message}"
                                    }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Check GitHub", fontSize = 12.sp)
                    }

                    Button(
                        enabled = !isDownloadingUpdate,
                        onClick = {
                            coroutineScope.launch {
                                isDownloadingUpdate = true
                                downloadProgress = 0
                                val targetUrl = latestRelease?.apkDownloadUrl
                                    ?: "https://github.com/unime1988/instapin-moto-g84/releases/latest/download/InstaPin-MotoG84.apk"
                                AppUpdaterService.downloadAndTriggerInstall(
                                    context = context,
                                    apkUrl = targetUrl,
                                    snapshotBeforeInstall = true,
                                    onProgressPercent = { pct -> downloadProgress = pct }
                                ).onSuccess {
                                    isDownloadingUpdate = false
                                    hasLocalRollbackApk = AppUpdaterService.hasLocalRollbackApk(context)
                                    updateStatusText = "Backed up current APK & DB! Confirm 'Update' in prompt."
                                }.onFailure { err ->
                                    isDownloadingUpdate = false
                                    updateStatusText = err.message ?: "Update failed"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download & Update", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 1-Tap Instant Local Rollback + JSON Data Snapshot Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                AppUpdaterService.triggerLocalRollbackInstall(context)
                                    .onSuccess {
                                        updateStatusText = "Launching cached Previous Working APK installer..."
                                    }
                                    .onFailure {
                                        showFullReleaseHistory = true
                                        updateStatusText = it.message ?: "Select a version from Time Machine above"
                                    }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (hasLocalRollbackApk) "Revert to Previous APK" else "Version Time Machine",
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onExportBackup { msg ->
                                backupStatusMessage = msg
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Backup DB", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            onRestoreBackup { msg ->
                                backupStatusMessage = msg
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Restore DB", fontSize = 11.sp)
                    }
                }

                if (backupStatusMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = backupStatusMessage,
                        color = Color(0xFF10B981),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. Live UI Theme, Layout & Text Formatting Switcher Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = Color(0xFFE60023))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Live UI Theme, Layout & Text Formatting",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Switch colors, Pinterest Masonry vs List layout, and text size instantly without rebuilding",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text("Color Theme Preset:", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = settings.themePreset == preset,
                            onClick = {
                                onSaveVisualAppearance(preset, settings.feedLayoutMode, settings.fontScalePreset)
                            },
                            label = { Text(preset.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text("Feed Layout Style:", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedLayoutMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.feedLayoutMode == mode,
                            onClick = {
                                onSaveVisualAppearance(settings.themePreset, mode, settings.fontScalePreset)
                            },
                            label = { Text(mode.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text("Text Size & Formatting Scale:", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontScalePreset.entries.forEach { scale ->
                        FilterChip(
                            selected = settings.fontScalePreset == scale,
                            onClick = {
                                onSaveVisualAppearance(settings.themePreset, settings.feedLayoutMode, scale)
                            },
                            label = { Text(scale.displayName, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 3. Multi-Provider Bring-Your-Own-Key (BYOK) & Android Keystore Encryption Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF8B5CF6))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Multi-Provider BYOK AI & Keystore Security",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "AES-256-GCM hardware-encrypted in Android Keystore • Header-only auth",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Select AI Provider:",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProviderType.entries.forEach { provider ->
                        val isSelected = selectedProvider == provider
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedProvider = provider
                                modelInput = provider.defaultModel
                            },
                            label = { Text(provider.displayName, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF8B5CF6),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("${selectedProvider.displayName} API Key (${selectedProvider.keyPlaceholder})") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    label = { Text("Model Name (default: ${selectedProvider.defaultModel})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // Script Duration & Tone Controls for Phase 2 Repurposing
                Text(
                    text = "Phase 2 Recreate Script Target Duration:",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 45, 60).forEach { sec ->
                        FilterChip(
                            selected = scriptDurationSec == sec,
                            onClick = { scriptDurationSec = sec },
                            label = { Text("${sec}s Reel") }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = scriptToneInput,
                    onValueChange = { scriptToneInput = it },
                    label = { Text("Script Tone & Language (e.g., English, Hinglish, Storytelling)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = nicheInput,
                    onValueChange = { nicheInput = it },
                    label = { Text("Your Creator Positioning / Niche") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (aiTestResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (aiTestResult.isSuccess) Color(0xFF10B981).copy(alpha = 0.16f) else Color(0xFFEF4444).copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = aiTestResult.message,
                            color = if (aiTestResult.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onTestAiConnection(
                                selectedProvider,
                                apiKeyInput,
                                modelInput,
                                scriptDurationSec,
                                scriptToneInput,
                                nicheInput
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Key", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            onSaveMultiProviderAi(
                                selectedProvider,
                                apiKeyInput,
                                modelInput,
                                scriptDurationSec,
                                scriptToneInput,
                                nicheInput
                            )
                            Toast.makeText(
                                context,
                                "Saved & Encrypted ${selectedProvider.displayName} settings in Android Keystore!",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("Save Encrypted Key", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        // 4. Moto G84 5G Hover & Accessibility Setup Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Moto G84 5G Hover & Accessibility Setup",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enable these two Android permissions so the Pinterest radial button hovers over Instagram and reads @author & caption automatically.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Layers,
                            contentDescription = null,
                            tint = if (hasOverlayPermission) Color(0xFF10B981) else Color(0xFFF59E0B),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "1. Display Over Other Apps",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (hasOverlayPermission) "Granted • Ready to hover" else "Required for floating Pinterest bubble",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { openOverlaySettings(context) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (hasOverlayPermission) "Configured" else "Grant")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (accessibilityConnected) Icons.Default.CheckCircle else Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            tint = if (accessibilityConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "2. InstaPin Context Reader",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (accessibilityConnected) {
                                    "Connected • Reading @author & caption live"
                                } else {
                                    "If greyed out on Moto G84: Settings -> Apps -> InstaPin -> ⋮ -> Allow restricted settings"
                                },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (accessibilityConnected) "Active" else "Enable")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                FloatingOverlayService.start(context, resetManualPause = true)
                                overlayRunning = true
                            } else {
                                openOverlaySettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Start Hover Bubble", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = {
                            FloatingOverlayService.stop(context)
                            overlayRunning = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                lastSaveTiming?.let { timing ->
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0B2E1F),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Last Pin Save Speed",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            val openPart = timing.tapToOpenCardMs?.let { "Bubble → Card: ${it}ms  •  " }.orEmpty()
                            Text(
                                text = "${openPart}Category Tap → Local Save: ${timing.localDbSaveMs}ms",
                                color = Color(0xFF34D399),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = timing.enrichmentStatus,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Last Reel-Link Capture Diagnostics: shows exactly which automated step
                // (Instagram on screen -> Share tap -> Copy link/InstaPin target) succeeded
                // or failed the last time you pinned from Instagram, so a "Legacy Pin" is never a mystery.
                lastCaptureReport?.let { report ->
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (report.succeeded) Color(0xFF0B2E1F) else Color(0xFF3A2A10),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Last Reel Link Capture",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = report.toReadableLine(),
                                color = if (report.succeeded) Color(0xFF34D399) else Color(0xFFFBBF24),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (report.note.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = report.note,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            if (!report.capturedUrl.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = report.capturedUrl,
                                    color = Color(0xFF34D399),
                                    fontSize = 11.sp
                                )
                            }
                            if (report.screenElements.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${report.screenElements.size} Instagram screen elements recorded at the failed step " +
                                        "(button labels, descriptions, IDs; text cut to 40 characters, kept on this phone only).",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    val fullText = buildString {
                                        appendLine("InstaPin ${BuildConfig.VERSION_NAME} - Reel link capture diagnostics")
                                        appendLine(report.toReadableLine())
                                        if (report.note.isNotBlank()) appendLine(report.note)
                                        if (report.screenElements.isNotEmpty()) {
                                            appendLine("--- Screen elements ---")
                                            report.screenElements.forEach { appendLine(it) }
                                        }
                                    }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("InstaPin diagnostics", fullText))
                                    Toast.makeText(context, "Diagnostics copied - paste it into the chat", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Copy diagnostics", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 5. Hover Behavior Preferences
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Hover & Auto-Analysis Preferences",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(10.dp))

                SettingToggleRow(
                    title = "Show Bubble Only When Instagram is Open",
                    subtitle = "Automatically hides the floating pin when you switch to other apps",
                    checked = settings.autoShowBubbleOnInstagramOnly,
                    onCheckedChange = {
                        onSaveOverlayPrefs(it, settings.autoCopyLinkWithAccessibility, settings.autoAnalyzePhase2OnSave)
                    }
                )

                SettingToggleRow(
                    title = "Auto-Click 'Copy Link' via Accessibility",
                    subtitle = "Captures the exact Reel permalink when you trigger a radial save",
                    checked = settings.autoCopyLinkWithAccessibility,
                    onCheckedChange = {
                        onSaveOverlayPrefs(settings.autoShowBubbleOnInstagramOnly, it, settings.autoAnalyzePhase2OnSave)
                    }
                )

                SettingToggleRow(
                    title = "Auto-Run Phase 2 Analysis on Every Save",
                    subtitle = "Immediately generates summary, hook breakdown & recreate script",
                    checked = settings.autoAnalyzePhase2OnSave,
                    onCheckedChange = {
                        onSaveOverlayPrefs(settings.autoShowBubbleOnInstagramOnly, settings.autoCopyLinkWithAccessibility, it)
                    }
                )
            }
        }
    }
}

@Composable
private fun VersionRowItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
