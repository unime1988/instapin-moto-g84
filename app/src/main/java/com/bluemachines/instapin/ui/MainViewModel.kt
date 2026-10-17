package com.bluemachines.instapin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.local.UserSettings
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardWithCount
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.local.entity.DomainSummary
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.data.local.entity.VideoPin
import com.bluemachines.instapin.data.repository.PinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        filterState.value = filterState.value.copy(searchQuery = query)
    }

    fun selectBoardFilter(boardId: Long?) {
        filterState.value = filterState.value.copy(selectedBoardId = boardId)
    }

    fun selectMoodFilter(moodId: Long?) {
        filterState.value = filterState.value.copy(selectedMoodId = moodId)
    }

    fun selectDomainFilter(domain: String?) {
        filterState.value = filterState.value.copy(selectedDomain = domain)
    }

    fun selectAuthorFilter(author: String?) {
        filterState.value = filterState.value.copy(selectedAuthor = author)
    }

    fun selectDateRange(range: DateFilterRange) {
        filterState.value = filterState.value.copy(dateRange = range)
    }

    fun toggleOnlyAnalyzed(onlyAnalyzed: Boolean) {
        filterState.value = filterState.value.copy(onlyPhase2Analyzed = onlyAnalyzed)
    }

    fun clearAllFilters() {
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
            repository.createBoard(name, emoji, colorHex, description, pinToRadial, autoAnalyzePhase2)
        }
    }

    fun createMood(name: String, emoji: String, colorHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createMoodTag(name, emoji, colorHex)
        }
    }

    fun triggerPhase2Analysis(pinId: Long) {
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
            repository.updatePinBoardsAndMoods(pinId, boardIds, moodIds, domain, subject, notes)
        }
    }

    fun deletePin(pin: VideoPin) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePin(pin)
        }
    }

    fun approveAllPhase3Triage() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.approveAllPhase3TriagePins()
        }
    }

    fun approveSinglePhase3Pin(pinId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.approvePhase3Pin(pinId)
        }
    }

    fun runDemoSampleImportPhase3() {
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
            preferences.updateGeminiApiKey(apiKey)
        }
    }

    fun saveOverlayPrefs(
        autoShowIgOnly: Boolean,
        autoCopyLink: Boolean,
        autoAnalyzePhase2: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.updateOverlayPreferences(autoShowIgOnly, autoCopyLink, autoAnalyzePhase2)
        }
    }

    fun saveCreatorNiche(niche: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
