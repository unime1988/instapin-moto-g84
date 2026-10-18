package com.bluemachines.instapin.data.repository

import android.content.Context
import com.bluemachines.instapin.BuildConfig
import com.bluemachines.instapin.data.local.AppDatabase
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardSection
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
import com.bluemachines.instapin.data.remote.ApiConnectionTestResult
import com.bluemachines.instapin.data.remote.GeminiAiService
import com.bluemachines.instapin.data.remote.InstagramExportJsonParser
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class SaveTimingReport(
    val timestampMillis: Long = System.currentTimeMillis(),
    val shortcode: String = "",
    val authorHandle: String = "",
    val boardSummary: String = "",
    val tapToOpenCardMs: Long? = null,
    val localDbSaveMs: Long = 0L,
    val backgroundMetadataMs: Long? = null,
    val backgroundAiMs: Long? = null,
    val enrichmentStatus: String = "Saved locally (enriching in background...)"
)

@Serializable
data class BackupPinItem(
    val pin: VideoPin,
    val boardNames: List<String> = emptyList(),
    val moodNames: List<String> = emptyList(),
    val analysis: VideoAnalysis? = null
)

@Serializable
data class FullDatabaseSnapshot(
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val boards: List<Board> = emptyList(),
    val moods: List<MoodTag> = emptyList(),
    val pins: List<BackupPinItem> = emptyList()
)

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
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val _lastSaveTiming = MutableStateFlow<SaveTimingReport?>(null)
        val lastSaveTiming: StateFlow<SaveTimingReport?> = _lastSaveTiming.asStateFlow()
    }

    private val snapshotJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    val allPinsFlow: Flow<List<PopulatedVideoPin>> = pinDao.observeAllPopulatedPins()
    val phase3TriagePinsFlow: Flow<List<PopulatedVideoPin>> = pinDao.observePhase3TriagePins()
    val boardsWithCountFlow: Flow<List<BoardWithCount>> = boardDao.observeBoardsWithCount()
    val allBoardsFlow: Flow<List<Board>> = boardDao.observeAllBoards()
    val allSectionsFlow: Flow<List<BoardSection>> = boardDao.observeAllSections()
    val radialBoardsFlow: Flow<List<Board>> = boardDao.observeRadialBoards()
    val allMoodsFlow: Flow<List<MoodTag>> = boardDao.observeAllMoodTags()
    val authorSummariesFlow: Flow<List<AuthorSummary>> = pinDao.observeAuthorSummaries()
    val domainSummariesFlow: Flow<List<DomainSummary>> = pinDao.observeDomainSummaries()
    val importBatchesFlow: Flow<List<ImportBatch>> = importDao.observeImportBatches()

    fun observePinById(pinId: Long): Flow<PopulatedVideoPin?> = pinDao.observePopulatedPinById(pinId)

    suspend fun testActiveAiConnection(): ApiConnectionTestResult {
        val settings = preferences.getSettings()
        return geminiAiService.testApiConnection(
            provider = settings.aiProvider,
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel
        )
    }

    /**
     * Phase 1 Core Save Pipeline (Instant Local-First):
     * 1. Normalizes the Instagram URL & classifies immediately using the local heuristic (<20ms).
     * 2. Saves the VideoPin + Board/Mood links into Room immediately and returns so the overlay
     *    dismisses in milliseconds.
     * 3. Launches OpenGraph metadata enrichment + BYOK AI classification + optional Phase 2
     *    in `backgroundScope` so network timeouts never block the user's save tap.
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
        runPhase2Immediately: Boolean = false,
        tapToOpenCardMs: Long? = null
    ): PopulatedVideoPin {
        val saveStartedAt = System.currentTimeMillis()
        val (normalizedUrl, shortcode) = InstagramMetadataExtractor.normalizeInstagramUrl(
            rawUrl = rawUrl,
            fallbackAuthor = screenAuthorHandle,
            fallbackCaption = screenCaption
        )

        val allBoards = boardDao.getAllBoards()
        val allMoods = boardDao.getAllMoodTags()
        val cleanScreenAuthor = screenAuthorHandle.removePrefix("@").trim().ifBlank { "instagram_reel" }
        val cleanScreenCaption = screenCaption.trim()
        val initialHashtags = InstagramMetadataExtractor.extractHashtags(cleanScreenCaption)

        val localClassification = geminiAiService.heuristicClassify(
            authorHandle = cleanScreenAuthor,
            caption = cleanScreenCaption,
            availableBoards = allBoards.map { it.name },
            availableMoods = allMoods.map { it.name }
        )

        val existingPin = pinDao.getPinByShortcode(shortcode)
        val now = System.currentTimeMillis()

        val pinToSave = if (existingPin != null) {
            existingPin.copy(
                instagramUrl = normalizedUrl,
                authorHandle = if (cleanScreenAuthor != "instagram_reel") cleanScreenAuthor else existingPin.authorHandle,
                caption = cleanScreenCaption.ifBlank { existingPin.caption },
                audioTrackName = screenAudioTrack.ifBlank { existingPin.audioTrackName },
                domainCategory = localClassification.domainCategory.ifBlank { existingPin.domainCategory },
                subjectTopic = localClassification.subjectTopic.ifBlank { existingPin.subjectTopic },
                hashtagsCsv = if (initialHashtags.isNotEmpty()) initialHashtags.joinToString(",") else existingPin.hashtagsCsv,
                userNotes = if (userNotes.isNotBlank()) userNotes else existingPin.userNotes,
                savedAt = now
            )
        } else {
            VideoPin(
                instagramUrl = normalizedUrl,
                shortcode = shortcode,
                authorHandle = cleanScreenAuthor,
                authorDisplayName = "",
                caption = cleanScreenCaption.ifBlank { localClassification.oneLineSummary },
                audioTrackName = screenAudioTrack,
                thumbnailUrl = "",
                domainCategory = localClassification.domainCategory,
                subjectTopic = localClassification.subjectTopic,
                hashtagsCsv = initialHashtags.joinToString(","),
                savedAt = now,
                userNotes = userNotes,
                isReachable = true,
                captureSource = captureSource
            )
        }

        val pinId = if (existingPin != null) {
            pinDao.updatePin(pinToSave)
            existingPin.id
        } else {
            pinDao.insertPin(pinToSave)
        }

        val targetBoardIds = if (explicitBoardIds.isNotEmpty()) {
            explicitBoardIds
        } else {
            val matchedIds = localClassification.suggestedBoards.mapNotNull { suggestedName ->
                allBoards.firstOrNull { it.name.equals(suggestedName, ignoreCase = true) }?.id
            }
            matchedIds.ifEmpty { listOfNotNull(allBoards.firstOrNull()?.id) }
        }

        targetBoardIds.forEach { boardId ->
            copyPinToBoard(pinId = pinId, targetBoardId = boardId)
        }

        val targetMoodIds = if (explicitMoodIds.isNotEmpty()) {
            explicitMoodIds
        } else {
            localClassification.suggestedMoods.mapNotNull { suggestedMood ->
                allMoods.firstOrNull { it.name.equals(suggestedMood, ignoreCase = true) }?.id
            }
        }

        targetMoodIds.forEach { moodId ->
            pinDao.insertPinMoodCrossRef(PinMoodCrossRef(pinId = pinId, moodId = moodId))
        }

        val populated = pinDao.getPopulatedPinById(pinId)!!
        val localSaveElapsedMs = (System.currentTimeMillis() - saveStartedAt).coerceAtLeast(1L)
        val boardSummary = populated.boards.joinToString(", ") { "${it.emoji} ${it.name}" }

        _lastSaveTiming.value = SaveTimingReport(
            timestampMillis = System.currentTimeMillis(),
            shortcode = shortcode,
            authorHandle = populated.pin.authorHandle,
            boardSummary = boardSummary,
            tapToOpenCardMs = tapToOpenCardMs,
            localDbSaveMs = localSaveElapsedMs,
            enrichmentStatus = "Local save complete in ${localSaveElapsedMs}ms • enriching metadata in background…"
        )

        // Fire-and-forget background network enrichment (OpenGraph + BYOK AI + optional Phase 2)
        backgroundScope.launch {
            enrichSavedPinInBackground(
                pinId = pinId,
                rawUrl = rawUrl,
                screenAuthorHandle = screenAuthorHandle,
                screenCaption = screenCaption,
                screenAudioTrack = screenAudioTrack,
                explicitBoardIds = explicitBoardIds,
                explicitMoodIds = explicitMoodIds,
                targetBoardIds = targetBoardIds,
                runPhase2Immediately = runPhase2Immediately
            )
        }

        return populated
    }

    private suspend fun enrichSavedPinInBackground(
        pinId: Long,
        rawUrl: String?,
        screenAuthorHandle: String,
        screenCaption: String,
        screenAudioTrack: String,
        explicitBoardIds: List<Long>,
        explicitMoodIds: List<Long>,
        targetBoardIds: List<Long>,
        runPhase2Immediately: Boolean
    ) {
        runCatching {
            val metaStart = System.currentTimeMillis()
            val extracted = metadataExtractor.enrichMetadata(
                rawUrl = rawUrl,
                screenAuthorHandle = screenAuthorHandle,
                screenCaption = screenCaption,
                screenAudioTrack = screenAudioTrack
            )
            val metaMs = System.currentTimeMillis() - metaStart

            val settings = preferences.getSettings()
            val allBoards = boardDao.getAllBoards()
            val allMoods = boardDao.getAllMoodTags()

            val aiStart = System.currentTimeMillis()
            val classification = geminiAiService.classifyPinPhase1(
                apiKey = settings.geminiApiKey,
                model = settings.geminiModel,
                authorHandle = extracted.authorHandle,
                caption = extracted.caption,
                audioTrack = extracted.audioTrackName,
                availableBoardNames = allBoards.map { it.name },
                availableMoodNames = allMoods.map { it.name },
                provider = settings.aiProvider
            )
            val aiMs = System.currentTimeMillis() - aiStart

            val current = pinDao.getPopulatedPinById(pinId)?.pin ?: return@runCatching
            val updatedPin = current.copy(
                instagramUrl = extracted.normalizedUrl.ifBlank { current.instagramUrl },
                shortcode = extracted.shortcode.ifBlank { current.shortcode },
                authorHandle = extracted.authorHandle
                    .takeIf { it.isNotBlank() && it != "unknown_creator" }
                    ?: current.authorHandle,
                authorDisplayName = extracted.authorDisplayName.ifBlank { current.authorDisplayName },
                caption = extracted.caption.ifBlank { current.caption.ifBlank { classification.oneLineSummary } },
                audioTrackName = extracted.audioTrackName.ifBlank { current.audioTrackName },
                thumbnailUrl = extracted.thumbnailUrl.ifBlank { current.thumbnailUrl },
                domainCategory = classification.domainCategory.ifBlank { current.domainCategory },
                subjectTopic = classification.subjectTopic.ifBlank { current.subjectTopic },
                hashtagsCsv = if (extracted.hashtags.isNotEmpty()) {
                    extracted.hashtags.joinToString(",")
                } else {
                    current.hashtagsCsv
                },
                isReachable = extracted.isReachable
            )
            pinDao.updatePin(updatedPin)

            if (explicitBoardIds.isEmpty()) {
                classification.suggestedBoards.mapNotNull { suggestedName ->
                    allBoards.firstOrNull { it.name.equals(suggestedName, ignoreCase = true) }?.id
                }.forEach { boardId ->
                    copyPinToBoard(pinId = pinId, targetBoardId = boardId)
                }
            }

            if (explicitMoodIds.isEmpty()) {
                classification.suggestedMoods.mapNotNull { suggestedMood ->
                    allMoods.firstOrNull { it.name.equals(suggestedMood, ignoreCase = true) }?.id
                }.forEach { moodId ->
                    pinDao.insertPinMoodCrossRef(PinMoodCrossRef(pinId = pinId, moodId = moodId))
                }
            }

            _lastSaveTiming.value = _lastSaveTiming.value?.copy(
                authorHandle = updatedPin.authorHandle,
                backgroundMetadataMs = metaMs,
                backgroundAiMs = aiMs,
                enrichmentStatus = "Background enrichment finished (IG metadata ${metaMs}ms, AI ${aiMs}ms)"
            )

            val boardRequiresAutoPhase2 = allBoards.any { it.id in targetBoardIds && it.autoAnalyzePhase2 }
            if (runPhase2Immediately || settings.autoAnalyzePhase2OnSave || boardRequiresAutoPhase2) {
                runCatching {
                    runPhase2DeepAnalysis(pinId)
                }
            }
        }.onFailure { err ->
            _lastSaveTiming.value = _lastSaveTiming.value?.copy(
                enrichmentStatus = "Saved locally; background enrichment skipped (${err.message?.take(60) ?: "offline"})"
            )
        }
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
            creatorNiche = settings.creatorNicheDescription,
            provider = settings.aiProvider,
            scriptDurationSec = settings.scriptDurationSeconds,
            scriptTone = settings.scriptToneAndLanguage
        )
        analysisDao.insertOrUpdateAnalysis(analysis)
        pinDao.updatePin(populated.pin.copy(isAnalyzedPhase2 = true))
        return analysis
    }

    /**
     * Phase 3: Imports historical Instagram `liked_posts.json` / `saved_posts.json` export.
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
                        availableMoodNames = allMoods.map { it.name },
                        provider = settings.aiProvider
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

    /**
     * Exports a complete JSON snapshot of all Boards, Moods, Pins, and Phase 2 Analyses
     * to `filesDir/backups/InstaPin-AutoBackup.json` so data is always safe across updates/rollbacks.
     */
    suspend fun exportFullBackupSnapshotToFile(context: Context): File {
        val boards = boardDao.getAllBoards()
        val moods = boardDao.getAllMoodTags()
        val populatedPins = pinDao.getAllPopulatedPins()

        val snapshot = FullDatabaseSnapshot(
            exportedAtMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            boards = boards,
            moods = moods,
            pins = populatedPins.map { item ->
                BackupPinItem(
                    pin = item.pin,
                    boardNames = item.boards.map { it.name },
                    moodNames = item.moods.map { it.name },
                    analysis = item.analysis
                )
            }
        )

        val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
        val backupFile = File(backupDir, "InstaPin-AutoBackup.json")
        backupFile.writeText(snapshotJson.encodeToString(snapshot))
        return backupFile
    }

    /**
     * Restores Boards, Moods, Pins, and Phase 2 Analyses from `filesDir/backups/InstaPin-AutoBackup.json`.
     */
    suspend fun restoreFullBackupSnapshotFromFile(context: Context): Int {
        val backupFile = File(File(context.filesDir, "backups"), "InstaPin-AutoBackup.json")
        if (!backupFile.exists()) return 0

        val snapshot = snapshotJson.decodeFromString<FullDatabaseSnapshot>(backupFile.readText())
        snapshot.boards.forEach { boardDao.insertBoard(it.copy(id = 0)) }
        snapshot.moods.forEach { boardDao.insertMoodTag(it.copy(id = 0)) }

        val allBoards = boardDao.getAllBoards()
        val allMoods = boardDao.getAllMoodTags()
        var restoredCount = 0

        snapshot.pins.forEach { item ->
            val existing = pinDao.getPinByShortcode(item.pin.shortcode)
            val pinId = existing?.id ?: pinDao.insertPin(item.pin.copy(id = 0))
            item.boardNames.forEach { bName ->
                allBoards.firstOrNull { it.name.equals(bName, ignoreCase = true) }?.let { b ->
                    pinDao.insertPinBoardCrossRef(PinBoardCrossRef(pinId = pinId, boardId = b.id))
                }
            }
            item.moodNames.forEach { mName ->
                allMoods.firstOrNull { it.name.equals(mName, ignoreCase = true) }?.let { m ->
                    pinDao.insertPinMoodCrossRef(PinMoodCrossRef(pinId = pinId, moodId = m.id))
                }
            }
            item.analysis?.let { analysis ->
                analysisDao.insertOrUpdateAnalysis(analysis.copy(id = 0, pinId = pinId))
            }
            restoredCount++
        }
        return restoredCount
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

    suspend fun createQuickSaveBoard(name: String): Long {
        boardDao.getBoardByName(name.trim())?.let { return it.id }
        return createBoard(
            name = name,
            emoji = "📌",
            colorHex = "#E60023",
            description = "Created from the Reel save screen",
            pinToRadial = false,
            autoAnalyzePhase2 = false
        )
    }

    suspend fun updateBoard(board: Board) {
        boardDao.updateBoard(board)
    }

    suspend fun deleteBoard(board: Board) {
        boardDao.deleteBoard(board)
    }

    suspend fun setBoardQuickSaveVisibility(boardId: Long, visibleInSaveSheet: Boolean) {
        val board = boardDao.getAllBoards().firstOrNull { it.id == boardId } ?: return
        val quickBoardCount = boardDao.getRadialBoards().size
        boardDao.updateBoard(
            board.copy(
                isPinnedToRadial = visibleInSaveSheet,
                radialSlotIndex = if (visibleInSaveSheet && !board.isPinnedToRadial) {
                    quickBoardCount
                } else {
                    board.radialSlotIndex
                }
            )
        )
    }

    suspend fun createBoardSection(boardId: Long, name: String, emoji: String = "📂"): Long {
        val existing = boardDao.getSectionsForBoard(boardId)
        return boardDao.insertSection(
            BoardSection(
                boardId = boardId,
                name = name.trim(),
                emoji = emoji.ifBlank { "📂" },
                sortOrder = existing.size
            )
        )
    }

    suspend fun updateBoardSection(section: BoardSection) {
        boardDao.updateSection(section)
    }

    suspend fun deleteBoardSection(section: BoardSection) {
        boardDao.deleteSection(section)
    }

    /** Copy/Add: keep the Pin in all current boards and add a new board relationship. */
    suspend fun copyPinToBoard(pinId: Long, targetBoardId: Long, targetSectionId: Long? = null) {
        // Preserve an existing section assignment when simply adding to a board.
        val effectiveSectionId = targetSectionId ?: pinDao.getPinSectionId(pinId, targetBoardId)
        pinDao.insertPinBoardCrossRef(
            PinBoardCrossRef(
                pinId = pinId,
                boardId = targetBoardId,
                sectionId = effectiveSectionId
            )
        )
    }

    /** Move: remove only the chosen source board relationship, then add the target relationship. */
    suspend fun movePinToBoard(
        pinId: Long,
        sourceBoardId: Long?,
        targetBoardId: Long,
        targetSectionId: Long? = null
    ) {
        sourceBoardId?.takeIf { it != targetBoardId || targetSectionId != null }?.let { sourceId ->
            pinDao.removePinFromBoard(pinId, sourceId)
        }
        copyPinToBoard(pinId, targetBoardId, targetSectionId)
    }

    suspend fun assignPinToSection(pinId: Long, boardId: Long, sectionId: Long?) {
        pinDao.assignPinToSection(pinId = pinId, boardId = boardId, sectionId = sectionId)
    }

    suspend fun getPinSectionId(pinId: Long, boardId: Long): Long? {
        return pinDao.getPinSectionId(pinId, boardId)
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

    suspend fun updatePinExactReelUrl(pinId: Long, rawClipboardOrUrl: String): Boolean {
        val cleanUrl = InstagramMetadataExtractor.extractFirstInstagramUrl(rawClipboardOrUrl) ?: return false
        val shortcode = InstagramMetadataExtractor.extractShortcode(cleanUrl)
        val existing = pinDao.getPopulatedPinById(pinId)?.pin ?: return false
        pinDao.updatePin(
            existing.copy(
                instagramUrl = cleanUrl,
                shortcode = shortcode
            )
        )
        return true
    }

    suspend fun approveAllPhase3TriagePins() {
        pinDao.markAllPhase3PinsApproved()
    }

    suspend fun approvePhase3Pin(pinId: Long) {
        pinDao.markPhase3PinsApproved(listOf(pinId))
    }
}
