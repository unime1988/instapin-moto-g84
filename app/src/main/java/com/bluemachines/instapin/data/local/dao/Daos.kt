package com.bluemachines.instapin.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardWithCount
import com.bluemachines.instapin.data.local.entity.DomainSummary
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PinBoardCrossRef
import com.bluemachines.instapin.data.local.entity.PinMoodCrossRef
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.data.local.entity.VideoAnalysis
import com.bluemachines.instapin.data.local.entity.VideoPin
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {

    @Transaction
    @Query("SELECT * FROM video_pins ORDER BY savedAt DESC")
    fun observeAllPopulatedPins(): Flow<List<PopulatedVideoPin>>

    @Transaction
    @Query("SELECT * FROM video_pins WHERE id = :pinId LIMIT 1")
    fun observePopulatedPinById(pinId: Long): Flow<PopulatedVideoPin?>

    @Transaction
    @Query("SELECT * FROM video_pins WHERE id = :pinId LIMIT 1")
    suspend fun getPopulatedPinById(pinId: Long): PopulatedVideoPin?

    @Query("SELECT * FROM video_pins WHERE shortcode = :shortcode LIMIT 1")
    suspend fun getPinByShortcode(shortcode: String): VideoPin?

    @Transaction
    @Query("SELECT * FROM video_pins WHERE isPendingTriagePhase3 = 1 ORDER BY originalLikedOrPostedAt DESC, savedAt DESC")
    fun observePhase3TriagePins(): Flow<List<PopulatedVideoPin>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPin(pin: VideoPin): Long

    @Update
    suspend fun updatePin(pin: VideoPin)

    @Delete
    suspend fun deletePin(pin: VideoPin)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPinBoardCrossRef(ref: PinBoardCrossRef)

    @Query("DELETE FROM pin_board_cross_ref WHERE pinId = :pinId")
    suspend fun clearBoardsForPin(pinId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPinMoodCrossRef(ref: PinMoodCrossRef)

    @Query("DELETE FROM pin_mood_cross_ref WHERE pinId = :pinId")
    suspend fun clearMoodsForPin(pinId: Long)

    @Query(
        """
        SELECT authorHandle, COUNT(*) AS videoCount, MAX(savedAt) AS latestSavedAt
        FROM video_pins
        GROUP BY authorHandle
        ORDER BY videoCount DESC, latestSavedAt DESC
        """
    )
    fun observeAuthorSummaries(): Flow<List<AuthorSummary>>

    @Query(
        """
        SELECT domainCategory, COUNT(*) AS videoCount
        FROM video_pins
        GROUP BY domainCategory
        ORDER BY videoCount DESC
        """
    )
    fun observeDomainSummaries(): Flow<List<DomainSummary>>

    @Query("UPDATE video_pins SET isPendingTriagePhase3 = 0 WHERE id IN (:pinIds)")
    suspend fun markPhase3PinsApproved(pinIds: List<Long>)

    @Query("UPDATE video_pins SET isPendingTriagePhase3 = 0")
    suspend fun markAllPhase3PinsApproved()
}

@Dao
interface BoardDao {

    @Query("SELECT * FROM boards ORDER BY isPinnedToRadial DESC, radialSlotIndex ASC, name ASC")
    fun observeAllBoards(): Flow<List<Board>>

    @Query("SELECT * FROM boards ORDER BY isPinnedToRadial DESC, radialSlotIndex ASC, name ASC")
    suspend fun getAllBoards(): List<Board>

    @Query("SELECT * FROM boards WHERE isPinnedToRadial = 1 ORDER BY radialSlotIndex ASC LIMIT 6")
    fun observeRadialBoards(): Flow<List<Board>>

    @Query("SELECT * FROM boards WHERE isPinnedToRadial = 1 ORDER BY radialSlotIndex ASC LIMIT 6")
    suspend fun getRadialBoards(): List<Board>

    @Query(
        """
        SELECT b.*, COUNT(pbc.pinId) AS pinCount
        FROM boards b
        LEFT JOIN pin_board_cross_ref pbc ON b.id = pbc.boardId
        GROUP BY b.id
        ORDER BY b.isPinnedToRadial DESC, b.radialSlotIndex ASC, b.name ASC
        """
    )
    fun observeBoardsWithCount(): Flow<List<BoardWithCount>>

    @Query("SELECT * FROM boards WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getBoardByName(name: String): Board?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBoard(board: Board): Long

    @Update
    suspend fun updateBoard(board: Board)

    @Delete
    suspend fun deleteBoard(board: Board)

    @Query("SELECT * FROM mood_tags ORDER BY name ASC")
    fun observeAllMoodTags(): Flow<List<MoodTag>>

    @Query("SELECT * FROM mood_tags ORDER BY name ASC")
    suspend fun getAllMoodTags(): List<MoodTag>

    @Query("SELECT * FROM mood_tags WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getMoodByName(name: String): MoodTag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMoodTag(moodTag: MoodTag): Long
}

@Dao
interface AnalysisDao {

    @Query("SELECT * FROM video_analyses WHERE pinId = :pinId LIMIT 1")
    fun observeAnalysisForPin(pinId: Long): Flow<VideoAnalysis?>

    @Query("SELECT * FROM video_analyses WHERE pinId = :pinId LIMIT 1")
    suspend fun getAnalysisForPin(pinId: Long): VideoAnalysis?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAnalysis(analysis: VideoAnalysis): Long
}

@Dao
interface ImportDao {

    @Query("SELECT * FROM import_batches ORDER BY startedAt DESC")
    fun observeImportBatches(): Flow<List<ImportBatch>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: ImportBatch): Long

    @Update
    suspend fun updateBatch(batch: ImportBatch)

    @Query("SELECT * FROM import_batches WHERE id = :batchId LIMIT 1")
    suspend fun getBatchById(batchId: Long): ImportBatch?
}
