package com.bluemachines.instapin.data.remote

import com.bluemachines.instapin.data.local.entity.VideoAnalysis
import com.bluemachines.instapin.data.local.entity.VideoPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class Phase1ClassificationResult(
    val domainCategory: String = "Video Editing",
    val subjectTopic: String = "",
    val suggestedBoards: List<String> = emptyList(),
    val suggestedMoods: List<String> = emptyList(),
    val oneLineSummary: String = ""
)

@Serializable
data class Phase2DeepAnalysisDto(
    val executiveSummary: String = "",
    val actionableTakeaways: List<String> = emptyList(),
    val toolsAndResources: List<String> = emptyList(),
    val fullTranscriptAndOnScreenText: String = "",
    val hookBreakdown: String = "",
    val bodyStructureBreakdown: String = "",
    val ctaBreakdown: String = "",
    val editingAndVisualNotes: String = "",
    val alternativeHooks: List<String> = emptyList(),
    val recreateTeleprompterScript: String = "",
    val shotListAndEditChecklist: List<String> = emptyList()
)

/**
 * Handles both:
 * - Phase 1: Fast Domain/Subject/Board/Mood categorization (with instant deterministic keyword fallback if offline/no key)
 * - Phase 2: Deep Video Analysis, Transcript/Hook/Structure Breakdown, and Repurpose/Recreate Script Studio via Google Gemini API.
 * - Phase 3: Batch categorization of historical Instagram liked/saved posts.
 */
class GeminiAiService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Phase 1: Categorizes an Instagram Reel into Domain, Subject, Boards, and Moods.
     * Uses Gemini 2.5 Flash if API key is configured; falls back to smart local heuristic classifier if offline or no key yet.
     */
    suspend fun classifyPinPhase1(
        apiKey: String,
        model: String = "gemini-2.5-flash",
        authorHandle: String,
        caption: String,
        audioTrack: String,
        availableBoardNames: List<String>,
        availableMoodNames: List<String>
    ): Phase1ClassificationResult = withContext(Dispatchers.IO) {
        val localFallback = heuristicClassify(authorHandle, caption, availableBoardNames, availableMoodNames)
        if (apiKey.isBlank()) return@withContext localFallback

        val prompt = """
            You are an intelligent Instagram Reel organizer for a creator who saves educational videos across domains like Video Editing, Trading, Health & Fitness, Jobs & Career, and AI & Tech Tools.
            Analyze the following Instagram Reel metadata and return ONLY valid JSON matching this schema:
            {
              "domainCategory": "Primary domain (e.g., Video Editing, Trading, Health & Fitness, Jobs & Career, AI & Tech Tools, Mindset, Design)",
              "subjectTopic": "Specific 3-6 word subject (e.g., CapCut Speed Ramp Transitions, Gold Scalping Liquidity Sweep, High Protein Meal Prep)",
              "suggestedBoards": ["1 to 2 best matching boards from: ${availableBoardNames.joinToString(", ")}"],
              "suggestedMoods": ["1 to 2 best matching moods from: ${availableMoodNames.joinToString(", ")}"],
              "oneLineSummary": "Concise 1-sentence summary of what this Reel teaches"
            }

            Reel Creator: @$authorHandle
            Reel Caption & Hashtags: $caption
            Audio Track: $audioTrack
        """.trimIndent()

        runCatching {
            val responseText = callGeminiJson(apiKey, model, prompt)
            json.decodeFromString<Phase1ClassificationResult>(responseText)
        }.getOrDefault(localFallback)
    }

    /**
     * Phase 2: Deeply analyzes a saved Reel to extract Summary, Actionable Takeaways, Tools,
     * Spoken/Visual Transcript, Hook (0-3s) & Structure Breakdown, Editing Notes, and a complete
     * Repurpose/Recreate Script + Shot List.
     */
    suspend fun analyzeVideoPhase2(
        apiKey: String,
        model: String = "gemini-2.5-flash",
        pin: VideoPin,
        creatorNiche: String
    ): VideoAnalysis = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext generateSmartOfflineAnalysis(pin, creatorNiche)
        }

        val prompt = """
            You are an expert short-form video strategist, scriptwriter, and content repurposing producer.
            Analyze this saved Instagram Reel so the user can extract every bit of knowledge and recreate/repurpose a high-performing version in their own style.

            Reel URL: ${pin.instagramUrl}
            Creator: @${pin.authorHandle} (${pin.authorDisplayName})
            Domain: ${pin.domainCategory}
            Subject: ${pin.subjectTopic}
            Caption & Hashtags: ${pin.caption}
            Audio Track: ${pin.audioTrackName}
            User's Personal Notes: ${pin.userNotes}
            User's Creator Positioning: $creatorNiche

            Return ONLY a valid JSON object with the exact keys below:
            {
              "executiveSummary": "Clear 2-3 sentence summary of the core concept and why this video is valuable.",
              "actionableTakeaways": [
                "Step 1: Specific actionable takeaway or lesson",
                "Step 2: ...",
                "Step 3: ..."
              ],
              "toolsAndResources": [
                "Every software tool, indicator, website, book, or shortcut mentioned or implied"
              ],
              "fullTranscriptAndOnScreenText": "Reconstructed spoken script / voiceover beat-by-beat + key on-screen text callouts.",
              "hookBreakdown": "Detailed analysis of the 0-3 second Hook: visual disruption, curiosity gap, and spoken opening line.",
              "bodyStructureBreakdown": "How the middle 4-25 seconds maintain retention: pacing, value delivery sequence, and pattern interrupts.",
              "ctaBreakdown": "How the creator closes the video (comment trigger, save prompt, loop transition) and why it works.",
              "editingAndVisualNotes": "Breakdown of editing style: cut frequency, zoom/keyframes, B-roll overlays, typography/captions, and sound design.",
              "alternativeHooks": [
                "Hook Option 1 (Curiosity / Contrarian angle)",
                "Hook Option 2 (Direct Value / Tutorial angle)",
                "Hook Option 3 (Story / Case-Study angle)"
              ],
              "recreateTeleprompterScript": "A complete, ready-to-record 35-45 second script inspired by this Reel's core lesson, rewritten freshly in an authentic, high-retention educational voice with [VISUAL CUE] markers.",
              "shotListAndEditChecklist": [
                "Shot 1 (0-3s): Talking head + bold text overlay...",
                "Shot 2 (3-12s): Screen recording / B-roll demonstration...",
                "Shot 3 (12-25s): Step-by-step breakdown with sound effects...",
                "Edit Check: Add auto-captions, 1.1x pacing, and loop ending."
              ]
            }
        """.trimIndent()

        val dto = runCatching {
            val responseText = callGeminiJson(apiKey, model, prompt)
            json.decodeFromString<Phase2DeepAnalysisDto>(responseText)
        }.getOrElse {
            return@withContext generateSmartOfflineAnalysis(pin, creatorNiche)
        }

        VideoAnalysis(
            pinId = pin.id,
            executiveSummary = dto.executiveSummary,
            actionableTakeawaysJson = json.encodeToString(dto.actionableTakeaways),
            toolsAndResourcesJson = json.encodeToString(dto.toolsAndResources),
            fullTranscript = dto.fullTranscriptAndOnScreenText,
            hookBreakdown = dto.hookBreakdown,
            bodyStructureBreakdown = dto.bodyStructureBreakdown,
            ctaBreakdown = dto.ctaBreakdown,
            editingAndVisualNotes = dto.editingAndVisualNotes,
            alternativeHooksJson = json.encodeToString(dto.alternativeHooks),
            recreateTeleprompterScript = dto.recreateTeleprompterScript,
            shotListAndEditChecklistJson = json.encodeToString(dto.shotListAndEditChecklist),
            modelUsed = model,
            analyzedAt = System.currentTimeMillis()
        )
    }

    private fun callGeminiJson(apiKey: String, model: String, prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini API HTTP ${response.code}")
            }
            val bodyStr = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["candidates"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: throw IllegalStateException("Empty Gemini response")
            return text.trim().removeSurrounding("```json", "```").trim()
        }
    }

    /**
     * Fast local deterministic classifier used immediately when saving or when offline/no API key is set.
     */
    fun heuristicClassify(
        authorHandle: String,
        caption: String,
        availableBoards: List<String>,
        availableMoods: List<String>
    ): Phase1ClassificationResult {
        val combined = "$authorHandle $caption".lowercase()

        val (domain, subject, boardKeyword) = when {
            combined.containsAny(
                "edit", "capcut", "premiere", "after effects", "davinci", "transition",
                "color grad", "b-roll", "keyframe", "sfx", "cinematic", "hook", "retention", "reel"
            ) -> Triple("Video Editing", "Short-Form Editing & Visual Pacing", "Video Editing")

            combined.containsAny(
                "trad", "forex", "crypto", "nifty", "banknifty", "stock", "options",
                "scalp", "chart", "price action", "candle", "liquidity", "ict", "market", "invest"
            ) -> Triple("Trading", "Market Structure & Trading Setup", "Trading")

            combined.containsAny(
                "health", "gym", "workout", "protein", "diet", "sleep", "fitness",
                "muscle", "fat loss", "mobility", "posture", "supplement", "longevity", "nutrition"
            ) -> Triple("Health & Fitness", "Health Protocol & Training Guide", "Health")

            combined.containsAny(
                "job", "career", "resume", "interview", "hiring", "linkedin", "remote work",
                "salary", "freelanc", "internship", "recruiter", "portfolio"
            ) -> Triple("Jobs & Career", "Career Growth & Job Strategy", "Jobs")

            combined.containsAny(
                "ai", "chatgpt", "gemini", "claude", "automation", "coding", "python",
                "nocode", "saas", "prompt", "workflow", "tool"
            ) -> Triple("AI & Tech Tools", "AI Workflow & Productivity Tool", "AI")

            else -> Triple("General Education", "Creator Educational Insight", availableBoards.firstOrNull() ?: "Video Editing")
        }

        val matchedBoard = availableBoards.firstOrNull {
            it.contains(boardKeyword, ignoreCase = true)
        } ?: availableBoards.firstOrNull() ?: domain

        val matchedMood = availableMoods.firstOrNull {
            it.contains("Recreate", ignoreCase = true) || it.contains("Actionable", ignoreCase = true)
        } ?: "Actionable Tip"

        return Phase1ClassificationResult(
            domainCategory = domain,
            subjectTopic = subject,
            suggestedBoards = listOf(matchedBoard),
            suggestedMoods = listOf(matchedMood),
            oneLineSummary = caption.lines().firstOrNull { it.isNotBlank() }?.take(110)
                ?: "$domain reel by @$authorHandle"
        )
    }

    private fun generateSmartOfflineAnalysis(pin: VideoPin, creatorNiche: String): VideoAnalysis {
        val cleanCaption = pin.caption.ifBlank { "Educational Reel in ${pin.domainCategory} by @${pin.authorHandle}" }
        val takeaways = listOf(
            "Core Topic: ${pin.subjectTopic.ifBlank { pin.domainCategory }} shared by @${pin.authorHandle}.",
            "Analyze the opening 3 seconds of @${pin.authorHandle}'s Reel to see how the visual + headline stops the scroll.",
            "Extract the 3-step framework from caption: ${cleanCaption.take(140)}"
        )
        val tools = listOf(
            "Instagram Reels (@${pin.authorHandle})",
            "Domain: ${pin.domainCategory}",
            "Add your Gemini API Key in Settings for full live AI transcript & multimodal analysis"
        )
        val altHooks = listOf(
            "Most people get ${pin.domainCategory.lowercase()} completely wrong—here is the 30-second fix.",
            "Stop scrolling if you want to master ${pin.subjectTopic.ifBlank { pin.domainCategory }} without wasting hours.",
            "I tested @${pin.authorHandle}'s ${pin.domainCategory.lowercase()} framework—here are the exact 3 steps that actually work."
        )
        val checklist = listOf(
            "Shot 1 (0–3s): Direct-to-camera hook with high-contrast headline text on screen.",
            "Shot 2 (3–15s): Over-the-shoulder / screen-recording proof showing the exact problem.",
            "Shot 3 (15–30s): 3 rapid-fire actionable steps with punch-in zoom cuts every 2.5 seconds.",
            "Shot 4 (30–38s): Save-worthy summary graphic + 'Save this to your ${pin.domainCategory} board' CTA."
        )

        return VideoAnalysis(
            pinId = pin.id,
            executiveSummary = "Structured breakdown of @${pin.authorHandle}'s ${pin.domainCategory} Reel (${pin.subjectTopic.ifBlank { "Core Tutorial" }}). Configure your Gemini API key in Settings anytime to run live cloud LLM enrichment.",
            actionableTakeawaysJson = json.encodeToString(takeaways),
            toolsAndResourcesJson = json.encodeToString(tools),
            fullTranscript = "Caption / On-Screen Context:\n$cleanCaption",
            hookBreakdown = "0–3s Scroll Stopper: Uses immediate visual contrast and a bold promise around ${pin.subjectTopic.ifBlank { pin.domainCategory }} to lock viewer curiosity.",
            bodyStructureBreakdown = "3–25s Value Delivery: Breaks the concept into digestible micro-steps with visual pattern interrupts every 3 seconds.",
            ctaBreakdown = "25–35s Close: Prompts viewers to save the video for reference because the steps require repeat viewing.",
            editingAndVisualNotes = "Fast jump-cuts (zero dead air), high-contrast center-screen captions, subtle whoosh/riser SFX on transitions.",
            alternativeHooksJson = json.encodeToString(altHooks),
            recreateTeleprompterScript = buildString {
                appendLine("[0:00 - HOOK | Bold Text on Screen]")
                appendLine("\"${altHooks.first()}\"")
                appendLine()
                appendLine("[0:04 - THE PROBLEM | Quick B-Roll / Screen Demo]")
                appendLine("\"Whenever you're working on ${pin.subjectTopic.ifBlank { pin.domainCategory }}, the biggest bottleneck is doing it the manual way.\"")
                appendLine()
                appendLine("[0:12 - 3 ACTIONABLE STEPS | Punch-in Zooms]")
                appendLine("\"First: set up your baseline structure. Second: apply the core technique from this framework. Third: review and lock in the result.\"")
                appendLine()
                appendLine("[0:30 - CTA | Seamless Loop]")
                appendLine("\"Save this video so you have the checklist ready next time you need it.\"")
            },
            shotListAndEditChecklistJson = json.encodeToString(checklist),
            modelUsed = "instapin-local-template (Add Gemini API key for live AI)",
            analyzedAt = System.currentTimeMillis()
        )
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it, ignoreCase = true) }
}
