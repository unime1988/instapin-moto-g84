package com.bluemachines.instapin.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bluemachines.instapin.data.analytics.AnalyticsClient
import com.bluemachines.instapin.data.local.AiProviderType
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.local.AppThemePreset
import com.bluemachines.instapin.data.local.FeedLayoutMode
import com.bluemachines.instapin.data.local.FontScalePreset
import com.bluemachines.instapin.data.local.ReelLinkCaptureMode
import com.bluemachines.instapin.data.local.UserSettings
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardSection
import com.bluemachines.instapin.data.local.entity.BoardWithCount
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.local.entity.DomainSummary
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.data.local.entity.VideoPin
import com.bluemachines.instapin.data.remote.ApiConnectionTestResult
import com.bluemachines.instapin.data.remote.InstagramChatCaptureService
import com.bluemachines.instapin.data.remote.InstagramChatCaptureState
import com.bluemachines.instapin.data.repository.PinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DateFilterRange(val label: String) {
    ALL_TIME("All Time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days")
}

data class PinFilterState(
    val searchQuery: String = "",
    val selectedBoardId: Long? = null,
    val selectedMoodId: Long? = null,
    val selectedDomain: String? = null,
    val selectedAuthor: String? = null,
    val dateRange: DateFilterRange = DateFilterRange.ALL_TIME,
    val onlyPhase2Analyzed: Boolean = false
)

class MainViewModel(
    private val repository: PinRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    val filterState = MutableStateFlow(PinFilterState())

    private val _aiTestStatus = MutableStateFlow<ApiConnectionTestResult?>(null)
    val aiTestStatus: StateFlow<ApiConnectionTestResult?> = _aiTestStatus.asStateFlow()

    private val instagramChatService = InstagramChatCaptureService()
    private val _instagramChatState = MutableStateFlow(InstagramChatCaptureState())
    val instagramChatState: StateFlow<InstagramChatCaptureState> = _instagramChatState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = preferences.getSettings()
            if (saved.instagramChatDeviceToken.isNotBlank()) {
                _instagramChatState.value = InstagramChatCaptureState(
                    isConnected = saved.instagramChatConnected,
                    pairingCode = saved.instagramChatPairingCode,
                    pairingExpiresAt = saved.instagramChatPairingExpiresAt,
                    statusMessage = if (saved.instagramChatConnected) "Connected • checking for new Reels" else "Waiting for pairing code"
                )
                refreshInstagramChatCapture()
            }
        }
    }

    val allPins: StateFlow<List<PopulatedVideoPin>> = repository.allPinsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredPins: StateFlow<List<PopulatedVideoPin>> = combine(
        repository.allPinsFlow,
        filterState
    ) { pins, filter ->
        val now = System.currentTimeMillis()
        pins.filter { item ->
            val matchesQuery = if (filter.searchQuery.isBlank()) {
                true
            } else {
                val q = filter.searchQuery.lowercase()
                item.pin.caption.lowercase().contains(q) ||
                    item.pin.authorHandle.lowercase().contains(q) ||
                    item.pin.domainCategory.lowercase().contains(q) ||
                    item.pin.subjectTopic.lowercase().contains(q) ||
                    item.pin.userNotes.lowercase().contains(q) ||
                    item.boards.any { it.name.lowercase().contains(q) } ||
                    item.moods.any { it.name.lowercase().contains(q) }
            }

            val matchesBoard = filter.selectedBoardId == null ||
                item.boards.any { it.id == filter.selectedBoardId }

            val matchesMood = filter.selectedMoodId == null ||
                item.moods.any { it.id == filter.selectedMoodId }

            val matchesDomain = filter.selectedDomain == null ||
                item.pin.domainCategory.equals(filter.selectedDomain, ignoreCase = true)

            val matchesAuthor = filter.selectedAuthor == null ||
                item.pin.authorHandle.equals(filter.selectedAuthor, ignoreCase = true)

            val matchesDate = when (filter.dateRange) {
                DateFilterRange.ALL_TIME -> true
                DateFilterRange.TODAY -> (now - item.pin.savedAt) <= 24L * 3600_000L
                DateFilterRange.LAST_7_DAYS -> (now - item.pin.savedAt) <= 7L * 24L * 3600_000L
                DateFilterRange.LAST_30_DAYS -> (now - item.pin.savedAt) <= 30L * 24L * 3600_000L
            }

            val matchesAnalyzed = !filter.onlyPhase2Analyzed || item.analysis != null

            matchesQuery && matchesBoard && matchesMood && matchesDomain && matchesAuthor && matchesDate && matchesAnalyzed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val boardsWithCount: StateFlow<List<BoardWithCount>> = repository.boardsWithCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBoards: StateFlow<List<Board>> = repository.allBoardsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSections: StateFlow<List<BoardSection>> = repository.allSectionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMoods: StateFlow<List<MoodTag>> = repository.allMoodsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val authorSummaries: StateFlow<List<AuthorSummary>> = repository.authorSummariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val domainSummaries: StateFlow<List<DomainSummary>> = repository.domainSummariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val phase3TriagePins: StateFlow<List<PopulatedVideoPin>> = repository.phase3TriagePinsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val importBatches: StateFlow<List<ImportBatch>> = repository.importBatchesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun updateSearchQuery(query: String) {
        if (query.isNotBlank() && filterState.value.searchQuery.isBlank()) {
            AnalyticsClient.track("search_used")
        }
        filterState.value = filterState.value.copy(searchQuery = query)
    }

    fun selectBoardFilter(boardId: Long?) {
        AnalyticsClient.track("filter_used", "type" to "board", "cleared" to (boardId == null))
        filterState.value = filterState.value.copy(selectedBoardId = boardId)
    }

    fun selectMoodFilter(moodId: Long?) {
        AnalyticsClient.track("filter_used", "type" to "mood", "cleared" to (moodId == null))
        filterState.value = filterState.value.copy(selectedMoodId = moodId)
    }

    fun selectDomainFilter(domain: String?) {
        AnalyticsClient.track("filter_used", "type" to "domain", "cleared" to (domain == null))
        filterState.value = filterState.value.copy(selectedDomain = domain)
    }

    fun selectAuthorFilter(author: String?) {
        AnalyticsClient.track("filter_used", "type" to "author", "cleared" to (author == null))
        filterState.value = filterState.value.copy(selectedAuthor = author)
    }

    fun selectDateRange(range: DateFilterRange) {
        AnalyticsClient.track("filter_used", "type" to "date", "range" to range.name)
        filterState.value = filterState.value.copy(dateRange = range)
    }

    fun toggleOnlyAnalyzed(onlyAnalyzed: Boolean) {
        AnalyticsClient.track("filter_used", "type" to "analyzed", "enabled" to onlyAnalyzed)
        filterState.value = filterState.value.copy(onlyPhase2Analyzed = onlyAnalyzed)
    }

    fun clearAllFilters() {
        AnalyticsClient.track("filters_cleared")
        filterState.value = PinFilterState()
    }

    fun addManualPin(
        instagramUrl: String,
        authorHandle: String,
        caption: String,
        boardIds: List<Long>,
        moodIds: List<Long>,
        notes: String,
        runPhase2Now: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveOrUpdateInstagramPin(
                rawUrl = instagramUrl,
                screenAuthorHandle = authorHandle,
                screenCaption = caption,
                explicitBoardIds = boardIds,
                explicitMoodIds = moodIds,
                userNotes = notes,
                captureSource = CaptureSource.QUICK_SAVE_OVERLAY,
                runPhase2Immediately = runPhase2Now
            )
        }
    }

    fun createBoard(
        name: String,
        emoji: String,
        colorHex: String,
        description: String,
        pinToRadial: Boolean,
        autoAnalyzePhase2: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track(
                "board_created",
                "pinned_to_radial" to pinToRadial,
                "auto_analyze" to autoAnalyzePhase2
            )
            repository.createBoard(name, emoji, colorHex, description, pinToRadial, autoAnalyzePhase2)
        }
    }

    suspend fun createQuickSaveBoard(name: String): Long {
        AnalyticsClient.track("board_created", "context" to "quick_save")
        return repository.createQuickSaveBoard(name)
    }

    fun updateBoard(board: Board) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("board_updated")
            repository.updateBoard(board)
        }
    }

    fun setBoardQuickSaveVisibility(boardId: Long, visibleInSaveSheet: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("board_radial_toggled", "visible" to visibleInSaveSheet)
            repository.setBoardQuickSaveVisibility(boardId, visibleInSaveSheet)
        }
    }

    fun createBoardSection(boardId: Long, name: String, emoji: String = "📂") {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("section_created")
            repository.createBoardSection(boardId, name, emoji)
        }
    }

    fun copyPinToBoard(pinId: Long, targetBoardId: Long, targetSectionId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("pin_added_to_board")
            repository.copyPinToBoard(pinId, targetBoardId, targetSectionId)
        }
    }

    fun movePinToBoard(pinId: Long, sourceBoardId: Long?, targetBoardId: Long, targetSectionId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("pin_moved")
            repository.movePinToBoard(pinId, sourceBoardId, targetBoardId, targetSectionId)
        }
    }

    fun assignPinToSection(pinId: Long, boardId: Long, sectionId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("pin_assigned_section", "to_section" to (sectionId != null))
            repository.assignPinToSection(pinId, boardId, sectionId)
        }
    }

    fun createMood(name: String, emoji: String, colorHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("mood_created")
            repository.createMoodTag(name, emoji, colorHex)
        }
    }

    fun triggerPhase2Analysis(pinId: Long) {
        AnalyticsClient.track("analysis_requested", "source" to "manual_button")
        viewModelScope.launch(Dispatchers.IO) {
            repository.runPhase2DeepAnalysis(pinId)
        }
    }

    fun updatePinClassification(
        pinId: Long,
        boardIds: List<Long>,
        moodIds: List<Long>,
        domain: String,
        subject: String,
        notes: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track(
                "pin_recategorized",
                "boards_count" to boardIds.size,
                "moods_count" to moodIds.size
            )
            repository.updatePinBoardsAndMoods(pinId, boardIds, moodIds, domain, subject, notes)
        }
    }

    fun deletePin(pin: VideoPin) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("pin_deleted", "was_analyzed" to pin.isAnalyzedPhase2)
            repository.deletePin(pin)
        }
    }

    fun attachReelUrlToPin(pinId: Long, rawUrl: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.updatePinExactReelUrl(pinId, rawUrl)
            AnalyticsClient.track("link_attached", "success" to ok)
            onResult(ok)
        }
    }

    fun approveAllPhase3Triage() {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("triage_approved", "bulk" to true)
            repository.approveAllPhase3TriagePins()
        }
    }

    fun approveSinglePhase3Pin(pinId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("triage_approved", "bulk" to false)
            repository.approvePhase3Pin(pinId)
        }
    }

    fun runDemoSampleImportPhase3() {
        AnalyticsClient.track("demo_import_run")
        val sampleLikedJson = """
            {
              "likes_media_likes": [
                {
                  "title": "editing.mastery",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/reel/C8xEditCapCut01/",
                      "value": "3 CapCut keyframe speed ramp transitions for cinematic reels #videoediting #capcut #reels",
                      "timestamp": 1728900000
                    }
                  ]
                },
                {
                  "title": "priceaction.lab",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/reel/C8xTradeSetup02/",
                      "value": "How to spot institutional liquidity sweeps on 5m Gold & Nifty chart #trading #priceaction #forex",
                      "timestamp": 1728810000
                    }
                  ]
                },
                {
                  "title": "dr.longevity.fit",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/reel/C8xHealthPro03/",
                      "value": "Morning 10-minute mobility & high protein breakfast protocol for focus #health #fitness #nutrition",
                      "timestamp": 1728720000
                    }
                  ]
                },
                {
                  "title": "remote.career.hq",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/reel/C8xJobSearch04/",
                      "value": "5 AI resume & LinkedIn outreach prompts that land tech interviews in 2026 #jobs #career #remote",
                      "timestamp": 1728630000
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        viewModelScope.launch(Dispatchers.IO) {
            repository.importHistoricalInstagramJson(
                fileName = "sample_instagram_liked_posts.json",
                rawJsonContent = sampleLikedJson,
                enrichOnlineMetadata = false
            )
        }
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("settings_saved", "section" to "ai", "has_key" to apiKey.isNotBlank())
            preferences.updateGeminiApiKey(apiKey)
        }
    }

    fun saveMultiProviderAiSettings(
        provider: AiProviderType,
        apiKey: String,
        modelName: String,
        scriptDurationSec: Int,
        scriptTone: String,
        creatorNiche: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track(
                "settings_saved",
                "section" to "ai",
                "provider" to provider.name,
                "has_key" to apiKey.isNotBlank()
            )
            preferences.updateAiConfiguration(
                provider = provider,
                apiKey = apiKey,
                modelName = modelName,
                scriptDurationSec = scriptDurationSec,
                scriptTone = scriptTone,
                creatorNiche = creatorNiche
            )
        }
    }

    fun testAiConnectionNow(
        provider: AiProviderType,
        apiKey: String,
        modelName: String,
        scriptDurationSec: Int,
        scriptTone: String,
        creatorNiche: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.updateAiConfiguration(
                provider = provider,
                apiKey = apiKey,
                modelName = modelName,
                scriptDurationSec = scriptDurationSec,
                scriptTone = scriptTone,
                creatorNiche = creatorNiche
            )
            val result = repository.testActiveAiConnection()
            _aiTestStatus.value = result
            AnalyticsClient.track(
                "ai_connection_test",
                "provider" to provider.name,
                "success" to result.isSuccess
            )
        }
    }

    fun saveVisualAppearance(
        themePreset: AppThemePreset,
        layoutMode: FeedLayoutMode,
        fontScale: FontScalePreset
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track(
                "settings_saved",
                "section" to "appearance",
                "theme" to themePreset.name,
                "layout" to layoutMode.name,
                "font" to fontScale.name
            )
            preferences.updateVisualAppearance(themePreset, layoutMode, fontScale)
        }
    }

    fun exportManualBackup(context: Context, onComplete: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = repository.exportFullBackupSnapshotToFile(context)
                AnalyticsClient.track("backup_exported", "success" to true)
                onComplete("Saved full JSON backup (${file.name})")
            }.onFailure { err ->
                AnalyticsClient.track("backup_exported", "success" to false)
                onComplete("Backup failed: ${err.message}")
            }
        }
    }

    fun restoreManualBackup(context: Context, onComplete: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val count = repository.restoreFullBackupSnapshotFromFile(context)
                AnalyticsClient.track("backup_restored", "success" to true, "restored_count" to count)
                onComplete("Restored $count Pins & Boards from backup!")
            }.onFailure { err ->
                AnalyticsClient.track("backup_restored", "success" to false)
                onComplete("Restore failed: ${err.message}")
            }
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.setEnabled(enabled)
        }
    }

    fun connectInstagramChatCapture() {
        AnalyticsClient.track("dm_pairing_requested")
        viewModelScope.launch(Dispatchers.IO) {
            _instagramChatState.value = _instagramChatState.value.copy(
                isLoading = true,
                statusMessage = "Creating secure pairing code…"
            )
            runCatching { instagramChatService.createPairing() }
                .onSuccess { pairing ->
                    AnalyticsClient.track("dm_pairing_created")
                    preferences.saveInstagramChatPairing(
                        deviceToken = pairing.deviceToken,
                        pairingCode = pairing.pairingCode,
                        expiresAt = pairing.expiresAt
                    )
                    _instagramChatState.value = InstagramChatCaptureState(
                        pairingCode = pairing.pairingCode,
                        pairingExpiresAt = pairing.expiresAt,
                        instagramChatUrl = pairing.instagramChatUrl,
                        statusMessage = "Send this code to @${pairing.instagramUsername}"
                    )
                }
                .onFailure { error ->
                    AnalyticsClient.track("dm_pairing_failed")
                    _instagramChatState.value = InstagramChatCaptureState(
                        statusMessage = "Could not create pairing code: ${error.message?.take(80) ?: "try again"}"
                    )
                }
        }
    }

    fun refreshInstagramChatCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = preferences.getSettings()
            val token = saved.instagramChatDeviceToken
            if (token.isBlank()) return@launch
            _instagramChatState.value = _instagramChatState.value.copy(isLoading = true)
            runCatching {
                val pairing = instagramChatService.getPairingStatus(token)
                if (pairing.paired && !saved.instagramChatConnected) preferences.markInstagramChatConnected()
                val inbox = if (pairing.paired) instagramChatService.getInbox(token) else null
                var imported = 0
                inbox?.items?.forEach { item ->
                    repository.saveOrUpdateInstagramPin(
                        rawUrl = item.instagramUrl,
                        screenCaption = item.title,
                        userNotes = item.note,
                        captureSource = CaptureSource.INSTAGRAM_DM_BOT
                    )
                    instagramChatService.acknowledge(token, item.id)
                    imported++
                }
                pairing.paired to imported
            }.onSuccess { (paired, imported) ->
                AnalyticsClient.track(
                    "dm_sync_completed",
                    "paired" to paired,
                    "items" to imported
                )
                _instagramChatState.value = _instagramChatState.value.copy(
                    isLoading = false,
                    isConnected = paired,
                    pairingCode = if (paired || System.currentTimeMillis() > saved.instagramChatPairingExpiresAt) {
                        ""
                    } else {
                        saved.instagramChatPairingCode
                    },
                    statusMessage = when {
                        imported > 0 -> "$imported new Reel${if (imported == 1) "" else "s"} added to InstaPin"
                        paired -> "Connected • no new Reels"
                        System.currentTimeMillis() > saved.instagramChatPairingExpiresAt -> "Pairing code expired • create a new one"
                        else -> "Waiting for the code to be sent on Instagram"
                    },
                    lastSyncedCount = imported
                )
            }.onFailure { error ->
                AnalyticsClient.track("dm_sync_failed")
                _instagramChatState.value = _instagramChatState.value.copy(
                    isLoading = false,
                    statusMessage = "Sync unavailable: ${error.message?.take(80) ?: "try again"}"
                )
            }
        }
    }

    fun disconnectInstagramChatCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("dm_disconnected")
            val token = preferences.getSettings().instagramChatDeviceToken
            if (token.isNotBlank()) runCatching { instagramChatService.disconnect(token) }
            preferences.clearInstagramChatPairing()
            _instagramChatState.value = InstagramChatCaptureState(statusMessage = "Disconnected")
        }
    }

    fun saveOverlayPrefs(
        autoShowIgOnly: Boolean,
        reelLinkCaptureMode: ReelLinkCaptureMode,
        autoAnalyzePhase2: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track(
                "settings_saved",
                "section" to "overlay",
                "capture_mode" to reelLinkCaptureMode.name,
                "auto_analyze" to autoAnalyzePhase2,
                "ig_only" to autoShowIgOnly
            )
            preferences.updateOverlayPreferences(autoShowIgOnly, reelLinkCaptureMode, autoAnalyzePhase2)
        }
    }

    fun saveCreatorNiche(niche: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AnalyticsClient.track("settings_saved", "section" to "niche")
            preferences.updateCreatorNiche(niche)
        }
    }

    class Factory(
        private val repository: PinRepository,
        private val preferences: AppPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository, preferences) as T
        }
    }
}
