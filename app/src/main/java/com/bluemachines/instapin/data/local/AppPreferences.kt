package com.bluemachines.instapin.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "instapin_settings")

data class UserSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val autoShowBubbleOnInstagramOnly: Boolean = true,
    val autoCopyLinkWithAccessibility: Boolean = true,
    val autoAnalyzePhase2OnSave: Boolean = false,
    val creatorNicheDescription: String = "Educational content creator in Video Editing, Trading, Health, and Career growth",
    val bubbleSideRight: Boolean = true,
    val bubbleVerticalPercent: Int = 55
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
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            geminiApiKey = prefs[KEY_GEMINI_API] ?: "",
            geminiModel = prefs[KEY_GEMINI_MODEL] ?: "gemini-2.5-flash",
            autoShowBubbleOnInstagramOnly = prefs[KEY_AUTO_SHOW_IG_ONLY] ?: true,
            autoCopyLinkWithAccessibility = prefs[KEY_AUTO_COPY_LINK] ?: true,
            autoAnalyzePhase2OnSave = prefs[KEY_AUTO_ANALYZE_PHASE2] ?: false,
            creatorNicheDescription = prefs[KEY_CREATOR_NICHE]
                ?: "Educational content creator in Video Editing, Trading, Health, and Career growth",
            bubbleSideRight = prefs[KEY_BUBBLE_RIGHT] ?: true,
            bubbleVerticalPercent = prefs[KEY_BUBBLE_Y_PERCENT] ?: 55
        )
    }

    suspend fun getSettings(): UserSettings = settingsFlow.first()

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
