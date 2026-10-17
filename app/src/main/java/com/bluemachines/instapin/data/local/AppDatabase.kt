package com.bluemachines.instapin.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bluemachines.instapin.data.local.dao.AnalysisDao
import com.bluemachines.instapin.data.local.dao.BoardDao
import com.bluemachines.instapin.data.local.dao.ImportDao
import com.bluemachines.instapin.data.local.dao.PinDao
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PinBoardCrossRef
import com.bluemachines.instapin.data.local.entity.PinMoodCrossRef
import com.bluemachines.instapin.data.local.entity.VideoAnalysis
import com.bluemachines.instapin.data.local.entity.VideoPin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DatabaseConverters {
    @TypeConverter
    fun fromCaptureSource(source: CaptureSource): String = source.name

    @TypeConverter
    fun toCaptureSource(value: String): CaptureSource =
        runCatching { CaptureSource.valueOf(value) }.getOrDefault(CaptureSource.LIVE_RADIAL_OVERLAY)
}

@Database(
    entities = [
        VideoPin::class,
        Board::class,
        MoodTag::class,
        PinBoardCrossRef::class,
        PinMoodCrossRef::class,
        VideoAnalysis::class,
        ImportBatch::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao
    abstract fun boardDao(): BoardDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun importDao(): ImportDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instapin_local.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.let { seedDefaultBoardsAndMoods(it) }
                            }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                CoroutineScope(Dispatchers.IO).launch {
                    seedDefaultBoardsAndMoods(instance)
                }
                instance
            }
        }

        suspend fun seedDefaultBoardsAndMoods(db: AppDatabase) {
            val boardDao = db.boardDao()
            if (boardDao.getAllBoards().isEmpty()) {
                val defaultBoards = listOf(
                    Board(
                        name = "Video Editing",
                        description = "Transitions, color grading, CapCut/Premiere/After Effects workflows, hooks & pacing",
                        iconName = "MovieEdit",
                        emoji = "🎬",
                        colorHex = "#E60023",
                        isPinnedToRadial = true,
                        radialSlotIndex = 0,
                        autoAnalyzePhase2 = true
                    ),
                    Board(
                        name = "Trading",
                        description = "Price action, options, chart patterns, risk management, macro & psychology",
                        iconName = "TrendingUp",
                        emoji = "📈",
                        colorHex = "#10B981",
                        isPinnedToRadial = true,
                        radialSlotIndex = 1,
                        autoAnalyzePhase2 = true
                    ),
                    Board(
                        name = "Health & Fitness",
                        description = "Nutrition, workouts, mobility, sleep protocols, longevity & recovery",
                        iconName = "Favorite",
                        emoji = "💪",
                        colorHex = "#F59E0B",
                        isPinnedToRadial = true,
                        radialSlotIndex = 2,
                        autoAnalyzePhase2 = false
                    ),
                    Board(
                        name = "Jobs & Career",
                        description = "Job search strategies, AI tools, interview prep, remote roles & side income",
                        iconName = "Work",
                        emoji = "💼",
                        colorHex = "#3B82F6",
                        isPinnedToRadial = true,
                        radialSlotIndex = 3,
                        autoAnalyzePhase2 = false
                    ),
                    Board(
                        name = "AI & Tech Tools",
                        description = "Automation, coding workflows, prompts, SaaS tools & productivity",
                        iconName = "AutoAwesome",
                        emoji = "🤖",
                        colorHex = "#8B5CF6",
                        isPinnedToRadial = true,
                        radialSlotIndex = 4,
                        autoAnalyzePhase2 = true
                    )
                )
                defaultBoards.forEach { boardDao.insertBoard(it) }
            }

            if (boardDao.getAllMoodTags().isEmpty()) {
                val defaultMoods = listOf(
                    MoodTag(name = "Recreate Soon", emoji = "🎥", colorHex = "#EF4444"),
                    MoodTag(name = "Hook Inspiration", emoji = "🪝", colorHex = "#F97316"),
                    MoodTag(name = "Deep Study", emoji = "🧠", colorHex = "#6366F1"),
                    MoodTag(name = "Actionable Tip", emoji = "⚡", colorHex = "#10B981"),
                    MoodTag(name = "Editing Style", emoji = "✂️", colorHex = "#EC4899"),
                    MoodTag(name = "Weekend Watch", emoji = "☕", colorHex = "#14B8A6")
                )
                defaultMoods.forEach { boardDao.insertMoodTag(it) }
            }
        }
    }
}
