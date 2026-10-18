package com.bluemachines.instapin

import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.VideoPin
import com.bluemachines.instapin.data.remote.GeminiAiService
import com.bluemachines.instapin.data.remote.InstagramExportJsonParser
import com.bluemachines.instapin.data.remote.InstagramMetadataExtractor
import com.bluemachines.instapin.data.repository.BackupBoardSection
import com.bluemachines.instapin.data.repository.BackupPinItem
import com.bluemachines.instapin.data.repository.FullDatabaseSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstaPinCorePipelineTest {

    @Test
    fun `extracts and normalizes Instagram Reel URLs accurately`() {
        val rawShareText = "Check out this editing tutorial https://www.instagram.com/reel/C9AbCdEf123/?igsh=MzRlODBiNWFlZA=="
        val normalized = InstagramMetadataExtractor.extractFirstInstagramUrl(rawShareText)
        assertEquals("https://www.instagram.com/reel/C9AbCdEf123/", normalized)
        assertEquals("C9AbCdEf123", InstagramMetadataExtractor.extractShortcode(rawShareText))
    }

    @Test
    fun `extracts hashtags from Reel caption`() {
        val caption = "Master speed ramps in CapCut #VideoEditing #CapCut #ReelsTutorial"
        val tags = InstagramMetadataExtractor.extractHashtags(caption)
        assertEquals(listOf("videoediting", "capcut", "reelstutorial"), tags)
    }

    @Test
    fun `Phase 1 heuristic classifier maps Video Editing, Trading, Health, and Jobs accurately`() {
        val ai = GeminiAiService()
        val boards = listOf("Video Editing", "Trading", "Health & Fitness", "Jobs & Career", "AI & Tech Tools")
        val moods = listOf("Recreate Soon", "Actionable Tip")

        val editResult = ai.heuristicClassify(
            authorHandle = "capcut.pro",
            caption = "How to keyframe smooth speed ramp transitions and color grade in Premiere",
            availableBoards = boards,
            availableMoods = moods
        )
        assertEquals("Video Editing", editResult.domainCategory)
        assertTrue(editResult.suggestedBoards.contains("Video Editing"))

        val tradeResult = ai.heuristicClassify(
            authorHandle = "nifty.scalper",
            caption = "5m price action liquidity sweep setup on BankNifty options",
            availableBoards = boards,
            availableMoods = moods
        )
        assertEquals("Trading", tradeResult.domainCategory)
        assertTrue(tradeResult.suggestedBoards.contains("Trading"))

        val healthResult = ai.heuristicClassify(
            authorHandle = "dr.fit",
            caption = "High protein breakfast and mobility workout for fat loss",
            availableBoards = boards,
            availableMoods = moods
        )
        assertEquals("Health & Fitness", healthResult.domainCategory)

        val jobResult = ai.heuristicClassify(
            authorHandle = "career.coach",
            caption = "Resume and LinkedIn interview tips for remote software jobs",
            availableBoards = boards,
            availableMoods = moods
        )
        assertEquals("Jobs & Career", jobResult.domainCategory)
    }

    @Test
    fun `Phase 3 parser extracts historical liked_posts and saved_posts JSON entries`() {
        val sampleJson = """
            {
              "likes_media_likes": [
                {
                  "title": "creator.one",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/reel/AbCdEf001/",
                      "value": "CapCut editing breakdown",
                      "timestamp": 1728000000
                    }
                  ]
                },
                {
                  "title": "trader.two",
                  "string_list_data": [
                    {
                      "href": "https://www.instagram.com/p/XyZ987654/",
                      "value": "Price action chart",
                      "timestamp": 1728500000
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = InstagramExportJsonParser.parseExportJson(sampleJson)
        assertEquals(2, parsed.size)
        // Sorted newest first
        assertEquals("XyZ987654", parsed[0].shortcode)
        assertEquals("trader.two", parsed[0].authorHandle)
        assertEquals("AbCdEf001", parsed[1].shortcode)
        assertEquals("creator.one", parsed[1].authorHandle)
        assertNotNull(parsed[0].instagramUrl)
    }

    @Test
    fun `Domain allowlist blocks SSRF and non-Instagram URLs while allowing valid Instagram HTTPS links`() {
        assertTrue(InstagramMetadataExtractor.isTrustedInstagramUrl("https://www.instagram.com/reel/C9AbCdEf123/"))
        assertTrue(InstagramMetadataExtractor.isTrustedInstagramUrl("https://instagram.com/p/XyZ987/"))
        org.junit.Assert.assertFalse(InstagramMetadataExtractor.isTrustedInstagramUrl("http://www.instagram.com/reel/C9AbCdEf123/"))
        org.junit.Assert.assertFalse(InstagramMetadataExtractor.isTrustedInstagramUrl("https://evil.example.com/instagram.com/reel/123"))
        org.junit.Assert.assertFalse(InstagramMetadataExtractor.isTrustedInstagramUrl("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `backup snapshot preserves board sections and Pin section assignments`() {
        val snapshot = FullDatabaseSnapshot(
            boards = listOf(Board(id = 1, name = "Video Editing")),
            sections = listOf(
                BackupBoardSection(
                    boardName = "Video Editing",
                    name = "CapCut",
                    emoji = "📂",
                    sortOrder = 2
                )
            ),
            pins = listOf(
                BackupPinItem(
                    pin = VideoPin(
                        id = 7,
                        instagramUrl = "https://www.instagram.com/reel/BackupTest/",
                        shortcode = "BackupTest"
                    ),
                    boardNames = listOf("Video Editing"),
                    sectionNameByBoard = mapOf("Video Editing" to "CapCut")
                )
            )
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val restored = json.decodeFromString<FullDatabaseSnapshot>(json.encodeToString(snapshot))

        assertEquals("CapCut", restored.sections.single().name)
        assertEquals(2, restored.sections.single().sortOrder)
        assertEquals("CapCut", restored.pins.single().sectionNameByBoard["Video Editing"])
    }

    @Test
    fun `older backup without sections remains restorable`() {
        val oldBackup = """
            {
              "boards": [],
              "moods": [],
              "pins": []
            }
        """.trimIndent()
        val restored = Json { ignoreUnknownKeys = true }.decodeFromString<FullDatabaseSnapshot>(oldBackup)

        assertTrue(restored.sections.isEmpty())
        assertTrue(restored.pins.isEmpty())
    }
}
