package com.bluemachines.instapin

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import com.bluemachines.instapin.service.FloatingOverlayService
import com.bluemachines.instapin.service.InstaPinAccessibilityService
import com.bluemachines.instapin.ui.overlay.QuickSaveOverlaySheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent Overlay Activity launched either:
 * 1. When the user taps the Floating Red Pin over Instagram (after Accessibility clicks Share -> Copy Link), OR
 * 2. When the user taps "Share -> Pin to Board (InstaPin)" inside Instagram.
 *
 * CRITICAL ANDROID 10–14 (Moto G84 5G) FIX:
 * Because `ShareReceiverActivity` is a true foreground `ComponentActivity` (unlike a background Service),
 * Android 14 grants it full `ClipboardManager.primaryClip` access inside `onWindowFocusChanged(true)`!
 * This guarantees the exact copied Reel URL (`https://www.instagram.com/reel/...`) is always captured.
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FROM_FLOATING_PIN = "extra_from_floating_pin"
        const val EXTRA_PRESELECTED_BOARD_ID = "extra_preselected_board_id"
        const val EXTRA_AUTHOR_HANDLE = "extra_author_handle"
        const val EXTRA_CAPTION = "extra_caption"
        const val EXTRA_AUDIO_TRACK = "extra_audio_track"
    }

    private var capturedInstagramUrl by mutableStateOf("")
    private var handledDirectRadialSave = false

    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            // Live-capture: if the user manually taps "Copy link" in Instagram while this
            // activity is alive, the exact Reel URL lands on the clipboard and is picked up here.
            readClipboardInstagramUrl()?.let { capturedInstagramUrl = it }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        val app = applicationContext as InstaPinApp

        setContent {
            val boards by app.repository.allBoardsFlow.collectAsState(initial = emptyList())
            val moods by app.repository.allMoodsFlow.collectAsState(initial = emptyList())
            val screenContext by InstaPinAccessibilityService.screenContext.collectAsState()
            val captureReport by InstaPinAccessibilityService.lastCaptureReport.collectAsState()

            val effectiveAuthor = intent?.getStringExtra(EXTRA_AUTHOR_HANDLE)
                ?.takeIf { it.isNotBlank() }
                ?: screenContext.authorHandle

            val effectiveCaption = intent?.getStringExtra(EXTRA_CAPTION)
                ?.takeIf { it.isNotBlank() }
                ?: screenContext.captionSnippet

            QuickSaveOverlaySheet(
                detectedUrl = capturedInstagramUrl,
                detectedAuthor = effectiveAuthor,
                detectedCaption = effectiveCaption,
                captureStatusText = captureReport?.toReadableLine().orEmpty(),
                boards = boards,
                moods = moods,
                onDismiss = { finish() },
                onStopHoverService = {
                    FloatingOverlayService.stop(this@ShareReceiverActivity)
                    finish()
                },
                onAutoGrabReelLink = {
                    readClipboardInstagramUrl()?.also { capturedInstagramUrl = it }
                },
                onSavePin = { manualUrl, author, caption, boardIds, moodIds, notes, runPhase2 ->
                    val finalUrl = manualUrl.trim().ifBlank {
                        capturedInstagramUrl.ifBlank { readClipboardInstagramUrl().orEmpty() }
                    }
                    savePinAndFinish(
                        rawUrl = finalUrl,
                        author = author.ifBlank { effectiveAuthor },
                        caption = caption.ifBlank { effectiveCaption },
                        audioTrack = screenContext.audioTrackName,
                        boardIds = boardIds,
                        moodIds = moodIds,
                        notes = notes,
                        runPhase2 = runPhase2
                    )
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handledDirectRadialSave = false
        handleIncomingIntent(intent)
        if (hasWindowFocus()) {
            runClipboardCaptureAndDirectSave()
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val sharedText = incoming?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val fromShare = InstagramMetadataExtractor.extractFirstInstagramUrl(sharedText)
        if (!fromShare.isNullOrBlank()) {
            capturedInstagramUrl = fromShare
        } else {
            readClipboardInstagramUrl()?.let { capturedInstagramUrl = it }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .addPrimaryClipChangedListener(clipboardListener)
        }
    }

    override fun onPause() {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(clipboardListener)
        }
        super.onPause()
    }

    /**
     * Called by Android as soon as this transparent overlay Activity gains window focus.
     * At this exact moment, Android 14 permits reading the system clipboard!
     * Polls briefly in case Instagram writes the copied link a beat after focus lands.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            runClipboardCaptureAndDirectSave()
        }
    }

    private fun runClipboardCaptureAndDirectSave() {
        lifecycleScope.launch {
            var attempts = 0
            while (attempts < 5) {
                val clipUrl = readClipboardInstagramUrl()
                if (!clipUrl.isNullOrBlank()) {
                    capturedInstagramUrl = clipUrl
                    break
                }
                attempts++
                delay(if (attempts == 1) 150L else 450L)
            }

            // If user used the Hold-and-Slide Radial Menu to pick a specific board,
            // save immediately once window focus reads the clipboard URL and close!
            val preselectedBoardId = intent?.getLongExtra(EXTRA_PRESELECTED_BOARD_ID, -99L) ?: -99L
            if (preselectedBoardId != -99L && !handledDirectRadialSave) {
                handledDirectRadialSave = true
                val boardIds = if (preselectedBoardId > 0L) listOf(preselectedBoardId) else emptyList()
                val screenCtx = InstaPinAccessibilityService.screenContext.value
                savePinAndFinish(
                    rawUrl = capturedInstagramUrl,
                    author = intent?.getStringExtra(EXTRA_AUTHOR_HANDLE).orEmpty().ifBlank { screenCtx.authorHandle },
                    caption = intent?.getStringExtra(EXTRA_CAPTION).orEmpty().ifBlank { screenCtx.captionSnippet },
                    audioTrack = intent?.getStringExtra(EXTRA_AUDIO_TRACK).orEmpty().ifBlank { screenCtx.audioTrackName },
                    boardIds = boardIds,
                    moodIds = emptyList(),
                    notes = "",
                    runPhase2 = false
                )
            }
        }
    }

    private fun readClipboardInstagramUrl(): String? {
        return runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return null
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i)?.coerceToText(this)?.toString()
                val extracted = InstagramMetadataExtractor.extractFirstInstagramUrl(text)
                if (!extracted.isNullOrBlank()) return extracted
            }
            null
        }.getOrNull()
    }

    private fun savePinAndFinish(
        rawUrl: String?,
        author: String,
        caption: String,
        audioTrack: String,
        boardIds: List<Long>,
        moodIds: List<Long>,
        notes: String,
        runPhase2: Boolean
    ) {
        val app = applicationContext as InstaPinApp
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                app.repository.saveOrUpdateInstagramPin(
                    rawUrl = rawUrl,
                    screenAuthorHandle = author,
                    screenCaption = caption,
                    screenAudioTrack = audioTrack,
                    explicitBoardIds = boardIds,
                    explicitMoodIds = moodIds,
                    userNotes = notes,
                    captureSource = CaptureSource.ANDROID_SHARE_SHEET,
                    runPhase2Immediately = runPhase2
                )
            }
            val boardNames = saved.boards.joinToString(", ") { "${it.emoji} ${it.name}" }
            val hasVideoCode = saved.pin.instagramUrl.contains("/reel/") || saved.pin.instagramUrl.contains("/p/")
            val linkStatus = if (hasVideoCode) {
                "🔗 Exact Reel Video Saved"
            } else {
                val failedStep = InstaPinAccessibilityService.lastCaptureReport.value
                    ?.firstFailedStep?.let { " (auto-capture failed at: $it)" }.orEmpty()
                "Saved @${saved.pin.authorHandle}$failedStep"
            }
            Toast.makeText(
                this@ShareReceiverActivity,
                "$linkStatus -> $boardNames",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
