package com.bluemachines.instapin.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.UserSettings
import com.bluemachines.instapin.service.FloatingOverlayService
import com.bluemachines.instapin.service.InstaPinAccessibilityService

@Composable
fun MotoG84OnboardingAndSettingsScreen(
    settings: UserSettings,
    onSaveApiKey: (String) -> Unit,
    onSaveOverlayPrefs: (Boolean, Boolean, Boolean) -> Unit,
    onSaveCreatorNiche: (String) -> Unit
) {
    val context = LocalContext.current
    val hasOverlayPermission = Settings.canDrawOverlays(context)
    val accessibilityConnected by InstaPinAccessibilityService.isServiceConnected.collectAsState()

    var apiKeyInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var nicheInput by remember(settings.creatorNicheDescription) { mutableStateOf(settings.creatorNicheDescription) }
    var overlayRunning by remember { mutableStateOf(FloatingOverlayService.isRunning) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Moto G84 5G Hover Setup Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Moto G84 5G Hover & Accessibility Setup",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enable these two Android permissions so the Pinterest radial button hovers over Instagram and reads @author & caption automatically.",
                    color = Color(0xFFA0A0B8),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(14.dp))

                // Step 1: Display Over Other Apps
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
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (hasOverlayPermission) "Granted • Ready to hover" else "Required for floating Pinterest bubble",
                                color = Color(0xFFA0A0B8),
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

                // Step 2: Accessibility Service
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
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (accessibilityConnected) {
                                    "Connected • Reading @author & caption live"
                                } else {
                                    "Detects Instagram & reads Reel author/caption"
                                },
                                color = Color(0xFFA0A0B8),
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

                // Start / Stop Hover Service Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                FloatingOverlayService.start(context)
                                overlayRunning = true
                            } else {
                                openOverlaySettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start Hover Bubble", fontWeight = FontWeight.Bold)
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
            }
        }

        // Hover Behavior Preferences
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Hover & Auto-Analysis Preferences",
                    color = Color.White,
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

        // Google Gemini API Configuration (Phase 1, 2 & 3)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF8B5CF6))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Google Gemini AI Configuration",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Powers Phase 1 Domain/Subject categorization, Phase 2 Deep Video Breakdown & Recreate Scripts, and Phase 3 Bulk Historical Sorting. Works offline with smart local rules if left blank.",
                    color = Color(0xFFA0A0B8),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Gemini API Key (AIza...)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = nicheInput,
                    onValueChange = { nicheInput = it },
                    label = { Text("Your Creator Style / Niche (for Phase 2 Script Rewriting)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        onSaveApiKey(apiKeyInput)
                        onSaveCreatorNiche(nicheInput)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save AI Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
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
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color(0xFFA0A0B8), fontSize = 11.sp)
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
