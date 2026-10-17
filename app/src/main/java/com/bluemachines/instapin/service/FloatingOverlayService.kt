package com.bluemachines.instapin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bluemachines.instapin.InstaPinApp
import com.bluemachines.instapin.MainActivity
import com.bluemachines.instapin.R
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import com.bluemachines.instapin.ui.overlay.PinterestRadialOverlay
import com.bluemachines.instapin.ui.overlay.QuickSaveOverlaySheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service managing the Pinterest Hover Bubble & Radial Hold-and-Slide Menu over Instagram
 * on Moto G84 5G.
 */
class FloatingOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private lateinit var windowManager: WindowManager
    private var composeOverlayView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var isQuickSheetExpanded by mutableStateOf(false)
    private var isFullScreenWindowMode by mutableStateOf(false)
    private var lastAutoCapturedReelUrl by mutableStateOf<String?>(null)
    private var bubbleYOffsetPx: Int = 0

    companion object {
        private const val CHANNEL_ID = "instapin_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_OVERLAY = "com.bluemachines.instapin.STOP_OVERLAY"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var manuallyPausedByUser: Boolean = false

        fun start(context: Context, resetManualPause: Boolean = false) {
            if (resetManualPause) {
                manuallyPausedByUser = false
            }
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            manuallyPausedByUser = true
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        isRunning = true
        _isRunningFlow.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        attachOverlayWindow()
        Toast.makeText(
            this,
            "InstaPin Hover Bubble Active! Look at the right edge of your screen.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (composeOverlayView == null) {
            attachOverlayWindow()
        }
        return START_STICKY
    }

    private fun attachOverlayWindow() {
        if (!Settings.canDrawOverlays(this) || composeOverlayView != null) return

        val app = applicationContext as InstaPinApp
        val params = createWindowLayoutParams(fullScreen = false, focusable = false)
        windowLayoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)

            setContent {
                val boards by app.repository.allBoardsFlow.collectAsState(initial = emptyList())
                val radialBoards by app.repository.radialBoardsFlow.collectAsState(initial = emptyList())
                val moods by app.repository.allMoodsFlow.collectAsState(initial = emptyList())
                val settings by app.preferences.settingsFlow.collectAsState(initial = com.bluemachines.instapin.data.local.UserSettings())
                val screenContext by InstaPinAccessibilityService.screenContext.collectAsState()
                val accessibilityConnected by InstaPinAccessibilityService.isServiceConnected.collectAsState()

                val shouldShowBubble = !settings.autoShowBubbleOnInstagramOnly ||
                    !accessibilityConnected ||
                    screenContext.isInstagramForeground ||
                    isQuickSheetExpanded

                if (shouldShowBubble) {
                    if (isQuickSheetExpanded) {
                        val clipboardUrl = lastAutoCapturedReelUrl
                            ?: readInstagramUrlFromClipboard()
                            ?: screenContext.detectedUrlFromScreen
                        QuickSaveOverlaySheet(
                            detectedUrl = clipboardUrl,
                            detectedAuthor = screenContext.authorHandle,
                            detectedCaption = screenContext.captionSnippet,
                            boards = boards,
                            moods = moods,
                            onDismiss = {
                                isQuickSheetExpanded = false
                                updateWindowMode(fullScreen = false, focusable = false)
                            },
                            onStopHoverService = {
                                manuallyPausedByUser = true
                                isQuickSheetExpanded = false
                                stopSelf()
                            },
                            onAutoGrabReelLink = {
                                isQuickSheetExpanded = false
                                updateWindowMode(fullScreen = false, focusable = false)
                                InstaPinAccessibilityService.triggerAutoCopyLinkAndWait()
                                val grabbed = readInstagramUrlFromClipboard()
                                lastAutoCapturedReelUrl = grabbed
                                isQuickSheetExpanded = true
                                updateWindowMode(fullScreen = true, focusable = true)
                                grabbed
                            },
                            onSavePin = { manualUrl, author, caption, boardIds, moodIds, notes, runPhase2 ->
                                isQuickSheetExpanded = false
                                updateWindowMode(fullScreen = false, focusable = false)
                                saveFromOverlay(
                                    explicitUrl = manualUrl.ifBlank { clipboardUrl },
                                    author = author,
                                    caption = caption,
                                    audioTrack = screenContext.audioTrackName,
                                    boardIds = boardIds,
                                    moodIds = moodIds,
                                    notes = notes,
                                    captureSource = CaptureSource.QUICK_SAVE_OVERLAY,
                                    runPhase2 = runPhase2
                                )
                            }
                        )
                    } else {
                        PinterestRadialOverlay(
                            radialBoards = radialBoards.ifEmpty { boards.take(5) },
                            isRightSide = settings.bubbleSideRight,
                            onRadialExpandChange = { expanded ->
                                vibrateTick()
                                updateWindowMode(fullScreen = expanded, focusable = false)
                            },
                            onSingleTapOpenSheet = {
                                vibrateTick()
                                lifecycleScope.launch {
                                    // While Instagram is still the active window, auto-click Share -> Copy Link
                                    // so the exact Reel video permalink (instagram.com/reel/...) is captured!
                                    if (screenContext.isInstagramForeground) {
                                        InstaPinAccessibilityService.triggerAutoCopyLinkAndWait()
                                        lastAutoCapturedReelUrl = readInstagramUrlFromClipboard()
                                    }
                                    isQuickSheetExpanded = true
                                    updateWindowMode(fullScreen = true, focusable = true)
                                }
                            },
                            onRadialBoardSelected = { selectedBoard: Board? ->
                                vibrateTick()
                                val clipboardUrl = readInstagramUrlFromClipboard()
                                    ?: screenContext.detectedUrlFromScreen
                                saveFromOverlay(
                                    explicitUrl = clipboardUrl,
                                    author = screenContext.authorHandle,
                                    caption = screenContext.captionSnippet,
                                    audioTrack = screenContext.audioTrackName,
                                    boardIds = listOfNotNull(selectedBoard?.id),
                                    moodIds = emptyList(),
                                    notes = "",
                                    captureSource = CaptureSource.LIVE_RADIAL_OVERLAY,
                                    runPhase2 = selectedBoard?.autoAnalyzePhase2 ?: false
                                )
                            },
                            onStopHoverService = {
                                manuallyPausedByUser = true
                                stopSelf()
                            },
                            onVerticalDragDelta = { deltaY ->
                                bubbleYOffsetPx = (bubbleYOffsetPx + deltaY.toInt()).coerceIn(-650, 650)
                                if (!isFullScreenWindowMode) {
                                    windowLayoutParams?.let { lp ->
                                        lp.y = bubbleYOffsetPx
                                        composeOverlayView?.let { v ->
                                            runCatching { windowManager.updateViewLayout(v, lp) }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        composeOverlayView = view
        runCatching {
            windowManager.addView(view, params)
        }
    }

    private fun createWindowLayoutParams(fullScreen: Boolean, focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val width = if (fullScreen) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }

        val height = if (fullScreen) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (fullScreen) {
                Gravity.CENTER
            } else {
                Gravity.CENTER_VERTICAL or Gravity.END
            }
            y = if (fullScreen) 0 else bubbleYOffsetPx
        }
    }

    private fun updateWindowMode(fullScreen: Boolean, focusable: Boolean) {
        val view = composeOverlayView ?: return
        isFullScreenWindowMode = fullScreen
        val updatedParams = createWindowLayoutParams(fullScreen = fullScreen, focusable = focusable)
        windowLayoutParams = updatedParams
        runCatching {
            windowManager.updateViewLayout(view, updatedParams)
        }
    }

    private fun saveFromOverlay(
        explicitUrl: String?,
        author: String,
        caption: String,
        audioTrack: String,
        boardIds: List<Long>,
        moodIds: List<Long>,
        notes: String,
        captureSource: CaptureSource,
        runPhase2: Boolean
    ) {
        val app = applicationContext as InstaPinApp
        lifecycleScope.launch {
            var resolvedUrl = explicitUrl
            if (resolvedUrl.isNullOrBlank()) {
                // Ensure our overlay does not hold window focus while Accessibility clicks Instagram's Share -> Copy Link
                updateWindowMode(fullScreen = false, focusable = false)
                InstaPinAccessibilityService.triggerAutoCopyLinkAndWait()
                resolvedUrl = readInstagramUrlFromClipboard() ?: lastAutoCapturedReelUrl
            }
            lastAutoCapturedReelUrl = null

            val savedPin = withContext(Dispatchers.IO) {
                app.repository.saveOrUpdateInstagramPin(
                    rawUrl = resolvedUrl,
                    screenAuthorHandle = author,
                    screenCaption = caption,
                    screenAudioTrack = audioTrack,
                    explicitBoardIds = boardIds,
                    explicitMoodIds = moodIds,
                    userNotes = notes,
                    captureSource = captureSource,
                    runPhase2Immediately = runPhase2
                )
            }

            val boardLabels = savedPin.boards.joinToString(", ") { "${it.emoji} ${it.name}" }
            Toast.makeText(
                this@FloatingOverlayService,
                "Pinned @${savedPin.pin.authorHandle} to $boardLabels (${savedPin.pin.domainCategory})",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readInstagramUrlFromClipboard(): String? {
        return runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            InstagramMetadataExtractor.extractFirstInstagramUrl(text)
        }.getOrNull()
    }

    private fun vibrateTick() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "InstaPin Hover Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Pinterest radial board saver active over Instagram on Moto G84 5G"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("InstaPin Hover Active")
            .setContentText("Hold & slide the red pin over Instagram Reels to save to Boards")
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop Hover", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        _isRunningFlow.value = false
        composeOverlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        composeOverlayView = null
        viewModelStore.clear()
        super.onDestroy()
    }
}
