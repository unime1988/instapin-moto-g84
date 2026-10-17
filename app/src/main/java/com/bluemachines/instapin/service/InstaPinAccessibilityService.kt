package com.bluemachines.instapin.service

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 * 3. Can optionally click Instagram's "Share -> Copy link" button if the user triggers a 1-tap
 *    radial save and wants the exact Reel URL copied automatically.
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

        fun triggerAutoCopyLinkIfPossible(): Boolean {
            return instance?.attemptAutoCopyLinkGesture() ?: false
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

        // Ignore transient system UI, keyboard, and our own overlay events so Instagram state stays active
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

        // Automatically start the Floating Hover Bubble whenever the user opens Instagram (unless manually paused)
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
            detectedUrlFromScreen = parsed.detectedUrlFromScreen,
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

    private fun attemptAutoCopyLinkGesture(): Boolean {
        val root = rootInActiveWindow ?: return false
        val copyNodes = root.findAccessibilityNodeInfosByText("Copy link")
        val copyNode = copyNodes?.firstOrNull { it.isClickable || it.parent?.isClickable == true }
        if (copyNode != null) {
            return if (copyNode.isClickable) {
                copyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                copyNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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
