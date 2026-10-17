package com.bluemachines.instapin.service

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveInstagramScreenContext(
    val isInstagramForeground: Boolean = false,
    val authorHandle: String = "",
    val captionSnippet: String = "",
    val audioTrackName: String = "",
    val detectedUrlFromScreen: String? = null,
    val updatedAtMillis: Long = 0L
)

/**
 * Phase 1 Accessibility Service for Moto G84 5G:
 * 1. Detects when Instagram (`com.instagram.android`) is in the foreground and AUTOMATICALLY
 *    starts `FloatingOverlayService` if it isn't already running!
 * 2. Reads visible text nodes from the active Instagram Reel screen to extract `@author_handle`,
 *    the visible caption, and audio track without modifying anything in Instagram.
 * 3. Performs a 2-step automated "Share -> Copy link" accessibility click sequence so the
 *    exact Reel video URL (`https://www.instagram.com/reel/...`) is captured instead of just the creator profile!
 */
class InstaPinAccessibilityService : AccessibilityService() {

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val INSTAPIN_PACKAGE = "com.bluemachines.instapin"

        private val _screenContext = MutableStateFlow(LiveInstagramScreenContext())
        val screenContext: StateFlow<LiveInstagramScreenContext> = _screenContext.asStateFlow()

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        @Volatile
        private var instance: InstaPinAccessibilityService? = null

        suspend fun triggerAutoCopyLinkAndWait(): Boolean {
            val svc = instance ?: return false
            return svc.attemptFullShareAndCopyLinkSequence()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        if (Settings.canDrawOverlays(this) && !FloatingOverlayService.isRunning) {
            FloatingOverlayService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (pkg == INSTAPIN_PACKAGE ||
            pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("gboard") ||
            pkg == "android"
        ) {
            return
        }

        val isIg = pkg == INSTAGRAM_PACKAGE || pkg.startsWith("com.instagram.")
        if (!isIg) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                _screenContext.value.isInstagramForeground
            ) {
                _screenContext.value = _screenContext.value.copy(
                    isInstagramForeground = false,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            return
        }

        if (!FloatingOverlayService.isRunning &&
            !FloatingOverlayService.manuallyPausedByUser &&
            Settings.canDrawOverlays(this)
        ) {
            FloatingOverlayService.start(this)
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            _screenContext.value = _screenContext.value.copy(
                isInstagramForeground = true,
                updatedAtMillis = System.currentTimeMillis()
            )
            return
        }

        val parsed = parseInstagramNodeTree(rootNode)
        _screenContext.value = LiveInstagramScreenContext(
            isInstagramForeground = true,
            authorHandle = parsed.authorHandle.ifBlank { _screenContext.value.authorHandle },
            captionSnippet = parsed.captionSnippet.ifBlank { _screenContext.value.captionSnippet },
            audioTrackName = parsed.audioTrackName.ifBlank { _screenContext.value.audioTrackName },
            detectedUrlFromScreen = parsed.detectedUrlFromScreen ?: _screenContext.value.detectedUrlFromScreen,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun parseInstagramNodeTree(root: AccessibilityNodeInfo): LiveInstagramScreenContext {
        val collectedTexts = mutableListOf<String>()
        val contentDescs = mutableListOf<String>()
        var handleCandidate = ""
        var audioCandidate = ""
        var captionCandidate = ""

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28) return

            val viewId = node.viewIdResourceName.orEmpty().lowercase()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()

            if (text.isNotBlank()) {
                collectedTexts.add(text)
                if (viewId.contains("username") || viewId.contains("clips_author") || viewId.contains("header")) {
                    if (isLikelyUsername(text)) {
                        handleCandidate = text.removePrefix("@")
                    }
                }
                if (viewId.contains("caption") || viewId.contains("description")) {
                    if (text.length > captionCandidate.length) {
                        captionCandidate = text
                    }
                }
                if (viewId.contains("audio") || viewId.contains("music") || text.contains("Original audio", ignoreCase = true)) {
                    audioCandidate = text
                }
            }

            if (desc.isNotBlank()) {
                contentDescs.add(desc)
                val reelByMatch = Regex("""(?:Reel by|Profile picture of|Photo by)\s+@?([A-Za-z0-9._]{2,30})""", RegexOption.IGNORE_CASE)
                    .find(desc)
                if (reelByMatch != null && handleCandidate.isBlank()) {
                    handleCandidate = reelByMatch.groupValues[1]
                }
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        runCatching { walk(root, 0) }

        if (handleCandidate.isBlank()) {
            handleCandidate = collectedTexts.firstOrNull { isLikelyUsername(it) }
                ?.removePrefix("@")
                .orEmpty()
        }

        if (captionCandidate.isBlank()) {
            captionCandidate = collectedTexts
                .filter { it.length >= 18 && !it.equals("Original audio", ignoreCase = true) }
                .maxByOrNull { it.length }
                .orEmpty()
        }

        val urlInText = (collectedTexts + contentDescs).firstNotNullOfOrNull {
            com.bluemachines.instapin.data.remote.InstagramMetadataExtractor.extractFirstInstagramUrl(it)
        }

        return LiveInstagramScreenContext(
            isInstagramForeground = true,
            authorHandle = handleCandidate,
            captionSnippet = captionCandidate,
            audioTrackName = audioCandidate,
            detectedUrlFromScreen = urlInText,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun isLikelyUsername(text: String): Boolean {
        val cleaned = text.removePrefix("@").trim()
        if (cleaned.length !in 3..30) return false
        if (cleaned.contains(" ")) return false
        val reservedUiWords = setOf(
            "reels", "follow", "following", "message", "share", "like", "comment",
            "remix", "audio", "more", "home", "search", "profile", "sponsored"
        )
        if (cleaned.lowercase() in reservedUiWords) return false
        return cleaned.matches(Regex("""^[a-zA-Z0-9._]+$""")) && cleaned.any { it.isLetter() }
    }

    /**
     * 2-Step Automated Reel Permalink Capture:
     * Step 1: If "Copy link" is already visible on screen, click it directly.
     * Step 2: Otherwise, find Instagram's "Share" / "Send post" (paper-plane) button on the Reel,
     *         click it, wait 450ms for the bottom share drawer to open, and click "Copy link"!
     */
    private suspend fun attemptFullShareAndCopyLinkSequence(): Boolean {
        // Step 1: Check if "Copy link" is already visible
        if (clickCopyLinkNodeIfVisible()) {
            delay(220)
            return true
        }

        // Step 2: Click Instagram's Share / Send Post paper-plane button on the Reel
        val clickedShare = clickInstagramShareButton()
        if (!clickedShare) return false

        // Wait up to ~750ms (3 polls of 250ms) for Instagram's Share drawer with "Copy link" to appear
        repeat(3) {
            delay(260)
            if (clickCopyLinkNodeIfVisible()) {
                delay(220)
                return true
            }
        }
        return false
    }

    private fun clickCopyLinkNodeIfVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val copyLabels = listOf("Copy link", "Copy Link", "Copy")
        for (label in copyLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val target = nodes?.firstOrNull { n ->
                val txt = n.text?.toString().orEmpty()
                val desc = n.contentDescription?.toString().orEmpty()
                (txt.contains("Copy link", ignoreCase = true) || desc.contains("Copy link", ignoreCase = true)) &&
                    (n.isClickable || n.parent?.isClickable == true || n.parent?.parent?.isClickable == true)
            }
            if (target != null) {
                return clickNodeOrClickableAncestor(target)
            }
        }
        return false
    }

    private fun clickInstagramShareButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        var shareCandidate: AccessibilityNodeInfo? = null

        fun searchShare(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || shareCandidate != null) return
            val viewId = node.viewIdResourceName.orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()

            val isShareViewId = viewId.contains("direct_share") ||
                viewId.contains("clips_share") ||
                viewId.contains("row_feed_button_share") ||
                viewId.contains("share_button")

            val isShareDesc = desc == "share" ||
                desc == "send post" ||
                desc.startsWith("share ") ||
                desc.startsWith("send ")

            if (isShareViewId || isShareDesc) {
                shareCandidate = node
                return
            }

            for (i in 0 until node.childCount) {
                searchShare(node.getChild(i), depth + 1)
            }
        }

        runCatching { searchShare(root, 0) }
        return shareCandidate?.let { clickNodeOrClickableAncestor(it) } ?: false
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 4) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _isServiceConnected.value = false
        super.onDestroy()
    }
}
