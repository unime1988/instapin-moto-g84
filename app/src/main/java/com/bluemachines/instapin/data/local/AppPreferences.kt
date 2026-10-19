package com.bluemachines.instapin.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bluemachines.instapin.BuildConfig
import com.bluemachines.instapin.security.AndroidKeystoreCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "instapin_settings")

enum class AiProviderType(val displayName: String, val defaultModel: String, val keyPlaceholder: String) {
    GEMINI("Google Gemini", "gemini-2.5-flash", "AIza..."),
    OPENAI("OpenAI (GPT-4o)", "gpt-4o-mini", "sk-..."),
    GROQ("Groq (Llama 3.3 70B)", "llama-3.3-70b-versatile", "gsk_..."),
    ANTHROPIC("Anthropic Claude", "claude-3-5-sonnet-latest", "sk-ant-...")
}

enum class AppThemePreset(val displayName: String) {
    OBSIDIAN("Pinterest Obsidian (Dark)"),
    AMOLED("Midnight AMOLED (Pure Black)"),
    DAYLIGHT("Clean Daylight (High-Contrast Light)")
}

enum class FeedLayoutMode(val displayName: String) {
    PINTEREST_MASONRY("2-Column Pinterest Masonry"),
    DETAILED_LIST("1-Column Detailed Feed")
}

enum class FontScalePreset(val displayName: String, val multiplier: Float) {
    COMPACT("Compact", 0.92f),
    STANDARD("Standard", 1.0f),
    LARGE("Large Comfortable", 1.12f)
}

enum class ReelLinkCaptureMode(val displayName: String, val description: String) {
    CLIPBOARD_FIRST(
        "Clipboard-first (Stable)",
        "You copy the Reel link first; InstaPin opens immediately and reads it."
    ),
    AUTOMATIC(
        "Automatic (Experimental)",
        "One tap: InstaPin waits for Instagram Share → Copy link, then opens the board chooser."
    ),
    INSTAGRAM_SHARE(
        "Instagram Share (Most Reliable)",
        "Use Instagram Share → Share to… → Pin to Board (InstaPin)."
    )
}

data class UserSettings(
    val aiProvider: AiProviderType = AiProviderType.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val scriptDurationSeconds: Int = 45,
    val scriptToneAndLanguage: String = "High-Retention Educational (English)",
    val autoShowBubbleOnInstagramOnly: Boolean = false,
    val reelLinkCaptureMode: ReelLinkCaptureMode = ReelLinkCaptureMode.CLIPBOARD_FIRST,
    val autoAnalyzePhase2OnSave: Boolean = false,
    val creatorNicheDescription: String = "Educational content creator in Video Editing, Trading, Health, and Career growth",
    val bubbleSideRight: Boolean = true,
    val bubbleVerticalPercent: Int = 55,
    val themePreset: AppThemePreset = AppThemePreset.OBSIDIAN,
    val feedLayoutMode: FeedLayoutMode = FeedLayoutMode.PINTEREST_MASONRY,
    val fontScalePreset: FontScalePreset = FontScalePreset.STANDARD,
    val instagramChatDeviceToken: String = "",
    val instagramChatPairingCode: String = "",
    val instagramChatPairingExpiresAt: Long = 0L,
    val instagramChatConnected: Boolean = false,
    val previousInstalledVersion: String = "v1.0.8 (Previous Build)",
    val lastRecordedCurrentVersion: String = BuildConfig.VERSION_NAME
)

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
        private val KEY_ENCRYPTED_API_KEY = stringPreferencesKey("encrypted_byok_api_key")
        private val KEY_LEGACY_GEMINI_API = stringPreferencesKey("gemini_api_key")
        private val KEY_AI_MODEL = stringPreferencesKey("gemini_model")
        private val KEY_SCRIPT_DURATION = intPreferencesKey("script_duration_sec")
        private val KEY_SCRIPT_TONE = stringPreferencesKey("script_tone_lang")
        private val KEY_AUTO_SHOW_IG_ONLY = booleanPreferencesKey("auto_show_ig_only")
        private val KEY_REEL_LINK_CAPTURE_MODE = stringPreferencesKey("reel_link_capture_mode")
        private val KEY_AUTO_ANALYZE_PHASE2 = booleanPreferencesKey("auto_analyze_phase2")
        private val KEY_CREATOR_NICHE = stringPreferencesKey("creator_niche")
        private val KEY_BUBBLE_RIGHT = booleanPreferencesKey("bubble_side_right")
        private val KEY_BUBBLE_Y_PERCENT = intPreferencesKey("bubble_y_percent")
        private val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        private val KEY_LAYOUT_MODE = stringPreferencesKey("feed_layout_mode")
        private val KEY_FONT_SCALE = stringPreferencesKey("font_scale_preset")
        private val KEY_ENCRYPTED_INSTAGRAM_CHAT_TOKEN = stringPreferencesKey("encrypted_instagram_chat_token")
        private val KEY_INSTAGRAM_CHAT_PAIRING_CODE = stringPreferencesKey("instagram_chat_pairing_code")
        private val KEY_INSTAGRAM_CHAT_PAIRING_EXPIRES = longPreferencesKey("instagram_chat_pairing_expires")
        private val KEY_INSTAGRAM_CHAT_CONNECTED = booleanPreferencesKey("instagram_chat_connected")
        private val KEY_PREVIOUS_VERSION = stringPreferencesKey("previous_installed_version")
        private val KEY_LAST_RECORDED_VERSION = stringPreferencesKey("last_recorded_version")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val provider = runCatching {
            AiProviderType.valueOf(prefs[KEY_AI_PROVIDER] ?: AiProviderType.GEMINI.name)
        }.getOrDefault(AiProviderType.GEMINI)

        val storedCipherOrLegacy = prefs[KEY_ENCRYPTED_API_KEY] ?: prefs[KEY_LEGACY_GEMINI_API].orEmpty()
        val decryptedApiKey = AndroidKeystoreCrypto.decryptSecret(storedCipherOrLegacy)

        val theme = runCatching {
            AppThemePreset.valueOf(prefs[KEY_THEME_PRESET] ?: AppThemePreset.OBSIDIAN.name)
        }.getOrDefault(AppThemePreset.OBSIDIAN)

        val layoutMode = runCatching {
            FeedLayoutMode.valueOf(prefs[KEY_LAYOUT_MODE] ?: FeedLayoutMode.PINTEREST_MASONRY.name)
        }.getOrDefault(FeedLayoutMode.PINTEREST_MASONRY)

        val fontScale = runCatching {
            FontScalePreset.valueOf(prefs[KEY_FONT_SCALE] ?: FontScalePreset.STANDARD.name)
        }.getOrDefault(FontScalePreset.STANDARD)

        val captureMode = runCatching {
            ReelLinkCaptureMode.valueOf(
                prefs[KEY_REEL_LINK_CAPTURE_MODE] ?: ReelLinkCaptureMode.CLIPBOARD_FIRST.name
            )
        }.getOrDefault(ReelLinkCaptureMode.CLIPBOARD_FIRST)

        UserSettings(
            aiProvider = provider,
            geminiApiKey = decryptedApiKey,
            geminiModel = prefs[KEY_AI_MODEL] ?: provider.defaultModel,
            scriptDurationSeconds = prefs[KEY_SCRIPT_DURATION] ?: 45,
            scriptToneAndLanguage = prefs[KEY_SCRIPT_TONE] ?: "High-Retention Educational (English)",
            autoShowBubbleOnInstagramOnly = prefs[KEY_AUTO_SHOW_IG_ONLY] ?: false,
            reelLinkCaptureMode = captureMode,
            autoAnalyzePhase2OnSave = prefs[KEY_AUTO_ANALYZE_PHASE2] ?: false,
            creatorNicheDescription = prefs[KEY_CREATOR_NICHE]
                ?: "Educational content creator in Video Editing, Trading, Health, and Career growth",
            bubbleSideRight = prefs[KEY_BUBBLE_RIGHT] ?: true,
            bubbleVerticalPercent = prefs[KEY_BUBBLE_Y_PERCENT] ?: 55,
            themePreset = theme,
            feedLayoutMode = layoutMode,
            fontScalePreset = fontScale,
            instagramChatDeviceToken = AndroidKeystoreCrypto.decryptSecret(
                prefs[KEY_ENCRYPTED_INSTAGRAM_CHAT_TOKEN].orEmpty()
            ),
            instagramChatPairingCode = prefs[KEY_INSTAGRAM_CHAT_PAIRING_CODE].orEmpty(),
            instagramChatPairingExpiresAt = prefs[KEY_INSTAGRAM_CHAT_PAIRING_EXPIRES] ?: 0L,
            instagramChatConnected = prefs[KEY_INSTAGRAM_CHAT_CONNECTED] ?: false,
            previousInstalledVersion = prefs[KEY_PREVIOUS_VERSION] ?: "v1.0.8 (Previous Build)",
            lastRecordedCurrentVersion = prefs[KEY_LAST_RECORDED_VERSION] ?: BuildConfig.VERSION_NAME
        )
    }

    suspend fun getSettings(): UserSettings = settingsFlow.first()

    suspend fun syncInstalledVersionOnStartup() {
        val currentVer = "v${BuildConfig.VERSION_NAME}"
        context.dataStore.edit { prefs ->
            val recorded = prefs[KEY_LAST_RECORDED_VERSION]
            if (recorded == null) {
                prefs[KEY_PREVIOUS_VERSION] = "v1.0.8"
                prefs[KEY_LAST_RECORDED_VERSION] = currentVer
            } else if (recorded != currentVer) {
                prefs[KEY_PREVIOUS_VERSION] = recorded
                prefs[KEY_LAST_RECORDED_VERSION] = currentVer
            }
            // Migrate any legacy plaintext key to AndroidKeystore AES-256-GCM
            val legacy = prefs[KEY_LEGACY_GEMINI_API]
            if (!legacy.isNullOrBlank()) {
                prefs[KEY_ENCRYPTED_API_KEY] = AndroidKeystoreCrypto.encryptSecret(legacy)
                prefs.remove(KEY_LEGACY_GEMINI_API)
            }
        }
    }

    suspend fun recordVersionBeforeUpdate() {
        val currentVer = "v${BuildConfig.VERSION_NAME}"
        context.dataStore.edit { prefs ->
            prefs[KEY_PREVIOUS_VERSION] = currentVer
        }
    }

    suspend fun updateGeminiApiKey(apiKey: String) {
        val cleanKey = apiKey.trim()
        val encrypted = AndroidKeystoreCrypto.encryptSecret(cleanKey)
        context.dataStore.edit {
            it[KEY_ENCRYPTED_API_KEY] = encrypted
            it.remove(KEY_LEGACY_GEMINI_API)
        }
    }

    suspend fun updateAiConfiguration(
        provider: AiProviderType,
        apiKey: String,
        modelName: String,
        scriptDurationSec: Int,
        scriptTone: String,
        creatorNiche: String
    ) {
        val encrypted = AndroidKeystoreCrypto.encryptSecret(apiKey.trim())
        context.dataStore.edit {
            it[KEY_AI_PROVIDER] = provider.name
            it[KEY_ENCRYPTED_API_KEY] = encrypted
            it.remove(KEY_LEGACY_GEMINI_API)
            it[KEY_AI_MODEL] = modelName.trim().ifBlank { provider.defaultModel }
            it[KEY_SCRIPT_DURATION] = scriptDurationSec
            it[KEY_SCRIPT_TONE] = scriptTone.trim()
            it[KEY_CREATOR_NICHE] = creatorNiche.trim()
        }
    }

    suspend fun updateVisualAppearance(
        themePreset: AppThemePreset,
        layoutMode: FeedLayoutMode,
        fontScale: FontScalePreset
    ) {
        context.dataStore.edit {
            it[KEY_THEME_PRESET] = themePreset.name
            it[KEY_LAYOUT_MODE] = layoutMode.name
            it[KEY_FONT_SCALE] = fontScale.name
        }
    }

    suspend fun updateOverlayPreferences(
        autoShowOnInstagramOnly: Boolean,
        reelLinkCaptureMode: ReelLinkCaptureMode,
        autoAnalyzePhase2OnSave: Boolean
    ) {
        context.dataStore.edit {
            it[KEY_AUTO_SHOW_IG_ONLY] = autoShowOnInstagramOnly
            it[KEY_REEL_LINK_CAPTURE_MODE] = reelLinkCaptureMode.name
            it[KEY_AUTO_ANALYZE_PHASE2] = autoAnalyzePhase2OnSave
        }
    }

    suspend fun updateCreatorNiche(niche: String) {
        context.dataStore.edit { it[KEY_CREATOR_NICHE] = niche.trim() }
    }

    suspend fun updateBubblePosition(isRight: Boolean, verticalPercent: Int) {
        context.dataStore.edit {
            it[KEY_BUBBLE_RIGHT] = isRight
            it[KEY_BUBBLE_Y_PERCENT] = verticalPercent.coerceIn(15, 85)
        }
    }

    suspend fun saveInstagramChatPairing(deviceToken: String, pairingCode: String, expiresAt: Long) {
        context.dataStore.edit {
            it[KEY_ENCRYPTED_INSTAGRAM_CHAT_TOKEN] = AndroidKeystoreCrypto.encryptSecret(deviceToken)
            it[KEY_INSTAGRAM_CHAT_PAIRING_CODE] = pairingCode
            it[KEY_INSTAGRAM_CHAT_PAIRING_EXPIRES] = expiresAt
            it[KEY_INSTAGRAM_CHAT_CONNECTED] = false
        }
    }

    suspend fun markInstagramChatConnected() {
        context.dataStore.edit {
            it[KEY_INSTAGRAM_CHAT_CONNECTED] = true
            it.remove(KEY_INSTAGRAM_CHAT_PAIRING_CODE)
            it.remove(KEY_INSTAGRAM_CHAT_PAIRING_EXPIRES)
        }
    }

    suspend fun clearInstagramChatPairing() {
        context.dataStore.edit {
            it.remove(KEY_ENCRYPTED_INSTAGRAM_CHAT_TOKEN)
            it.remove(KEY_INSTAGRAM_CHAT_PAIRING_CODE)
            it.remove(KEY_INSTAGRAM_CHAT_PAIRING_EXPIRES)
            it.remove(KEY_INSTAGRAM_CHAT_CONNECTED)
        }
    }
}
