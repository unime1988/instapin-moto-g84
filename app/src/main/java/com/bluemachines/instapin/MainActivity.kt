package com.bluemachines.instapin

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.AppThemePreset
import com.bluemachines.instapin.data.local.FeedLayoutMode
import com.bluemachines.instapin.service.FloatingOverlayService
import com.bluemachines.instapin.ui.MainViewModel
import com.bluemachines.instapin.ui.detail.PinDetailAndRepurposeScreen
import com.bluemachines.instapin.ui.home.BoardsGridScreen
import com.bluemachines.instapin.ui.home.PinFeedScreen
import com.bluemachines.instapin.ui.import_phase3.Phase3HistoricalImportScreen
import com.bluemachines.instapin.ui.overlay.QuickSaveOverlaySheet
import com.bluemachines.instapin.ui.settings.MotoG84OnboardingAndSettingsScreen

private val ObsidianColorScheme = darkColorScheme(
    primary = Color(0xFFE60023),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFF10B981),
    background = Color(0xFF0E0E16),
    surface = Color(0xFF181826),
    onBackground = Color.White,
    onSurface = Color(0xFFF1F5F9)
)

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFF8B5CF6),
    secondary = Color(0xFFE60023),
    tertiary = Color(0xFF10B981),
    background = Color(0xFF000000),
    surface = Color(0xFF0D0D14),
    onBackground = Color.White,
    onSurface = Color(0xFFF8FAFC)
)

private val DaylightColorScheme = lightColorScheme(
    primary = Color(0xFFE60023),
    secondary = Color(0xFF6366F1),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF1E293B)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as InstaPinApp
        MainViewModel.Factory(app.repository, app.preferences)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this) && !FloatingOverlayService.isRunning) {
            FloatingOverlayService.start(this)
        }

        setContent {
            val filteredPins by viewModel.filteredPins.collectAsState()
            val allPins by viewModel.allPins.collectAsState()
            val boardsWithCount by viewModel.boardsWithCount.collectAsState()
            val allBoards by viewModel.allBoards.collectAsState()
            val allMoods by viewModel.allMoods.collectAsState()
            val authors by viewModel.authorSummaries.collectAsState()
            val domains by viewModel.domainSummaries.collectAsState()
            val filterState by viewModel.filterState.collectAsState()
            val triagePins by viewModel.phase3TriagePins.collectAsState()
            val importBatches by viewModel.importBatches.collectAsState()
            val settings by viewModel.settings.collectAsState()
            val aiTestResult by viewModel.aiTestStatus.collectAsState()

            val activeColorScheme = when (settings.themePreset) {
                AppThemePreset.OBSIDIAN -> ObsidianColorScheme
                AppThemePreset.AMOLED -> AmoledColorScheme
                AppThemePreset.DAYLIGHT -> DaylightColorScheme
            }

            MaterialTheme(colorScheme = activeColorScheme) {
                var currentBottomTab by remember { mutableIntStateOf(0) }
                var selectedPinIdForDetail by remember { mutableStateOf<Long?>(null) }
                var showAddPinSheet by remember { mutableStateOf(false) }

                val activeDetailPin = remember(selectedPinIdForDetail, allPins) {
                    allPins.firstOrNull { it.pin.id == selectedPinIdForDetail }
                }

                if (activeDetailPin != null) {
                    PinDetailAndRepurposeScreen(
                        populatedPin = activeDetailPin,
                        onBack = { selectedPinIdForDetail = null },
                        onTriggerPhase2Analysis = { pinId ->
                            viewModel.triggerPhase2Analysis(pinId)
                        },
                        onAttachClipboardLink = { rawUrl ->
                            viewModel.attachReelUrlToPin(activeDetailPin.pin.id, rawUrl) {}
                        },
                        onDeletePin = {
                            viewModel.deletePin(activeDetailPin.pin)
                            selectedPinIdForDetail = null
                        }
                    )
                } else {
                    Scaffold(
                        containerColor = activeColorScheme.background,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = "InstaPin • Pinterest for Instagram",
                                        color = activeColorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                },
                                actions = {
                                    // 1-Tap Quick Theme Cyciling Button in Top Bar
                                    IconButton(
                                        onClick = {
                                            val nextTheme = when (settings.themePreset) {
                                                AppThemePreset.OBSIDIAN -> AppThemePreset.AMOLED
                                                AppThemePreset.AMOLED -> AppThemePreset.DAYLIGHT
                                                AppThemePreset.DAYLIGHT -> AppThemePreset.OBSIDIAN
                                            }
                                            viewModel.saveVisualAppearance(
                                                nextTheme,
                                                settings.feedLayoutMode,
                                                settings.fontScalePreset
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Palette,
                                            contentDescription = "Cycle Theme",
                                            tint = activeColorScheme.primary
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = activeColorScheme.surface
                                )
                            )
                        },
                        floatingActionButton = {
                            if (currentBottomTab == 0 || currentBottomTab == 1) {
                                FloatingActionButton(
                                    onClick = { showAddPinSheet = true },
                                    containerColor = Color(0xFFE60023),
                                    contentColor = Color.White
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Pin Instagram Link")
                                }
                            }
                        },
                        bottomBar = {
                            NavigationBar(containerColor = activeColorScheme.surface) {
                                NavigationBarItem(
                                    selected = currentBottomTab == 0,
                                    onClick = { currentBottomTab = 0 },
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("Pins (${allPins.size})") }
                                )
                                NavigationBarItem(
                                    selected = currentBottomTab == 1,
                                    onClick = { currentBottomTab = 1 },
                                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                                    label = { Text("Boards") }
                                )
                                NavigationBarItem(
                                    selected = currentBottomTab == 2,
                                    onClick = { currentBottomTab = 2 },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (triagePins.isNotEmpty()) {
                                                    Badge { Text("${triagePins.size}") }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.History, contentDescription = null)
                                        }
                                    },
                                    label = { Text("Phase 3 Import") }
                                )
                                NavigationBarItem(
                                    selected = currentBottomTab == 3,
                                    onClick = { currentBottomTab = 3 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("Moto G84 Setup") }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentBottomTab) {
                                0 -> PinFeedScreen(
                                    pins = filteredPins,
                                    boards = boardsWithCount,
                                    moods = allMoods,
                                    domains = domains,
                                    authors = authors,
                                    filterState = filterState,
                                    layoutMode = settings.feedLayoutMode,
                                    fontMultiplier = settings.fontScalePreset.multiplier,
                                    onToggleLayoutMode = {
                                        val nextLayout = if (settings.feedLayoutMode == FeedLayoutMode.PINTEREST_MASONRY) {
                                            FeedLayoutMode.DETAILED_LIST
                                        } else {
                                            FeedLayoutMode.PINTEREST_MASONRY
                                        }
                                        viewModel.saveVisualAppearance(
                                            settings.themePreset,
                                            nextLayout,
                                            settings.fontScalePreset
                                        )
                                    },
                                    onSearchQueryChange = viewModel::updateSearchQuery,
                                    onSelectBoard = viewModel::selectBoardFilter,
                                    onSelectMood = viewModel::selectMoodFilter,
                                    onSelectDomain = viewModel::selectDomainFilter,
                                    onSelectAuthor = viewModel::selectAuthorFilter,
                                    onSelectDateRange = viewModel::selectDateRange,
                                    onToggleOnlyAnalyzed = viewModel::toggleOnlyAnalyzed,
                                    onClearFilters = viewModel::clearAllFilters,
                                    onOpenPinDetail = { pinId -> selectedPinIdForDetail = pinId },
                                    onTriggerPhase2 = { pinId -> viewModel.triggerPhase2Analysis(pinId) }
                                )

                                1 -> BoardsGridScreen(
                                    boardsWithCount = boardsWithCount,
                                    authors = authors,
                                    domains = domains,
                                    onBoardClick = { boardId ->
                                        viewModel.selectBoardFilter(boardId)
                                        currentBottomTab = 0
                                    },
                                    onAuthorClick = { author ->
                                        viewModel.selectAuthorFilter(author)
                                        currentBottomTab = 0
                                    },
                                    onDomainClick = { domain ->
                                        viewModel.selectDomainFilter(domain)
                                        currentBottomTab = 0
                                    },
                                    onCreateBoard = viewModel::createBoard
                                )

                                2 -> Phase3HistoricalImportScreen(
                                    triagePins = triagePins,
                                    importBatches = importBatches,
                                    onApproveAllTriage = viewModel::approveAllPhase3Triage,
                                    onApproveSinglePin = viewModel::approveSinglePhase3Pin,
                                    onOpenPinDetail = { pinId -> selectedPinIdForDetail = pinId },
                                    onRunDemoImport = viewModel::runDemoSampleImportPhase3
                                )

                                3 -> MotoG84OnboardingAndSettingsScreen(
                                    settings = settings,
                                    aiTestResult = aiTestResult,
                                    onSaveMultiProviderAi = viewModel::saveMultiProviderAiSettings,
                                    onTestAiConnection = viewModel::testAiConnectionNow,
                                    onSaveVisualAppearance = viewModel::saveVisualAppearance,
                                    onExportBackup = { cb -> viewModel.exportManualBackup(this@MainActivity, cb) },
                                    onRestoreBackup = { cb -> viewModel.restoreManualBackup(this@MainActivity, cb) },
                                    onSaveOverlayPrefs = viewModel::saveOverlayPrefs
                                )
                            }

                            if (showAddPinSheet) {
                                QuickSaveOverlaySheet(
                                    detectedUrl = "",
                                    detectedAuthor = "",
                                    detectedCaption = "",
                                    boards = allBoards,
                                    moods = allMoods,
                                    onDismiss = { showAddPinSheet = false },
                                    onSavePin = { url, author, caption, boardIds, moodIds, notes, runPhase2 ->
                                        showAddPinSheet = false
                                        viewModel.addManualPin(
                                            instagramUrl = url,
                                            authorHandle = author,
                                            caption = caption,
                                            boardIds = boardIds,
                                            moodIds = moodIds,
                                            notes = notes,
                                            runPhase2Now = runPhase2
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && !FloatingOverlayService.isRunning) {
            FloatingOverlayService.start(this)
        }
    }
}
