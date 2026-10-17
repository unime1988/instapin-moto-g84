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
 * Step-by-step diagnostic report of the last automated "Share -> InstaPin/Copy link" capture run.
 * Surfaced in the Quick Save card and Settings so the exact failing step is always visible.
 */
enum class ReelCaptureOutcome {
    /** "Copy link" was tapped; the exact URL is on the clipboard for a focused Activity to read. */
    COPIED_TO_CLIPBOARD,
    /** InstaPin was tapped in the Android share screen; the URL arrives via ACTION_SEND EXTRA_TEXT. */
    SENT_TO_INSTAPIN_SHARE_TARGET,
    FAILED
}

data class ReelLinkCaptureReport(
    val timestampMillis: Long = 0L,
    val startedAtMillis: Long = 0L,
    val steps: List<Pair<String, Boolean>> = emptyList(),
    val note: String = "",
    val capturedUrl: String? = null,
    val screenElements: List<String> = emptyList()
) {
    val succeeded: Boolean get() = steps.isNotEmpty() && steps.last().second
    val firstFailedStep: String? get() = steps.firstOrNull { !it.second }?.first
    fun toReadableLine(): String =
        if (steps.isEmpty()) "No capture attempt yet"
        else steps.joinToString("  →  ") { (label, ok) -> "$label ${if (ok) "✓" else "✗"}" }
}

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

        private val _lastCaptureReport = MutableStateFlow<ReelLinkCaptureReport?>(null)
        val lastCaptureReport: StateFlow<ReelLinkCaptureReport?> = _lastCaptureReport.asStateFlow()

        @Volatile
        private var instance: InstaPinAccessibilityService? = null

        suspend fun triggerAutoCopyLinkAndWait(): ReelCaptureOutcome {
            val svc = instance
            if (svc == null) {
                val now = System.currentTimeMillis()
                _lastCaptureReport.value = ReelLinkCaptureReport(
                    timestampMillis = now,
                    startedAtMillis = now,
                    steps = listOf("Accessibility service running" to false),
                    note = "Enable InstaPin in Settings -> Accessibility so it can tap Share/Copy link"
                )
                return ReelCaptureOutcome.FAILED
            }
            return svc.attemptFullShareAndCopyLinkSequence()
        }

        private val SHARE_SHEET_MARKERS = setOf(
            "copy link", "add to story", "share to", "share to other apps", "send separately", "whatsapp"
        )
        private val SHARE_TO_OTHER_APPS_LABELS = setOf(
            "share to", "share to other apps", "share via", "other apps"
        )

        /** Lowercases, trims and strips a trailing ellipsis so "Share to…" == "share to". */
        private fun normalizeLabel(raw: CharSequence?): String =
            raw?.toString()?.lowercase()?.trim()
                ?.removeSuffix("…")?.removeSuffix("...")?.trim()
                .orEmpty()
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

    /** Bounds of the Reel's Share (✈) button we tapped, so it is never re-matched as a sheet item. */
    private var lastClickedShareRect: Rect? = null

    /**
     * Automated Reel Permalink Capture (fully diagnosed, safe):
     * 1. Confirm Instagram is on screen.
     * 2. If Instagram's share sheet is not already open, tap the Reel's Share (✈) button
     *    and WAIT until the share sheet is confirmed open (nothing else happens otherwise).
     * 3. Tap "Copy link" (exact label only). If it is hidden, scroll ONLY the share-sheet row
     *    that contains the sheet's own labels - never the Reels feed, so we can never move
     *    on to the next Reel and copy the wrong link.
     * 4. Fallback: tap exactly "Share to…" (never anything mentioning story) to open the
     *    Android share screen, then tap "Pin to Board (InstaPin)" -> the URL arrives via
     *    ACTION_SEND EXTRA_TEXT with no clipboard involved.
     * On failure, a record of the visible screen elements is saved so the real Instagram
     * labels/IDs on this phone can be inspected instead of guessed.
     */
    private suspend fun attemptFullShareAndCopyLinkSequence(): ReelCaptureOutcome {
        val startedAt = System.currentTimeMillis()
        val steps = mutableListOf<Pair<String, Boolean>>()

        fun publish(note: String = "", elements: List<String> = emptyList()) {
            _lastCaptureReport.value = ReelLinkCaptureReport(
                timestampMillis = System.currentTimeMillis(),
                startedAtMillis = startedAt,
                steps = steps.toList(),
                note = note,
                screenElements = elements
            )
        }

        val igForeground = isInstagramActuallyForeground()
        steps += "Instagram on screen" to igForeground
        if (!igForeground) {
            publish("Instagram is not in the foreground - open a Reel first")
            return ReelCaptureOutcome.FAILED
        }

        if (isShareSheetOpen()) {
            steps += "Share sheet open" to true
        } else {
            val shareRect = clickInstagramShareButton()
            steps += "Share (✈) button tap" to (shareRect != null)
            if (shareRect == null) {
                publish("Could not find Instagram's Share button on this screen", recordScreenElements())
                return ReelCaptureOutcome.FAILED
            }
            lastClickedShareRect = shareRect

            val opened = waitUntil(timeoutMs = 2_500L, pollMs = 100L) {
                isShareSheetOpen()
            }
            steps += "Share sheet opened (${System.currentTimeMillis() - startedAt}ms)" to opened
            if (!opened) {
                publish("Tapped Share but Instagram's share sheet did not appear", recordScreenElements())
                return ReelCaptureOutcome.FAILED
            }
        }

        // Playwright-style conditional wait: re-read the accessibility tree every 100ms and
        // continue immediately when the exact Copy link node appears. The timeout is a maximum,
        // not a mandatory delay.
        var copied = waitUntil(timeoutMs = 1_500L, pollMs = 100L) {
            clickCopyLinkInShareSheet()
        }
        if (!copied && scrollShareSheetRowOnly()) {
            copied = waitUntil(timeoutMs = 1_000L, pollMs = 100L) {
                clickCopyLinkInShareSheet()
            }
        }
        steps += "Copy link tap (${System.currentTimeMillis() - startedAt}ms)" to copied
        if (copied) {
            publish("Copy link tapped; opening focused InstaPin receiver")
            return ReelCaptureOutcome.COPIED_TO_CLIPBOARD
        }

        // Fallback: Share to… -> Android share screen -> Pin to Board (InstaPin)
        val elementsBeforeFallback = recordScreenElements()
        val openedAndroidShare = clickShareToOtherApps()
        steps += "Share to… tap" to openedAndroidShare
        if (openedAndroidShare) {
            val sentToInstaPin = waitUntil(timeoutMs = 2_500L, pollMs = 125L) {
                clickInstaPinShareTarget()
            }
            steps += "InstaPin share target" to sentToInstaPin
            if (sentToInstaPin) {
                publish("Sent the Reel to InstaPin through the Android share screen")
                return ReelCaptureOutcome.SENT_TO_INSTAPIN_SHARE_TARGET
            }
        }

        publish(
            "Share sheet opened but 'Copy link' was not found on this Instagram version. " +
                "Tap the Reel's Share -> Copy link yourself, then tap Grab in the card.",
            elementsBeforeFallback
        )
        return ReelCaptureOutcome.FAILED
    }

    private suspend fun waitUntil(
        timeoutMs: Long,
        pollMs: Long,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (condition()) return true
            delay(pollMs)
        } while (System.currentTimeMillis() < deadline)
        return condition()
    }

    private fun isInstagramActuallyForeground(): Boolean {
        if (rootInActiveWindow?.packageName?.toString() == INSTAGRAM_PACKAGE) return true
        return runCatching {
            windows?.any { it.root?.packageName?.toString() == INSTAGRAM_PACKAGE } == true
        }.getOrDefault(false)
    }

    /**
     * Roots of all windows EXCEPT InstaPin's own overlay/UI. Our own bubble
     * ("InstaPin Hover Button") and Quick Save card ("...Share -> Copy link..." tip text)
     * would otherwise be false matches for the InstaPin share target and Copy link searchers.
     */
    private fun allWindowRoots(): List<AccessibilityNodeInfo> {
        return buildList {
            rootInActiveWindow?.let { add(it) }
            runCatching {
                windows?.forEach { win ->
                    win.root?.let { add(it) }
                }
            }
        }
            .distinct()
            .filter { it.packageName?.toString() != INSTAPIN_PACKAGE }
    }

    /** Depth-first search across all non-InstaPin windows; returns the first matching node. */
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in allWindowRoots()) {
            var match: AccessibilityNodeInfo? = null
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || match != null) return
                if (runCatching { predicate(node) }.getOrDefault(false)) {
                    match = node
                    return
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }
            runCatching { walk(root, 0) }
            match?.let { return it }
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun isShareSheetMarker(node: AccessibilityNodeInfo): Boolean {
        val txt = normalizeLabel(node.text)
        val desc = normalizeLabel(node.contentDescription)
        return txt in SHARE_SHEET_MARKERS || desc in SHARE_SHEET_MARKERS
    }

    /** True only when one of the share sheet's own exact labels is visible. */
    private fun isShareSheetOpen(): Boolean = findNode { isShareSheetMarker(it) } != null

    /** Taps a node whose exact label is "Copy link" (or whose view ID is copy_link). */
    private fun clickCopyLinkInShareSheet(): Boolean {
        val node = findNode { n ->
            val txt = normalizeLabel(n.text)
            val desc = normalizeLabel(n.contentDescription)
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            txt == "copy link" || desc == "copy link" || id.endsWith("copy_link") || id.contains("copy_link_")
        } ?: return false
        return clickNodeOrTapBounds(node)
    }

    /**
     * Scrolls forward ONLY a scrollable container that (a) holds one of the share sheet's
     * own labels and (b) is clearly smaller than the full screen and sits in the lower part
     * of it. The full-screen Reels feed can never qualify, so this can never advance the feed.
     * No swipe-gesture fallback on purpose.
     */
    private fun scrollShareSheetRowOnly(): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val anchor = findNode { isShareSheetMarker(it) } ?: return false
        var current: AccessibilityNodeInfo? = anchor.parent
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isScrollable) {
                val r = boundsOf(current)
                val isSmallLowerContainer = r.height() < (screenHeight * 0.6f) && r.top > (screenHeight * 0.3f)
                if (isSmallLowerContainer) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                return false
            }
            current = current.parent
            hops++
        }
        return false
    }

    /**
     * Taps exactly "Share to…" / "Share to other apps" / "Share via" / "Other apps".
     * Anything mentioning story/stories is never tapped, and the Reel's own Share (✈)
     * button is excluded by its bounds.
     */
    private fun clickShareToOtherApps(): Boolean {
        val excluded = lastClickedShareRect
        val node = findNode { n ->
            val txt = normalizeLabel(n.text)
            val desc = normalizeLabel(n.contentDescription)
            val label = if (txt.isNotBlank()) txt else desc
            label in SHARE_TO_OTHER_APPS_LABELS &&
                !label.contains("story") &&
                (excluded == null || boundsOf(n) != excluded)
        } ?: return false
        return clickNodeOrTapBounds(node)
    }

    /**
     * Taps InstaPin's own entry ("Pin to Board (InstaPin)") in the Android share screen.
     * Android then delivers the exact Reel URL to ShareReceiverActivity via ACTION_SEND.
     */
    private fun clickInstaPinShareTarget(): Boolean {
        val node = findNode { n ->
            val txt = normalizeLabel(n.text)
            val desc = normalizeLabel(n.contentDescription)
            txt.contains("instapin") || desc.contains("instapin") ||
                txt.startsWith("pin to board") || desc.startsWith("pin to board")
        } ?: return false
        return clickNodeOrTapBounds(node)
    }

    /**
     * Records up to 80 visible screen elements (label, description, view ID, clickable) from
     * non-InstaPin windows. Text is cut to 40 characters. Stored on-device only; the user
     * chooses whether to copy it from Settings.
     */
    private fun recordScreenElements(): List<String> {
        val lines = mutableListOf<String>()
        for (root in allWindowRoots()) {
            val pkg = root.packageName?.toString().orEmpty().substringAfterLast('.')
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || lines.size >= 80) return
                val txt = node.text?.toString()?.trim().orEmpty().take(40)
                val desc = node.contentDescription?.toString()?.trim().orEmpty().take(40)
                val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                if (txt.isNotBlank() || desc.isNotBlank() || (node.isClickable && id.isNotBlank())) {
                    val parts = buildList {
                        if (txt.isNotBlank()) add("text='$txt'")
                        if (desc.isNotBlank()) add("desc='$desc'")
                        if (id.isNotBlank()) add("id=$id")
                        if (node.isClickable) add("click")
                        if (node.isScrollable) add("scroll")
                    }
                    lines += "[$pkg] ${parts.joinToString(" ")}"
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }
            runCatching { walk(root, 0) }
        }
        return lines
    }

    /**
     * Taps the Reel's Share (✈) button and returns its screen bounds, or null if not found.
     * Prefers a share-described node in the right-hand action column; falls back to the 3rd
     * icon of that column (Like, Comment, Share, More).
     */
    private fun clickInstagramShareButton(): Rect? {
        val allRoots = allWindowRoots()
        if (allRoots.isEmpty()) return null
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val shareCandidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val rightColumnButtonCandidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

        fun searchShare(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val viewId = node.viewIdResourceName.orEmpty().lowercase()
            val desc = normalizeLabel(node.contentDescription)
            val text = normalizeLabel(node.text)

            val isShareViewId = viewId.contains("direct_share") ||
                viewId.contains("clips_share") ||
                viewId.contains("row_feed_button_share") ||
                viewId.contains("share_button") ||
                viewId.contains("send_button") ||
                viewId.contains("ufi_share")

            val isShareDesc = desc == "share" ||
                desc == "send" ||
                desc.startsWith("send post") ||
                desc.startsWith("send reel") ||
                desc.startsWith("share reel") ||
                desc.startsWith("share post") ||
                text == "share" ||
                text == "send"

            val mentionsStory = desc.contains("story") || text.contains("story")
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if ((isShareViewId || isShareDesc) && !mentionsStory) {
                shareCandidates.add(node to rect)
            }

            if (node.isClickable &&
                rect.centerX() > (screenWidth * 0.82f) &&
                rect.centerY() in (screenHeight * 0.45f).toInt()..(screenHeight * 0.88f).toInt() &&
                rect.width() in 30..260
            ) {
                rightColumnButtonCandidates.add(node to rect)
            }

            for (i in 0 until node.childCount) {
                searchShare(node.getChild(i), depth + 1)
            }
        }

        allRoots.forEach { runCatching { searchShare(it, 0) } }

        val chosen = shareCandidates.firstOrNull {
            it.second.centerX() > (screenWidth * 0.7f) &&
                it.second.centerY() in (screenHeight * 0.25f).toInt()..(screenHeight * 0.92f).toInt()
        } ?: shareCandidates.firstOrNull()
            ?: rightColumnButtonCandidates
                .distinctBy { it.second.centerY() / 35 }
                .sortedBy { it.second.centerY() }
                .getOrNull(2)
            ?: return null

        return if (clickNodeOrTapBounds(chosen.first)) chosen.second else null
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
