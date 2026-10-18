package com.bluemachines.instapin.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

enum class CaptureSource {
    LIVE_RADIAL_OVERLAY,
    QUICK_SAVE_OVERLAY,
    ANDROID_SHARE_SHEET,
    PHASE3_HISTORICAL_IMPORT
}

/**
 * Core instance representing a saved Instagram Reel/Post link without downloading the video file.
 * Categorized by Date (savedAt / publishedAt), Author (@authorHandle), Domain, Subject, Boards, and Moods.
 */
@Serializable
@Entity(
    tableName = "video_pins",
    indices = [
        Index(value = ["shortcode"], unique = true),
        Index(value = ["instagramUrl"]),
        Index(value = ["authorHandle"]),
        Index(value = ["domainCategory"]),
        Index(value = ["savedAt"])
    ]
)
data class VideoPin(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val instagramUrl: String,
    val shortcode: String,
    val authorHandle: String = "unknown_creator",
    val authorDisplayName: String = "",
    val caption: String = "",
    val audioTrackName: String = "",
    val thumbnailUrl: String = "",
    val domainCategory: String = "Uncategorized", // e.g., Video Editing, Trading, Health, Jobs
    val subjectTopic: String = "",                // e.g., Color Grading in CapCut, Price Action Scalp
    val hashtagsCsv: String = "",
    val savedAt: Long = System.currentTimeMillis(),
    val originalLikedOrPostedAt: Long? = null,
    val userNotes: String = "",
    val isAnalyzedPhase2: Boolean = false,
    val isPendingTriagePhase3: Boolean = false,
    val isReachable: Boolean = true,
    val captureSource: CaptureSource = CaptureSource.LIVE_RADIAL_OVERLAY
)

/**
 * User-created Pinterest-style Board (e.g., Video Editing, Trading, Health, Jobs).
 * Can be pinned to the Radial Hold-and-Slide menu (slots 0..5).
 */
@Serializable
@Entity(
    tableName = "boards",
    indices = [Index(value = ["name"], unique = true)]
)
data class Board(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val iconName: String = "Folder",
    val emoji: String = "📌",
    val colorHex: String = "#E60023",
    val isPinnedToRadial: Boolean = true,
    val radialSlotIndex: Int = 0,
    val autoAnalyzePhase2: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Mood / Intent tags (e.g., To Recreate, Deep Study, Hook Idea, Daily Reference, High Energy).
 */
@Serializable
@Entity(
    tableName = "mood_tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class MoodTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val emoji: String = "✨",
    val colorHex: String = "#6366F1"
)

/**
 * Many-to-many junction allowing a single Instagram Reel to be stored into MULTIPLE Boards at once.
 */
@Serializable
@Entity(
    tableName = "pin_board_cross_ref",
    primaryKeys = ["pinId", "boardId"],
    foreignKeys = [
        ForeignKey(
            entity = VideoPin::class,
            parentColumns = ["id"],
            childColumns = ["pinId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Board::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pinId"), Index("boardId")]
)
data class PinBoardCrossRef(
    val pinId: Long,
    val boardId: Long,
    val sectionId: Long? = null
)

/**
 * Pinterest-style sub-board Section inside a Board (e.g., Video Editing -> CapCut, Hooks).
 */
@Serializable
@Entity(
    tableName = "board_sections",
    foreignKeys = [
        ForeignKey(
            entity = Board::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("boardId"), Index(value = ["boardId", "name"], unique = true)]
)
data class BoardSection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val boardId: Long,
    val name: String,
    val emoji: String = "📂",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Many-to-many junction allowing a single Instagram Reel to carry MULTIPLE Mood tags.
 */
@Serializable
@Entity(
    tableName = "pin_mood_cross_ref",
    primaryKeys = ["pinId", "moodId"],
    foreignKeys = [
        ForeignKey(
            entity = VideoPin::class,
            parentColumns = ["id"],
            childColumns = ["pinId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MoodTag::class,
            parentColumns = ["id"],
            childColumns = ["moodId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pinId"), Index("moodId")]
)
data class PinMoodCrossRef(
    val pinId: Long,
    val moodId: Long
)

/**
 * Phase 2: Deep Video Analysis, Summary, Transcript, Hook/Editing Breakdown & Repurpose Studio output.
 */
@Serializable
@Entity(
    tableName = "video_analyses",
    foreignKeys = [
        ForeignKey(
            entity = VideoPin::class,
            parentColumns = ["id"],
            childColumns = ["pinId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pinId"], unique = true)]
)
data class VideoAnalysis(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pinId: Long,
    val executiveSummary: String = "",
    val actionableTakeawaysJson: String = "[]",     // JSON list of actionable steps
    val toolsAndResourcesJson: String = "[]",       // JSON list of apps, indicators, websites, books mentioned
    val fullTranscript: String = "",                // Spoken words + on-screen OCR text
    val hookBreakdown: String = "",                 // 0-3s scroll-stopper hook analysis
    val bodyStructureBreakdown: String = "",        // Retention pacing & core argument flow
    val ctaBreakdown: String = "",                  // Call to action & engagement trigger
    val editingAndVisualNotes: String = "",         // Cut cadence, B-roll, captions, SFX/music mood
    val alternativeHooksJson: String = "[]",        // 3 fresh hooks for recreating the video
    val recreateTeleprompterScript: String = "",    // Ready-to-record script in user's own voice
    val shotListAndEditChecklistJson: String = "[]",// Step-by-step filming & editing checklist
    val modelUsed: String = "gemini-2.5-flash",
    val analyzedAt: Long = System.currentTimeMillis()
)

/**
 * Phase 3: Historical Instagram Data Export Import Batch tracker.
 */
@Serializable
@Entity(tableName = "import_batches")
data class ImportBatch(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceFileName: String,
    val importType: String = "LIKED_AND_SAVED_JSON",
    val totalDiscovered: Int = 0,
    val newlyImported: Int = 0,
    val duplicatesSkipped: Int = 0,
    val aiCategorizedCount: Int = 0,
    val status: String = "IN_PROGRESS", // IN_PROGRESS, COMPLETED, FAILED
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

/**
 * Rich composite model combining a VideoPin with all its assigned Boards, Moods, and Phase 2 Analysis.
 */
data class PopulatedVideoPin(
    @Embedded val pin: VideoPin,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PinBoardCrossRef::class,
            parentColumn = "pinId",
            entityColumn = "boardId"
        )
    )
    val boards: List<Board>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PinMoodCrossRef::class,
            parentColumn = "pinId",
            entityColumn = "moodId"
        )
    )
    val moods: List<MoodTag>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PinBoardCrossRef::class,
            parentColumn = "pinId",
            entityColumn = "sectionId"
        )
    )
    val sections: List<BoardSection>,
    @Relation(
        parentColumn = "id",
        entityColumn = "pinId"
    )
    val analysis: VideoAnalysis?
)

data class BoardWithCount(
    @Embedded val board: Board,
    val pinCount: Int
)

data class AuthorSummary(
    val authorHandle: String,
    val videoCount: Int,
    val latestSavedAt: Long
)

data class DomainSummary(
    val domainCategory: String,
    val videoCount: Int
)
