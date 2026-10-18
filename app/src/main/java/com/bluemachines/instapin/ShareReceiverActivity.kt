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
 * 2. When Instagram/Android shares a Reel to "Pin to Board (InstaPin)" (manually or via the accessibility fallback).
 *
 * Link priority:
 * 1. URL delivered by the share intent (EXTRA_TEXT) - always exact, never overwritten by the clipboard.
 * 2. Clipboard URL read once this focused Activity has window focus (the only place Android 10+
 *    allows clipboard reads) - but only if it was copied recently, so an old Reel link left on
 *    the clipboard is never saved against the wrong Reel.
 */
class ShareReceiverActivity : ComponentActivity() {

    data class PendingFloatingSave(
        val preselectedBoardId: Long,
        val author: String,
        val caption: String,
        val audioTrack: String,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    companion object {
        const val EXTRA_FROM_FLOATING_PIN = "extra_from_floating_pin"
        const val EXTRA_PRESELECTED_BOARD_ID = "extra_preselected_board_id"
        const val EXTRA_AUTHOR_HANDLE = "extra_author_handle"
        const val EXTRA_CAPTION = "extra_caption"
        const val EXTRA_AUDIO_TRACK = "extra_audio_track"
        const val EXTRA_TAP_STARTED_AT_MS = "extra_tap_started_at_ms"

        /** Single tap on the bubble: show the save card instead of auto-saving. */
        const val NO_PRESELECTED_BOARD = -99L

        private const val PENDING_SAVE_MAX_AGE_MS = 20_000L
        private const val CLIPBOARD_MAX_AGE_MS = 120_000L
        private const val CLIPBOARD_PRE_CAPTURE_GRACE_MS = 30_000L

        /**
         * Set by FloatingOverlayService right before the save card opens, so if the URL arrives
         * through the Android share screen (ACTION_SEND) we still know which board was chosen.
         */
        @Volatile
        var pendingFloatingSave: PendingFloatingSave? = null
    }

    private var capturedInstagramUrl by mutableStateOf("")
    private var urlCameFromShareIntent = false
    private var handledDirectRadialSave = false
    private var activeSaveContext by mutableStateOf<PendingFloatingSave?>(null)
    private var tapStartedAtMs: Long = 0L
    private var tapToOpenCardMs: Long? = null

    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            // Live-capture: the clipboard just changed while this screen is open, so it is fresh.
            if (!urlCameFromShareIntent) {
                readClipboardInstagramUrl(requireFresh = false)?.let { capturedInstagramUrl = it }
            }
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

            val effectiveAuthor = activeSaveContext?.author?.takeIf { it.isNotBlank() }
                ?: screenContext.authorHandle
            val effectiveCaption = activeSaveContext?.caption?.takeIf { it.isNotBlank() }
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
                    // Explicit user tap: accept whatever Reel link is on the clipboard.
                    readClipboardInstagramUrl(requireFresh = false)?.also { capturedInstagramUrl = it }
                },
                onCreateBoard = { name -> app.repository.createQuickSaveBoard(name) },
                onSavePin = { manualUrl, author, caption, boardIds, moodIds, notes, runPhase2 ->
                    savePinAndFinish(
                        rawUrl = manualUrl.trim().ifBlank { capturedInstagramUrl },
                        author = author.ifBlank { effectiveAuthor },
                        caption = caption.ifBlank { effectiveCaption },
                        audioTrack = activeSaveContext?.audioTrack?.takeIf { it.isNotBlank() }
                            ?: screenContext.audioTrackName,
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
        val fromFloatingPin = incoming?.getBooleanExtra(EXTRA_FROM_FLOATING_PIN, false) == true

        // Consume the bubble's pending board choice if it is recent (covers both the direct
        // floating launch and the ACTION_SEND launch from the Android share screen).
        val pending = pendingFloatingSave?.takeIf {
            System.currentTimeMillis() - it.createdAtMillis <= PENDING_SAVE_MAX_AGE_MS
        }
        pendingFloatingSave = null

        val incomingTapMs = incoming?.getLongExtra(EXTRA_TAP_STARTED_AT_MS, 0L)?.takeIf { it > 0L }
            ?: pending?.createdAtMillis
            ?: System.currentTimeMillis()
        tapStartedAtMs = incomingTapMs
        tapToOpenCardMs = null

        activeSaveContext = when {
            fromFloatingPin -> PendingFloatingSave(
                preselectedBoardId = incoming?.getLongExtra(EXTRA_PRESELECTED_BOARD_ID, NO_PRESELECTED_BOARD)
                    ?: NO_PRESELECTED_BOARD,
                author = incoming?.getStringExtra(EXTRA_AUTHOR_HANDLE).orEmpty(),
                caption = incoming?.getStringExtra(EXTRA_CAPTION).orEmpty(),
                audioTrack = incoming?.getStringExtra(EXTRA_AUDIO_TRACK).orEmpty(),
                createdAtMillis = incomingTapMs
            )
            !fromShare.isNullOrBlank() -> pending
            else -> null
        }

        if (!fromShare.isNullOrBlank()) {
            capturedInstagramUrl = fromShare
            urlCameFromShareIntent = true
        } else {
            urlCameFromShareIntent = false
            capturedInstagramUrl = readClipboardInstagramUrl(requireFresh = true).orEmpty()
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
     * At this exact moment, Android 10+ permits reading the system clipboard.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            runClipboardCaptureAndDirectSave()
        }
    }

    private fun runClipboardCaptureAndDirectSave() {
        if (tapToOpenCardMs == null && tapStartedAtMs > 0L) {
            tapToOpenCardMs = (System.currentTimeMillis() - tapStartedAtMs).coerceAtLeast(1L)
        }
        lifecycleScope.launch {
            if (!urlCameFromShareIntent) {
                var attempts = 0
                while (attempts < 3) {
                    val clipUrl = readClipboardInstagramUrl(requireFresh = true)
                    if (!clipUrl.isNullOrBlank()) {
                        capturedInstagramUrl = clipUrl
                        break
                    }
                    attempts++
                    delay(if (attempts == 1) 50L else 120L)
                }
            }

            // Hold-and-slide board choice: save straight away ONLY when the exact Reel link
            // was captured. Otherwise keep the card open with the warning + Grab button,
            // so a Legacy (profile-only) pin is never saved silently.
            val context = activeSaveContext ?: return@launch
            val preselectedBoardId = context.preselectedBoardId
            if (preselectedBoardId == NO_PRESELECTED_BOARD || handledDirectRadialSave) return@launch
            if (capturedInstagramUrl.isBlank()) {
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "Exact Reel link not captured - tap Grab or paste the link, then pick the board",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            handledDirectRadialSave = true
            val screenCtx = InstaPinAccessibilityService.screenContext.value
            savePinAndFinish(
                rawUrl = capturedInstagramUrl,
                author = context.author.ifBlank { screenCtx.authorHandle },
                caption = context.caption.ifBlank { screenCtx.captionSnippet },
                audioTrack = context.audioTrack.ifBlank { screenCtx.audioTrackName },
                boardIds = if (preselectedBoardId > 0L) listOf(preselectedBoardId) else emptyList(),
                moodIds = emptyList(),
                notes = "",
                runPhase2 = false
            )
        }
    }

    /**
     * Reads a Reel/Post URL from the clipboard. With [requireFresh], the clip must have been
     * copied during the current capture run (or up to 20s before it, when the user copied the
     * link manually and then tapped the bubble), or within the last 90s if no capture ran.
     * This stops an old Reel link on the clipboard from being saved against a different Reel.
     */
    private fun readClipboardInstagramUrl(requireFresh: Boolean): String? {
        return runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return null
            if (requireFresh) {
                val copiedAt = clip.description?.timestamp ?: 0L
                val now = System.currentTimeMillis()
                val report = InstaPinAccessibilityService.lastCaptureReport.value
                val captureStartedAt = report?.startedAtMillis?.takeIf { now - it <= PENDING_SAVE_MAX_AGE_MS }
                val oldestAccepted = captureStartedAt?.let { it - CLIPBOARD_PRE_CAPTURE_GRACE_MS }
                    ?: (now - CLIPBOARD_MAX_AGE_MS)
                if (copiedAt in 1 until oldestAccepted) return null
            }
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
            val saveStartedAt = System.currentTimeMillis()
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
                    runPhase2Immediately = runPhase2,
                    tapToOpenCardMs = tapToOpenCardMs
                )
            }
            val saveElapsedMs = (System.currentTimeMillis() - saveStartedAt).coerceAtLeast(1L)
            val boardNames = saved.boards.joinToString(", ") { "${it.emoji} ${it.name}" }
            val hasVideoCode = saved.pin.instagramUrl.contains("/reel/") ||
                saved.pin.instagramUrl.contains("/p/") ||
                saved.pin.instagramUrl.contains("/share/")
            val linkStatus = if (hasVideoCode) {
                "🔗 Saved in ${saveElapsedMs}ms"
            } else {
                val failedStep = InstaPinAccessibilityService.lastCaptureReport.value
                    ?.firstFailedStep?.let { " (auto-capture failed at: $it)" }.orEmpty()
                "Saved @${saved.pin.authorHandle} (${saveElapsedMs}ms, without exact link)$failedStep"
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
