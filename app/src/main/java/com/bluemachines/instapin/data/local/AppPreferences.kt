package com.bluemachines.instapin.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bluemachines.instapin.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "instapin_settings")

data class UserSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val autoShowBubbleOnInstagramOnly: Boolean = false,
    val autoCopyLinkWithAccessibility: Boolean = true,
    val autoAnalyzePhase2OnSave: Boolean = false,
    val creatorNicheDescription: String = "Educational content creator in Video Editing, Trading, Health, and Career growth",
    val bubbleSideRight: Boolean = true,
    val bubbleVerticalPercent: Int = 55,
    val previousInstalledVersion: String = "None (First Install)",
    val lastRecordedCurrentVersion: String = BuildConfig.VERSION_NAME
)

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_GEMINI_API = stringPreferencesKey("gemini_api_key")
        private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        private val KEY_AUTO_SHOW_IG_ONLY = booleanPreferencesKey("auto_show_ig_only")
        private val KEY_AUTO_COPY_LINK = booleanPreferencesKey("auto_copy_link")
        private val KEY_AUTO_ANALYZE_PHASE2 = booleanPreferencesKey("auto_analyze_phase2")
        private val KEY_CREATOR_NICHE = stringPreferencesKey("creator_niche")
        private val KEY_BUBBLE_RIGHT = booleanPreferencesKey("bubble_side_right")
        private val KEY_BUBBLE_Y_PERCENT = intPreferencesKey("bubble_y_percent")
        private val KEY_PREVIOUS_VERSION = stringPreferencesKey("previous_installed_version")
        private val KEY_LAST_RECORDED_VERSION = stringPreferencesKey("last_recorded_version")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            geminiApiKey = prefs[KEY_GEMINI_API] ?: "",
            geminiModel = prefs[KEY_GEMINI_MODEL] ?: "gemini-2.5-flash",
            autoShowBubbleOnInstagramOnly = prefs[KEY_AUTO_SHOW_IG_ONLY] ?: false,
            autoCopyLinkWithAccessibility = prefs[KEY_AUTO_COPY_LINK] ?: true,
            autoAnalyzePhase2OnSave = prefs[KEY_AUTO_ANALYZE_PHASE2] ?: false,
            creatorNicheDescription = prefs[KEY_CREATOR_NICHE]
                ?: "Educational content creator in Video Editing, Trading, Health, and Career growth",
            bubbleSideRight = prefs[KEY_BUBBLE_RIGHT] ?: true,
            bubbleVerticalPercent = prefs[KEY_BUBBLE_Y_PERCENT] ?: 55,
            previousInstalledVersion = prefs[KEY_PREVIOUS_VERSION] ?: "v1.0.3 (Initial Install)",
            lastRecordedCurrentVersion = prefs[KEY_LAST_RECORDED_VERSION] ?: BuildConfig.VERSION_NAME
        )
    }

    suspend fun getSettings(): UserSettings = settingsFlow.first()

    /**
     * Automatically tracks version transitions on app startup or before an in-app update
     * so the user can always see "Previous Version -> Current Version".
     */
    suspend fun syncInstalledVersionOnStartup() {
        val currentVer = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        context.dataStore.edit { prefs ->
            val recorded = prefs[KEY_LAST_RECORDED_VERSION]
            if (recorded == null) {
                prefs[KEY_PREVIOUS_VERSION] = "v1.0.3 (Initial Install)"
                prefs[KEY_LAST_RECORDED_VERSION] = currentVer
            } else if (recorded != currentVer) {
                prefs[KEY_PREVIOUS_VERSION] = recorded
                prefs[KEY_LAST_RECORDED_VERSION] = currentVer
            }
        }
    }

    suspend fun recordVersionBeforeUpdate() {
        val currentVer = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        context.dataStore.edit { prefs ->
            prefs[KEY_PREVIOUS_VERSION] = currentVer
        }
    }

    suspend fun updateGeminiApiKey(apiKey: String) {
        context.dataStore.edit { it[KEY_GEMINI_API] = apiKey.trim() }
    }

    suspend fun updateOverlayPreferences(
        autoShowOnInstagramOnly: Boolean,
        autoCopyLinkWithAccessibility: Boolean,
        autoAnalyzePhase2OnSave: Boolean
    ) {
        context.dataStore.edit {
            it[KEY_AUTO_SHOW_IG_ONLY] = autoShowOnInstagramOnly
            it[KEY_AUTO_COPY_LINK] = autoCopyLinkWithAccessibility
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
}
