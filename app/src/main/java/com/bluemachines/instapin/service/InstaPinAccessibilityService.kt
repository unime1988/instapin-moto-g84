package com.bluemachines.instapin.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
 * 3. Performs a multi-strategy automated "Share -> Copy link" accessibility click sequence so the
 *    exact Reel video URL (`https://www.instagram.com/reel/...`) is copied onto the clipboard!
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
                    val extractedHandle = reelByMatch.groupValues[1]
                    if (isLikelyUsername(extractedHandle)) {
                        handleCandidate = extractedHandle
                    }
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
            "reels", "reel", "explore", "follow", "following", "message", "share", "like", "comment",
            "remix", "audio", "more", "home", "search", "profile", "sponsored", "suggested",
            "foryou", "clips", "posts", "tagged", "saved", "send", "copy", "link", "shop", "live"
        )
        if (cleaned.lowercase() in reservedUiWords) return false
        return cleaned.matches(Regex("""^[a-zA-Z0-9._]+$""")) && cleaned.any { it.isLetter() }
    }

    /**
     * 2-Step Automated Reel Permalink Capture:
     * Step 1: If "Copy link" is already visible on screen, click it directly.
     * Step 2: Otherwise, find Instagram's "Share" / "Send" (paper-plane) button on the Reel
     *         (by View ID, ContentDescription, or right-edge action-column position),
     *         click it, wait for the bottom share drawer to open, and click "Copy link"!
     */
    private suspend fun attemptFullShareAndCopyLinkSequence(): Boolean {
        // Step 1: Check if "Copy link" is already visible
        if (clickCopyLinkNodeIfVisible()) {
            delay(280)
            return true
        }

        // Step 2: Click Instagram's Share / Send Post paper-plane button on the Reel
        val clickedShare = clickInstagramShareButton()
        if (!clickedShare) return false

        // Wait up to ~1.25 seconds (5 polls of 250ms) for Instagram's Share drawer with "Copy link" to appear
        repeat(5) {
            delay(250)
            if (clickCopyLinkNodeIfVisible()) {
                delay(280)
                return true
            }
        }
        return false
    }

    private fun clickCopyLinkNodeIfVisible(): Boolean {
        val allRoots = buildList {
            rootInActiveWindow?.let { add(it) }
            runCatching {
                windows?.forEach { win ->
                    win.root?.let { add(it) }
                }
            }
        }.distinct()

        val copyKeywords = listOf("copy link", "copylink", "link copy")

        for (root in allRoots) {
            // 1. Direct text search
            val byTextNodes = root.findAccessibilityNodeInfosByText("Copy link")
                ?: root.findAccessibilityNodeInfosByText("Copy")
            val match = byTextNodes?.firstOrNull { n ->
                val txt = n.text?.toString()?.lowercase().orEmpty()
                val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
                val id = n.viewIdResourceName?.lowercase().orEmpty()
                copyKeywords.any { kw -> txt.contains(kw) || desc.contains(kw) || id.contains("copy_link") }
            }
            if (match != null && clickNodeOrTapBounds(match)) {
                return true
            }

            // 2. Full tree walk for contentDescription or viewId containing copy_link
            var walkedMatch: AccessibilityNodeInfo? = null
            fun searchCopy(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 26 || walkedMatch != null) return
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                val txt = node.text?.toString()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()

                if (id.contains("copy_link") ||
                    txt.contains("copy link") ||
                    desc.contains("copy link")
                ) {
                    walkedMatch = node
                    return
                }
                for (i in 0 until node.childCount) {
                    searchCopy(node.getChild(i), depth + 1)
                }
            }
            runCatching { searchCopy(root, 0) }
            if (walkedMatch != null && clickNodeOrTapBounds(walkedMatch!!)) {
                return true
            }
        }
        return false
    }

    private fun clickInstagramShareButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        var shareCandidate: AccessibilityNodeInfo? = null
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val rightColumnButtonCandidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

        fun searchShare(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 26 || shareCandidate != null) return
            val viewId = node.viewIdResourceName.orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()

            val isShareViewId = viewId.contains("direct_share") ||
                viewId.contains("clips_share") ||
                viewId.contains("row_feed_button_share") ||
                viewId.contains("share_button") ||
                viewId.contains("send_button") ||
                viewId.contains("ufi_share")

            val isShareDesc = desc.contains("share") ||
                desc.contains("send post") ||
                desc.contains("send reel") ||
                desc.contains("send to") ||
                desc.contains("reshare") ||
                text == "share" ||
                text == "send"

            if (isShareViewId || isShareDesc) {
                shareCandidate = node
                return
            }

            // Also record clickable icons in Instagram Reels' right-hand vertical action column
            // (x > 82% of screenWidth, y between 45% and 88% of screenHeight)
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() > (screenWidth * 0.82f) &&
                    rect.centerY() in (screenHeight * 0.45f).toInt()..(screenHeight * 0.88f).toInt() &&
                    rect.width() in 30..260
                ) {
                    rightColumnButtonCandidates.add(node to rect)
                }
            }

            for (i in 0 until node.childCount) {
                searchShare(node.getChild(i), depth + 1)
            }
        }

        runCatching { searchShare(root, 0) }
        if (shareCandidate != null) {
            return clickNodeOrTapBounds(shareCandidate!!)
        }

        // Fallback: In Instagram Reels, the right-side action column is ordered top-to-bottom:
        // [0] Like (Heart), [1] Comment, [2] Share/Send (Paper Plane), [3] More (...)
        val sortedRightButtons = rightColumnButtonCandidates
            .distinctBy { it.second.centerY() / 35 }
            .sortedBy { it.second.centerY() }

        if (sortedRightButtons.size >= 3) {
            val paperPlaneNode = sortedRightButtons[2].first
            return clickNodeOrTapBounds(paperPlaneNode)
        }

        return false
    }

    private fun clickNodeOrTapBounds(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // Fallback: dispatch physical tap gesture at the node's screen center coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                val path = Path().apply {
                    moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                return dispatchGesture(gesture, null, null)
            }
        }
        return false
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _isServiceConnected.value = false
        super.onDestroy()
    }
}
