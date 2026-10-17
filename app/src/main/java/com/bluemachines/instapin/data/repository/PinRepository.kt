package com.bluemachines.instapin.data.repository

import com.bluemachines.instapin.data.local.AppDatabase
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardWithCount
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.local.entity.DomainSummary
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PinBoardCrossRef
import com.bluemachines.instapin.data.local.entity.PinMoodCrossRef
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.data.local.entity.VideoAnalysis
import com.bluemachines.instapin.data.local.entity.VideoPin
import com.bluemachines.instapin.data.remote.GeminiAiService
import com.bluemachines.instapin.data.remote.InstagramExportJsonParser
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import kotlinx.coroutines.flow.Flow

class PinRepository(
    private val database: AppDatabase,
    private val preferences: AppPreferences,
    private val metadataExtractor: InstagramMetadataExtractor = InstagramMetadataExtractor(),
    private val geminiAiService: GeminiAiService = GeminiAiService()
) {
    private val pinDao = database.pinDao()
    private val boardDao = database.boardDao()
    private val analysisDao = database.analysisDao()
    private val importDao = database.importDao()

    val allPinsFlow: Flow<List<PopulatedVideoPin>> = pinDao.observeAllPopulatedPins()
    val phase3TriagePinsFlow: Flow<List<PopulatedVideoPin>> = pinDao.observePhase3TriagePins()
    val boardsWithCountFlow: Flow<List<BoardWithCount>> = boardDao.observeBoardsWithCount()
    val allBoardsFlow: Flow<List<Board>> = boardDao.observeAllBoards()
    val radialBoardsFlow: Flow<List<Board>> = boardDao.observeRadialBoards()
    val allMoodsFlow: Flow<List<MoodTag>> = boardDao.observeAllMoodTags()
    val authorSummariesFlow: Flow<List<AuthorSummary>> = pinDao.observeAuthorSummaries()
    val domainSummariesFlow: Flow<List<DomainSummary>> = pinDao.observeDomainSummaries()
    val importBatchesFlow: Flow<List<ImportBatch>> = importDao.observeImportBatches()

    fun observePinById(pinId: Long): Flow<PopulatedVideoPin?> = pinDao.observePopulatedPinById(pinId)

    /**
     * Phase 1 Core Save Pipeline:
     * 1. Normalizes Instagram URL & enriches with OpenGraph metadata (without downloading video).
     * 2. Classifies Domain, Subject, Boards, and Moods via Gemini Flash (or instant local heuristic).
     * 3. Stores the VideoPin instance and links it to 1 or multiple Boards and Moods.
     */
    suspend fun saveOrUpdateInstagramPin(
        rawUrl: String?,
        screenAuthorHandle: String = "",
        screenCaption: String = "",
        screenAudioTrack: String = "",
        explicitBoardIds: List<Long> = emptyList(),
        explicitMoodIds: List<Long> = emptyList(),
        userNotes: String = "",
        captureSource: CaptureSource = CaptureSource.LIVE_RADIAL_OVERLAY,
        runPhase2Immediately: Boolean = false
    ): PopulatedVideoPin {
        val extracted = metadataExtractor.enrichMetadata(
            rawUrl = rawUrl,
            screenAuthorHandle = screenAuthorHandle,
            screenCaption = screenCaption,
            screenAudioTrack = screenAudioTrack
        )

        val settings = preferences.getSettings()
        val allBoards = boardDao.getAllBoards()
        val allMoods = boardDao.getAllMoodTags()

        val classification = geminiAiService.classifyPinPhase1(
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel,
            authorHandle = extracted.authorHandle,
            caption = extracted.caption,
            audioTrack = extracted.audioTrackName,
            availableBoardNames = allBoards.map { it.name },
            availableMoodNames = allMoods.map { it.name }
        )

        val existingPin = pinDao.getPinByShortcode(extracted.shortcode)
        val now = System.currentTimeMillis()

        val pinToSave = if (existingPin != null) {
            existingPin.copy(
                instagramUrl = extracted.normalizedUrl,
                authorHandle = extracted.authorHandle.ifBlank { existingPin.authorHandle },
                authorDisplayName = extracted.authorDisplayName.ifBlank { existingPin.authorDisplayName },
                caption = extracted.caption.ifBlank { existingPin.caption },
                audioTrackName = extracted.audioTrackName.ifBlank { existingPin.audioTrackName },
                thumbnailUrl = extracted.thumbnailUrl.ifBlank { existingPin.thumbnailUrl },
                domainCategory = classification.domainCategory.ifBlank { existingPin.domainCategory },
                subjectTopic = classification.subjectTopic.ifBlank { existingPin.subjectTopic },
                hashtagsCsv = extracted.hashtags.joinToString(","),
                userNotes = if (userNotes.isNotBlank()) userNotes else existingPin.userNotes,
                savedAt = now
            )
        } else {
            VideoPin(
                instagramUrl = extracted.normalizedUrl,
                shortcode = extracted.shortcode,
                authorHandle = extracted.authorHandle,
                authorDisplayName = extracted.authorDisplayName,
                caption = extracted.caption.ifBlank { classification.oneLineSummary },
                audioTrackName = extracted.audioTrackName,
                thumbnailUrl = extracted.thumbnailUrl,
                domainCategory = classification.domainCategory,
                subjectTopic = classification.subjectTopic,
                hashtagsCsv = extracted.hashtags.joinToString(","),
                savedAt = now,
                userNotes = userNotes,
                isReachable = extracted.isReachable,
                captureSource = captureSource
            )
        }

        val pinId = pinDao.insertPin(pinToSave)

        // Resolve Board assignments (explicitly chosen boards OR AI-matched boards)
        val targetBoardIds = if (explicitBoardIds.isNotEmpty()) {
            explicitBoardIds
        } else {
            val matchedIds = classification.suggestedBoards.mapNotNull { suggestedName ->
                allBoards.firstOrNull { it.name.equals(suggestedName, ignoreCase = true) }?.id
            }
            matchedIds.ifEmpty { listOfNotNull(allBoards.firstOrNull()?.id) }
        }

        targetBoardIds.forEach { boardId ->
            pinDao.insertPinBoardCrossRef(PinBoardCrossRef(pinId = pinId, boardId = boardId))
        }

        // Resolve Mood assignments
        val targetMoodIds = if (explicitMoodIds.isNotEmpty()) {
            explicitMoodIds
        } else {
            classification.suggestedMoods.mapNotNull { suggestedMood ->
                allMoods.firstOrNull { it.name.equals(suggestedMood, ignoreCase = true) }?.id
            }
        }

        targetMoodIds.forEach { moodId ->
            pinDao.insertPinMoodCrossRef(PinMoodCrossRef(pinId = pinId, moodId = moodId))
        }

        // Check if Phase 2 Deep Analysis should run automatically
        val boardRequiresAutoPhase2 = allBoards.any { it.id in targetBoardIds && it.autoAnalyzePhase2 }
        if (runPhase2Immediately || settings.autoAnalyzePhase2OnSave || boardRequiresAutoPhase2) {
            runCatching {
                runPhase2DeepAnalysis(pinId)
            }
        }

        return pinDao.getPopulatedPinById(pinId)!!
    }

    /**
     * Phase 2: Runs deep AI video analysis, transcript/hook breakdown, and generates a recreate script & shot list.
     */
    suspend fun runPhase2DeepAnalysis(pinId: Long): VideoAnalysis? {
        val populated = pinDao.getPopulatedPinById(pinId) ?: return null
        val settings = preferences.getSettings()
        val analysis = geminiAiService.analyzeVideoPhase2(
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel,
            pin = populated.pin,
            creatorNiche = settings.creatorNicheDescription
        )
        analysisDao.insertOrUpdateAnalysis(analysis)
        pinDao.updatePin(populated.pin.copy(isAnalyzedPhase2 = true))
        return analysis
    }

    /**
     * Phase 3: Imports historical Instagram `liked_posts.json` / `saved_posts.json` export,
     * deduplicates against existing pins, enriches metadata, and auto-categorizes into Boards & Domains.
     */
    suspend fun importHistoricalInstagramJson(
        fileName: String,
        rawJsonContent: String,
        enrichOnlineMetadata: Boolean = false,
        onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportBatch {
        val parsedItems = InstagramExportJsonParser.parseExportJson(rawJsonContent)
        val batchId = importDao.insertBatch(
            ImportBatch(
                sourceFileName = fileName,
                totalDiscovered = parsedItems.size,
                status = "IN_PROGRESS"
            )
        )

        var newlyImported = 0
        var duplicatesSkipped = 0
        var aiCategorizedCount = 0

        val allBoards = boardDao.getAllBoards()
        val allMoods = boardDao.getAllMoodTags()
        val settings = preferences.getSettings()

        parsedItems.forEachIndexed { index, item ->
            val existing = pinDao.getPinByShortcode(item.shortcode)
            if (existing != null) {
                duplicatesSkipped++
            } else {
                val enriched = if (enrichOnlineMetadata && index < 40) {
                    metadataExtractor.enrichMetadata(
                        rawUrl = item.instagramUrl,
                        screenAuthorHandle = item.authorHandle,
                        screenCaption = item.titleOrCaption
                    )
                } else {
                    null
                }

                val effectiveAuthor = enriched?.authorHandle?.ifBlank { item.authorHandle } ?: item.authorHandle
                val effectiveCaption = enriched?.caption?.ifBlank { item.titleOrCaption } ?: item.titleOrCaption

                val classification = if (settings.geminiApiKey.isNotBlank() && index < 25) {
                    geminiAiService.classifyPinPhase1(
                        apiKey = settings.geminiApiKey,
                        model = settings.geminiModel,
                        authorHandle = effectiveAuthor,
                        caption = effectiveCaption,
                        audioTrack = "",
                        availableBoardNames = allBoards.map { it.name },
                        availableMoodNames = allMoods.map { it.name }
                    )
                } else {
                    geminiAiService.heuristicClassify(
                        authorHandle = effectiveAuthor,
                        caption = effectiveCaption,
                        availableBoards = allBoards.map { it.name },
                        availableMoods = allMoods.map { it.name }
                    )
                }

                val pinId = pinDao.insertPin(
                    VideoPin(
                        instagramUrl = item.instagramUrl,
                        shortcode = item.shortcode,
                        authorHandle = effectiveAuthor.ifBlank { "unknown_creator" },
                        authorDisplayName = enriched?.authorDisplayName.orEmpty(),
                        caption = effectiveCaption.ifBlank { classification.oneLineSummary },
                        thumbnailUrl = enriched?.thumbnailUrl.orEmpty(),
                        domainCategory = classification.domainCategory,
                        subjectTopic = classification.subjectTopic,
                        savedAt = System.currentTimeMillis(),
                        originalLikedOrPostedAt = item.timestampMillis,
                        isPendingTriagePhase3 = true,
                        isReachable = enriched?.isReachable ?: true,
                        captureSource = CaptureSource.PHASE3_HISTORICAL_IMPORT
                    )
                )

                val matchedBoardIds = classification.suggestedBoards.mapNotNull { boardName ->
                    allBoards.firstOrNull { it.name.equals(boardName, ignoreCase = true) }?.id
                }.ifEmpty { listOfNotNull(allBoards.firstOrNull()?.id) }

                matchedBoardIds.forEach { boardId ->
                    pinDao.insertPinBoardCrossRef(PinBoardCrossRef(pinId = pinId, boardId = boardId))
                }

                newlyImported++
                aiCategorizedCount++
            }

            onProgress(index + 1, parsedItems.size)
        }

        val finishedBatch = ImportBatch(
            id = batchId,
            sourceFileName = fileName,
            totalDiscovered = parsedItems.size,
            newlyImported = newlyImported,
            duplicatesSkipped = duplicatesSkipped,
            aiCategorizedCount = aiCategorizedCount,
            status = "COMPLETED",
            completedAt = System.currentTimeMillis()
        )
        importDao.updateBatch(finishedBatch)
        return finishedBatch
    }

    suspend fun createBoard(
        name: String,
        emoji: String,
        colorHex: String,
        description: String,
        pinToRadial: Boolean,
        autoAnalyzePhase2: Boolean
    ): Long {
        val currentBoards = boardDao.getAllBoards()
        return boardDao.insertBoard(
            Board(
                name = name.trim(),
                description = description.trim(),
                emoji = emoji.ifBlank { "📌" },
                colorHex = colorHex,
                isPinnedToRadial = pinToRadial,
                radialSlotIndex = currentBoards.size,
                autoAnalyzePhase2 = autoAnalyzePhase2
            )
        )
    }

    suspend fun createMoodTag(name: String, emoji: String, colorHex: String): Long {
        return boardDao.insertMoodTag(
            MoodTag(
                name = name.trim(),
                emoji = emoji.ifBlank { "✨" },
                colorHex = colorHex
            )
        )
    }

    suspend fun updatePinBoardsAndMoods(
        pinId: Long,
        boardIds: List<Long>,
        moodIds: List<Long>,
        domainCategory: String,
        subjectTopic: String,
        userNotes: String
    ) {
        val existing = pinDao.getPopulatedPinById(pinId)?.pin ?: return
        pinDao.updatePin(
            existing.copy(
                domainCategory = domainCategory,
                subjectTopic = subjectTopic,
                userNotes = userNotes,
                isPendingTriagePhase3 = false
            )
        )
        pinDao.clearBoardsForPin(pinId)
        boardIds.forEach { boardId ->
            pinDao.insertPinBoardCrossRef(PinBoardCrossRef(pinId = pinId, boardId = boardId))
        }
        pinDao.clearMoodsForPin(pinId)
        moodIds.forEach { moodId ->
            pinDao.insertPinMoodCrossRef(PinMoodCrossRef(pinId = pinId, moodId = moodId))
        }
    }

    suspend fun deletePin(pin: VideoPin) {
        pinDao.deletePin(pin)
    }

    suspend fun approveAllPhase3TriagePins() {
        pinDao.markAllPhase3PinsApproved()
    }

    suspend fun approvePhase3Pin(pinId: Long) {
        pinDao.markPhase3PinsApproved(listOf(pinId))
    }
}
