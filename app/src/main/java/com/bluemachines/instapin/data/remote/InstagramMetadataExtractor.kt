package com.bluemachines.instapin.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ExtractedInstagramContext(
    val normalizedUrl: String,
    val shortcode: String,
    val authorHandle: String = "",
    val authorDisplayName: String = "",
    val caption: String = "",
    val audioTrackName: String = "",
    val thumbnailUrl: String = "",
    val hashtags: List<String> = emptyList(),
    val isReachable: Boolean = true
)

/**
 * Extracts and normalizes Instagram Reel/Post links and public OpenGraph metadata
 * WITHOUT downloading video files or modifying anything in Instagram.
 */
class InstagramMetadataExtractor(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private val IG_URL_REGEX = Regex(
            """https?://(?:www\.)?instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val IG_SHARE_URL_REGEX = Regex(
            """https?://(?:www\.)?instagram\.com/share/(?:reel|p)?/?([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val HASHTAG_REGEX = Regex("""#([A-Za-z0-9_]+)""")
        private val OG_TITLE_AUTHOR_REGEX = Regex(
            """([^\n"|•]+?)\s+on\s+Instagram:\s*"?(.*?)"?$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val OG_DESC_HANDLE_REGEX = Regex("""\(@([A-Za-z0-9._]+)\)""")

        private val ALLOWED_INSTAGRAM_HOSTS = setOf(
            "instagram.com",
            "www.instagram.com",
            "m.instagram.com",
            "instagr.am"
        )

        fun isTrustedInstagramUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return runCatching {
                val parsed = url.toHttpUrlOrNull() ?: return false
                parsed.isHttps && parsed.host.lowercase() in ALLOWED_INSTAGRAM_HOSTS
            }.getOrDefault(false)
        }

        fun extractFirstInstagramUrl(rawText: String?): String? {
            if (rawText.isNullOrBlank()) return null
            IG_URL_REGEX.find(rawText)?.let { match ->
                val shortcode = match.groupValues[1]
                return "https://www.instagram.com/reel/$shortcode/"
            }
            IG_SHARE_URL_REGEX.find(rawText)?.let { match ->
                return match.value
            }
            return null
        }

        fun extractShortcode(urlOrText: String): String {
            IG_URL_REGEX.find(urlOrText)?.let { return it.groupValues[1] }
            IG_SHARE_URL_REGEX.find(urlOrText)?.let { return "share_${it.groupValues[1]}" }
            return "local_${urlOrText.hashCode().toUInt().toString(16)}"
        }

        fun normalizeInstagramUrl(rawUrl: String?, fallbackAuthor: String, fallbackCaption: String): Pair<String, String> {
            val found = extractFirstInstagramUrl(rawUrl)
            if (found != null) {
                val shortcode = extractShortcode(found)
                val canonical = if (shortcode.startsWith("share_") || shortcode.startsWith("local_")) {
                    found
                } else {
                    "https://www.instagram.com/reel/$shortcode/"
                }
                return canonical to shortcode
            }
            // If user triggered radial save directly from screen before copying link, create a deterministic pointer
            // that opens the creator profile or search in Instagram and can be updated if link is copied later.
            val cleanHandle = fallbackAuthor.removePrefix("@").trim().ifEmpty { "instagram_reel" }
            val syntheticKey = "${cleanHandle}_${fallbackCaption.take(48)}_${System.currentTimeMillis() / 60000}"
            val shortcode = "screen_${syntheticKey.hashCode().toUInt().toString(16)}"
            val url = if (cleanHandle != "instagram_reel" && cleanHandle != "unknown_creator") {
                "https://www.instagram.com/$cleanHandle/"
            } else {
                "https://www.instagram.com/reels/"
            }
            return url to shortcode
        }

        fun extractHashtags(text: String): List<String> {
            return HASHTAG_REGEX.findAll(text)
                .map { it.groupValues[1].lowercase() }
                .distinct()
                .toList()
        }
    }

    /**
     * Enriches screen-captured context with public OpenGraph tags (`og:title`, `og:description`, `og:image`)
     * from the public Instagram Reel page without downloading any video bytes.
     */
    suspend fun enrichMetadata(
        rawUrl: String?,
        screenAuthorHandle: String = "",
        screenCaption: String = "",
        screenAudioTrack: String = ""
    ): ExtractedInstagramContext = withContext(Dispatchers.IO) {
        val (initialUrl, initialShortcode) = normalizeInstagramUrl(rawUrl, screenAuthorHandle, screenCaption)
        var finalUrl = initialUrl
        var finalShortcode = initialShortcode
        var authorHandle = screenAuthorHandle.removePrefix("@").trim()
        var authorDisplayName = ""
        var caption = screenCaption.trim()
        var thumbnailUrl = ""
        var isReachable = true

        if (isTrustedInstagramUrl(initialUrl) && (
                initialUrl.contains("instagram.com/reel/") ||
                initialUrl.contains("instagram.com/p/") ||
                initialUrl.contains("instagram.com/share/")
            )
        ) {
            runCatching {
                val request = Request.Builder()
                    .url(initialUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Moto G84 5G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val resolvedUrl = response.request.url.toString()
                    extractFirstInstagramUrl(resolvedUrl)?.let { canonical ->
                        finalUrl = canonical
                        finalShortcode = extractShortcode(canonical)
                    }
                    if (response.code == 404) {
                        isReachable = false
                    }
                    val html = response.body?.string().orEmpty()
                    val ogTitle = extractMetaTag(html, "og:title")
                    val ogDescription = extractMetaTag(html, "og:description")
                    val ogImage = extractMetaTag(html, "og:image")

                    if (ogImage.isNotBlank()) {
                        thumbnailUrl = decodeHtmlEntities(ogImage)
                    }

                    if (ogDescription.isNotBlank()) {
                        OG_DESC_HANDLE_REGEX.find(ogDescription)?.let { match ->
                            if (authorHandle.isBlank() || authorHandle == "unknown_creator") {
                                authorHandle = match.groupValues[1]
                            }
                        }
                    }

                    if (ogTitle.isNotBlank()) {
                        val decodedTitle = decodeHtmlEntities(ogTitle)
                        OG_TITLE_AUTHOR_REGEX.find(decodedTitle)?.let { match ->
                            val rawAuthor = match.groupValues[1].trim()
                            val extractedCaption = match.groupValues[2].trim().removeSuffix("\"")
                            if (authorDisplayName.isBlank()) {
                                authorDisplayName = rawAuthor
                            }
                            if (authorHandle.isBlank() || authorHandle == "unknown_creator") {
                                authorHandle = rawAuthor.lowercase().replace(" ", "_")
                            }
                            if (caption.isBlank() && extractedCaption.isNotBlank()) {
                                caption = extractedCaption
                            }
                        }
                    }
                }
            }
        }

        val combinedHashtags = extractHashtags(caption)
        ExtractedInstagramContext(
            normalizedUrl = finalUrl,
            shortcode = finalShortcode,
            authorHandle = authorHandle.ifBlank { "unknown_creator" },
            authorDisplayName = authorDisplayName.ifBlank { authorHandle },
            caption = caption,
            audioTrackName = screenAudioTrack,
            thumbnailUrl = thumbnailUrl,
            hashtags = combinedHashtags,
            isReachable = isReachable
        )
    }

    private fun extractMetaTag(html: String, property: String): String {
        val pattern = Regex(
            """<meta[^>]+property=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        pattern.find(html)?.let { return it.groupValues[1] }

        val reversePattern = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$property["']""",
            RegexOption.IGNORE_CASE
        )
        return reversePattern.find(html)?.groupValues?.get(1).orEmpty()
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
