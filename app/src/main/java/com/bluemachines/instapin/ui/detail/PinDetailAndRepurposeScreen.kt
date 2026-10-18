package com.bluemachines.instapin.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.BoardSection
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.ui.overlay.parseHexColor
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PinDetailAndRepurposeScreen(
    populatedPin: PopulatedVideoPin,
    allBoards: List<Board>,
    allSections: List<BoardSection>,
    onBack: () -> Unit,
    onTriggerPhase2Analysis: (Long) -> Unit,
    onMovePin: (pinId: Long, sourceBoardId: Long?, targetBoardId: Long, targetSectionId: Long?) -> Unit,
    onCopyPinToBoard: (pinId: Long, targetBoardId: Long, targetSectionId: Long?) -> Unit,
    onAssignPinToSection: (pinId: Long, boardId: Long, sectionId: Long?) -> Unit,
    onAttachClipboardLink: (String) -> Unit = {},
    onDeletePin: () -> Unit
) {
    val context = LocalContext.current
    val pin = populatedPin.pin
    val analysis = populatedPin.analysis
    var selectedTab by remember { mutableIntStateOf(0) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showSectionDialog by remember { mutableStateOf(false) }

    val json = remember { Json { ignoreUnknownKeys = true } }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    val takeaways = remember(analysis?.actionableTakeawaysJson) {
        runCatching {
            json.decodeFromString<List<String>>(analysis?.actionableTakeawaysJson ?: "[]")
        }.getOrDefault(emptyList())
    }
    val tools = remember(analysis?.toolsAndResourcesJson) {
        runCatching {
            json.decodeFromString<List<String>>(analysis?.toolsAndResourcesJson ?: "[]")
        }.getOrDefault(emptyList())
    }
    val altHooks = remember(analysis?.alternativeHooksJson) {
        runCatching {
            json.decodeFromString<List<String>>(analysis?.alternativeHooksJson ?: "[]")
        }.getOrDefault(emptyList())
    }
    val shotChecklist = remember(analysis?.shotListAndEditChecklistJson) {
        runCatching {
            json.decodeFromString<List<String>>(analysis?.shotListAndEditChecklistJson ?: "[]")
        }.getOrDefault(emptyList())
    }

    Scaffold(
        containerColor = Color(0xFF0E0E16),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "@${pin.authorHandle}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "${pin.domainCategory} • ${dateFormat.format(Date(pin.savedAt))}",
                            color = Color(0xFFA0A0B8),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            openInstagramPermalink(context, pin.instagramUrl)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in Instagram",
                            tint = Color(0xFFE60023)
                        )
                    }
                    IconButton(onClick = onDeletePin) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF14141F))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Top Metadata Card (Link Instance Info)
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFFE60023).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = pin.domainCategory,
                                color = Color(0xFFFF6B81),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Button(
                            onClick = { openInstagramPermalink(context, pin.instagramUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Watch on Instagram", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showMoveDialog = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("⇄ Move", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showCopyDialog = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("⧉ Add to Board", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showSectionDialog = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("📂 Section", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (pin.subjectTopic.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = pin.subjectTopic,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pin.caption.ifBlank { "No caption text captured" },
                        color = Color(0xFFD0D0E4),
                        fontSize = 13.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    val hasExactReelLink = pin.instagramUrl.contains("/reel/", ignoreCase = true) ||
                        pin.instagramUrl.contains("/p/", ignoreCase = true) ||
                        pin.instagramUrl.contains("/share/", ignoreCase = true)

                    Surface(
                        color = if (hasExactReelLink) {
                            Color(0xFF10B981).copy(alpha = 0.14f)
                        } else {
                            Color(0xFFF59E0B).copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = if (hasExactReelLink) {
                                    "🔗 Exact Video Link: ${pin.instagramUrl}"
                                } else {
                                    "⚠️ Channel-only link (${pin.instagramUrl}). Copy the Reel link in Instagram and tap below to attach the exact video:"
                                },
                                color = if (hasExactReelLink) Color(0xFF10B981) else Color(0xFFF59E0B),
                                fontSize = 11.sp
                            )
                            if (!hasExactReelLink) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (clipText.contains("instagram.com/", ignoreCase = true)) {
                                            onAttachClipboardLink(clipText)
                                            Toast.makeText(context, "Attached exact Reel link from Clipboard!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Copy an Instagram Reel link first, then tap this button!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Paste Reel Video Link from Clipboard", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Boards & Moods badges
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        populatedPin.boards.forEach { b ->
                            Surface(
                                color = parseHexColor(b.colorHex).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${b.emoji} ${b.name}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        populatedPin.moods.forEach { m ->
                            Surface(
                                color = parseHexColor(m.colorHex).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${m.emoji} ${m.name}",
                                    color = Color(0xFFE0E0FF),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Phase 2 Header & Trigger Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phase 2: AI Repurpose & Video Studio",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (analysis != null) {
                            "Analyzed with ${analysis.modelUsed}"
                        } else {
                            "Extract summary, hook, transcript & recreate script"
                        },
                        color = Color(0xFFA0A0B8),
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.width(10.dp))

                Button(
                    onClick = { onTriggerPhase2Analysis(pin.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (analysis == null) "Analyze Video" else "Re-Analyze",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (analysis == null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tap 'Analyze Video' above to run Phase 2 Deep Breakdown",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Generates Executive Summary, Actionable Steps, Spoken/Visual Transcript, 0-3s Hook & Structure Breakdown, and a Ready-to-Record Recreate Script + Shot List.",
                            color = Color(0xFFA0A0B8),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val tabs = listOf(
                    "1. Summary & Steps",
                    "2. Transcript & Hook",
                    "3. Editing Style",
                    "4. Recreate Script"
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF14141F),
                    contentColor = Color.White,
                    edgePadding = 0.dp
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        AnalysisSectionCard(
                            title = "Executive Summary",
                            body = analysis.executiveSummary
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisListCard(
                            title = "Actionable Takeaways",
                            items = takeaways
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisListCard(
                            title = "Tools, Indicators & Resources Mentioned",
                            items = tools
                        )
                    }

                    1 -> {
                        AnalysisSectionCard(
                            title = "0–3s Scroll-Stopper Hook Breakdown",
                            body = analysis.hookBreakdown
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisSectionCard(
                            title = "Body Pacing & Retention Structure",
                            body = analysis.bodyStructureBreakdown
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisSectionCard(
                            title = "Call-To-Action (CTA) Strategy",
                            body = analysis.ctaBreakdown
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisSectionCard(
                            title = "Transcript & On-Screen Text",
                            body = analysis.fullTranscript
                        )
                    }

                    2 -> {
                        AnalysisSectionCard(
                            title = "Editing, Visual Pacing & Audio Breakdown",
                            body = analysis.editingAndVisualNotes
                        )
                    }

                    3 -> {
                        AnalysisListCard(
                            title = "3 Fresh Hooks for Your Recreated Video",
                            items = altHooks
                        )
                        Spacer(Modifier.height(10.dp))
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Ready-to-Record Teleprompter Script",
                                        color = Color(0xFFA78BFA),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(
                                                ClipData.newPlainText("Recreate Script", analysis.recreateTeleprompterScript)
                                            )
                                            Toast.makeText(context, "Script copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy Script", fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = analysis.recreateTeleprompterScript,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        AnalysisListCard(
                            title = "Shot-by-Shot Filming & Editing Checklist",
                            items = shotChecklist
                        )
                    }
                }
            }
        }
    }
    if (showMoveDialog) {
        var sourceBoardId by remember(pin.id) { mutableStateOf(populatedPin.boards.firstOrNull()?.id) }
        var targetBoardId by remember(pin.id) { mutableStateOf<Long?>(null) }
        var targetSectionId by remember(pin.id) { mutableStateOf<Long?>(null) }
        val targetSections = allSections.filter { it.boardId == targetBoardId }

        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move Pin") },
            text = {
                Column {
                    Text("From board", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        populatedPin.boards.forEach { board ->
                            FilterChip(
                                selected = sourceBoardId == board.id,
                                onClick = { sourceBoardId = board.id },
                                label = { Text("${board.emoji} ${board.name}", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("To board", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allBoards.forEach { board ->
                            FilterChip(
                                selected = targetBoardId == board.id,
                                onClick = {
                                    targetBoardId = board.id
                                    targetSectionId = null
                                },
                                label = { Text("${board.emoji} ${board.name}", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = parseHexColor(board.colorHex).copy(alpha = 0.75f)
                                )
                            )
                        }
                    }
                    if (targetSections.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Section (optional)", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = targetSectionId == null,
                                onClick = { targetSectionId = null },
                                label = { Text("No section", fontSize = 11.sp) }
                            )
                            targetSections.forEach { section ->
                                FilterChip(
                                    selected = targetSectionId == section.id,
                                    onClick = { targetSectionId = section.id },
                                    label = { Text("${section.emoji} ${section.name}", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = targetBoardId != null,
                    onClick = {
                        targetBoardId?.let { targetId ->
                            onMovePin(pin.id, sourceBoardId, targetId, targetSectionId)
                            Toast.makeText(context, "Pin moved", Toast.LENGTH_SHORT).show()
                            showMoveDialog = false
                        }
                    }
                ) {
                    Text("Move")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCopyDialog) {
        var targetBoardId by remember(pin.id) { mutableStateOf<Long?>(null) }
        var targetSectionId by remember(pin.id) { mutableStateOf<Long?>(null) }
        val targetSections = allSections.filter { it.boardId == targetBoardId }

        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("Add Pin to Another Board") },
            text = {
                Column {
                    Text(
                        "The Pin remains in its current board(s).",
                        color = Color(0xFFA0A0B8),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Add to", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allBoards.forEach { board ->
                            FilterChip(
                                selected = targetBoardId == board.id,
                                onClick = {
                                    targetBoardId = board.id
                                    targetSectionId = null
                                },
                                label = { Text("${board.emoji} ${board.name}", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = parseHexColor(board.colorHex).copy(alpha = 0.75f)
                                )
                            )
                        }
                    }
                    if (targetSections.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Section (optional)", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = targetSectionId == null,
                                onClick = { targetSectionId = null },
                                label = { Text("No section", fontSize = 11.sp) }
                            )
                            targetSections.forEach { section ->
                                FilterChip(
                                    selected = targetSectionId == section.id,
                                    onClick = { targetSectionId = section.id },
                                    label = { Text("${section.emoji} ${section.name}", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = targetBoardId != null,
                    onClick = {
                        targetBoardId?.let { targetId ->
                            onCopyPinToBoard(pin.id, targetId, targetSectionId)
                            Toast.makeText(context, "Added to board", Toast.LENGTH_SHORT).show()
                            showCopyDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSectionDialog) {
        var selectedBoardId by remember(pin.id) { mutableStateOf(populatedPin.boards.firstOrNull()?.id) }
        var selectedSectionId by remember(pin.id) { mutableStateOf<Long?>(null) }
        val boardSections = allSections.filter { it.boardId == selectedBoardId }

        AlertDialog(
            onDismissRequest = { showSectionDialog = false },
            title = { Text("Assign to Section") },
            text = {
                Column {
                    Text("Board", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        populatedPin.boards.forEach { board ->
                            FilterChip(
                                selected = selectedBoardId == board.id,
                                onClick = {
                                    selectedBoardId = board.id
                                    selectedSectionId = null
                                },
                                label = { Text("${board.emoji} ${board.name}", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Section", color = Color(0xFFA0A0B8), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedSectionId == null,
                            onClick = { selectedSectionId = null },
                            label = { Text("No section", fontSize = 11.sp) }
                        )
                        boardSections.forEach { section ->
                            FilterChip(
                                selected = selectedSectionId == section.id,
                                onClick = { selectedSectionId = section.id },
                                label = { Text("${section.emoji} ${section.name}", fontSize = 11.sp) }
                            )
                        }
                    }
                    if (boardSections.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Create sections from the selected Board's screen.",
                            color = Color(0xFFA0A0B8),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedBoardId != null,
                    onClick = {
                        selectedBoardId?.let { boardId ->
                            onAssignPinToSection(pin.id, boardId, selectedSectionId)
                            Toast.makeText(context, "Section updated", Toast.LENGTH_SHORT).show()
                            showSectionDialog = false
                        }
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSectionDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AnalysisSectionCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color(0xFFA78BFA),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body.ifBlank { "—" },
                color = Color(0xFFE5E5F5),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }

}

@Composable
private fun AnalysisListCard(title: String, items: List<String>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color(0xFFA78BFA),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text("None listed", color = Color(0xFFA0A0B8), fontSize = 13.sp)
            } else {
                items.forEachIndexed { idx, point ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8B5CF6).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${idx + 1}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = point,
                            color = Color(0xFFE5E5F5),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

fun openInstagramPermalink(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }
    }
}
