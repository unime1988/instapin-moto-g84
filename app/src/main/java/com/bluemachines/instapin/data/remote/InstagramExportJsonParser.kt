package com.bluemachines.instapin.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class HistoricalInstagramItem(
    val instagramUrl: String,
    val shortcode: String,
    val authorHandle: String,
    val titleOrCaption: String,
    val timestampMillis: Long
)

/**
 * Phase 3: Parses Instagram's official "Download Your Information" JSON files:
 * - `liked_posts.json` (`likes_media_likes`)
 * - `saved_posts.json` (`saved_saved_media`)
 * - Or any JSON/text export containing Instagram Reel/Post links.
 *
 * Strictly read-only: creates local instances pointing to the original Instagram URLs without modifying Instagram.
 */
object InstagramExportJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseExportJson(rawJsonContent: String): List<HistoricalInstagramItem> {
        val discovered = LinkedHashMap<String, HistoricalInstagramItem>()

        runCatching {
            val rootElement = json.parseToJsonElement(rawJsonContent)
            traverseJsonElement(rootElement, currentTitle = "", discovered = discovered)
        }.onFailure {
            // Fallback: regex scan if user selected a raw HTML/TXT export instead of JSON
            val urlRegex = Regex("""https?://(?:www\.)?instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            urlRegex.findAll(rawJsonContent).forEach { match ->
                val shortcode = match.groupValues[1]
                val canonicalUrl = "https://www.instagram.com/reel/$shortcode/"
                discovered.putIfAbsent(
                    shortcode,
                    HistoricalInstagramItem(
                        instagramUrl = canonicalUrl,
                        shortcode = shortcode,
                        authorHandle = "unknown_creator",
                        titleOrCaption = "",
                        timestampMillis = System.currentTimeMillis()
                    )
                )
            }
        }

        return discovered.values.sortedByDescending { it.timestampMillis }
    }

    private fun traverseJsonElement(
        element: JsonElement,
        currentTitle: String,
        discovered: MutableMap<String, HistoricalInstagramItem>
    ) {
        when (element) {
            is JsonObject -> {
                val itemTitle = element["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                val effectiveTitle = if (itemTitle.isNotBlank()) itemTitle else currentTitle

                // Check if this object directly has `href` + `timestamp` (Instagram string_list_data entry)
                val href = element["href"]?.jsonPrimitive?.content
                val timestampSec = element["timestamp"]?.jsonPrimitive?.longOrNull
                val valueStr = element["value"]?.jsonPrimitive?.content.orEmpty()

                if (!href.isNullOrBlank()) {
                    val normalizedUrl = InstagramMetadataExtractor.extractFirstInstagramUrl(href)
                    if (normalizedUrl != null) {
                        val shortcode = InstagramMetadataExtractor.extractShortcode(normalizedUrl)
                        val author = effectiveTitle.removePrefix("@").trim().ifBlank { "unknown_creator" }
                        val timestampMs = if (timestampSec != null && timestampSec > 0) {
                            if (timestampSec < 10_000_000_000L) timestampSec * 1000L else timestampSec
                        } else {
                            System.currentTimeMillis()
                        }
                        discovered[shortcode] = HistoricalInstagramItem(
                            instagramUrl = normalizedUrl,
                            shortcode = shortcode,
                            authorHandle = author,
                            titleOrCaption = valueStr,
                            timestampMillis = timestampMs
                        )
                    }
                }

                // Also check Instagram `saved_saved_media` format where `string_map_data.Saved on.href` is nested
                element.values.forEach { child ->
                    traverseJsonElement(child, effectiveTitle, discovered)
                }
            }

            is JsonArray -> {
                element.forEach { child ->
                    traverseJsonElement(child, currentTitle, discovered)
                }
            }

            else -> Unit
        }
    }
}
