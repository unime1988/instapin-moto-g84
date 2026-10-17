package com.bluemachines.instapin

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.bluemachines.instapin.data.local.entity.CaptureSource
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import com.bluemachines.instapin.service.InstaPinAccessibilityService
import com.bluemachines.instapin.ui.overlay.QuickSaveOverlaySheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent Overlay Activity launched when the user taps "Share -> Pin to Board (InstaPin)"
 * inside Instagram. Captures the exact Reel URL immediately and presents the Pinterest Board/Mood picker
 * right on top of Instagram without leaving the Reel feed.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val extractedUrl = InstagramMetadataExtractor.extractFirstInstagramUrl(sharedText) ?: sharedText
        val app = applicationContext as InstaPinApp

        setContent {
            val boards by app.repository.allBoardsFlow.collectAsState(initial = emptyList())
            val moods by app.repository.allMoodsFlow.collectAsState(initial = emptyList())
            val screenContext by InstaPinAccessibilityService.screenContext.collectAsState()

            QuickSaveOverlaySheet(
                detectedUrl = extractedUrl,
                detectedAuthor = screenContext.authorHandle,
                detectedCaption = screenContext.captionSnippet,
                boards = boards,
                moods = moods,
                onDismiss = { finish() },
                onSavePin = { manualUrl, author, caption, boardIds, moodIds, notes, runPhase2 ->
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            app.repository.saveOrUpdateInstagramPin(
                                rawUrl = manualUrl.ifBlank { extractedUrl },
                                screenAuthorHandle = author,
                                screenCaption = caption,
                                screenAudioTrack = screenContext.audioTrackName,
                                explicitBoardIds = boardIds,
                                explicitMoodIds = moodIds,
                                userNotes = notes,
                                captureSource = CaptureSource.ANDROID_SHARE_SHEET,
                                runPhase2Immediately = runPhase2
                            )
                        }
                        val boardNames = saved.boards.joinToString(", ") { "${it.emoji} ${it.name}" }
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            "Pinned to $boardNames (${saved.pin.domainCategory})",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            )
        }
    }
}
