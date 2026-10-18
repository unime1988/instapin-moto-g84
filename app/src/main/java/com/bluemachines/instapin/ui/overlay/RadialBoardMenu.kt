package com.bluemachines.instapin.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.entity.Board
import com.bluemachines.instapin.data.local.entity.MoodTag
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class RadialTargetItem(
    val board: Board?, // null means "AI Auto-Sort"
    val label: String,
    val emoji: String,
    val color: Color,
    val angleRadians: Double
)

fun parseHexColor(hex: String, fallback: Color = Color(0xFFE60023)): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}

/**
 * The Pinterest-style floating bubble + Hold-and-Slide Radial Fan overlay rendered over Instagram.
 * - Single Tap -> Opens Compact Quick Save Card.
 * - Press & Hold -> Expands circular arc of Boards around your thumb; slide & release to save in 1 second!
 * - Mini `✕` badge on the bubble -> Stops/hides the floating bubble anytime.
 */
@Composable
fun PinterestRadialOverlay(
    radialBoards: List<Board>,
    isRightSide: Boolean = true,
    onRadialExpandChange: (Boolean) -> Unit = {},
    onSingleTapOpenSheet: () -> Unit,
    onRadialBoardSelected: (Board?) -> Unit, // null = AI Auto-Categorize
    onStopHoverService: () -> Unit = {},
    onVerticalDragDelta: (Float) -> Unit = {}
) {
    var isRadialExpanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableIntStateOf(-1) }

    val density = LocalDensity.current
    val radiusPx = with(density) { 126.dp.toPx() }

    val targets = remember(radialBoards, isRightSide) {
        val items = mutableListOf<Pair<Board?, Triple<String, String, Color>>>()
        items.add(null to Triple("AI Auto-Sort", "✨", Color(0xFF8B5CF6)))
        radialBoards.take(5).forEach { b ->
            items.add(b to Triple(b.name, b.emoji, parseHexColor(b.colorHex)))
        }
        val count = items.size
        val startAngleDeg = if (isRightSide) 105.0 else -75.0
        val endAngleDeg = if (isRightSide) 255.0 else 75.0

        items.mapIndexed { idx, (board, meta) ->
            val fraction = if (count <= 1) 0.5 else idx.toDouble() / (count - 1).toDouble()
            val angleDeg = startAngleDeg + fraction * (endAngleDeg - startAngleDeg)
            val angleRad = angleDeg * (PI / 180.0)
            RadialTargetItem(
                board = board,
                label = meta.first,
                emoji = meta.second,
                color = meta.third,
                angleRadians = angleRad
            )
        }
    }

    val containerModifier = if (isRadialExpanded) {
        Modifier.fillMaxSize()
    } else {
        Modifier.size(width = 76.dp, height = 88.dp)
    }

    Box(
        modifier = containerModifier,
        contentAlignment = if (isRightSide) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // Dim backdrop while holding the radial menu
        AnimatedVisibility(
            visible = isRadialExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .clickable {
                        isRadialExpanded = false
                        onRadialExpandChange(false)
                    }
            )
        }

        // Hovered board banner at the top of the radial fan
        AnimatedVisibility(
            visible = isRadialExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
        ) {
            val activeTarget = targets.getOrNull(hoveredIndex)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = activeTarget?.color ?: Color(0xFF1E1E2E),
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeTarget?.emoji ?: "📌",
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (activeTarget != null) {
                            "Release to Pin to ${activeTarget.label}"
                        } else {
                            "Slide to a Board • Release to Pin"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // Anchor container on the screen edge
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Radial Target Nodes
            if (isRadialExpanded) {
                targets.forEachIndexed { index, item ->
                    val isHovered = hoveredIndex == index
                    val scale by animateFloatAsState(
                        targetValue = if (isHovered) 1.28f else 1.0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "radial_scale"
                    )
                    val offsetX = (cos(item.angleRadians) * radiusPx).roundToInt()
                    val offsetY = (sin(item.angleRadians) * radiusPx).roundToInt()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset { IntOffset(offsetX, offsetY) }
                            .scale(scale)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(if (isHovered) 60.dp else 52.dp)
                                .shadow(8.dp, CircleShape)
                                .clip(CircleShape)
                                .background(item.color)
                                .border(
                                    width = if (isHovered) 3.dp else 1.5.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                        ) {
                            Text(
                                text = item.emoji,
                                fontSize = if (isHovered) 24.sp else 20.sp
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Surface(
                            color = Color.Black.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = item.label,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Main Floating Pinterest Trigger Bubble + Small Top-Right Dismiss ('✕') Badge
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 6.dp, end = 4.dp)
                        .size(56.dp)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFE60023), Color(0xFFFF4B2B))
                            )
                        )
                        .border(2.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                        .pointerInput(targets) {
                            detectTapGestures(
                                onTap = {
                                    onSingleTapOpenSheet()
                                }
                            )
                        }
                        .pointerInput(targets) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isRadialExpanded = true
                                    onRadialExpandChange(true)
                                    dragOffset = Offset.Zero
                                    hoveredIndex = -1
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount
                                    val distance = hypot(dragOffset.x, dragOffset.y)
                                    if (distance > 36f) {
                                        hoveredIndex = targets.indices.minByOrNull { idx ->
                                            val item = targets[idx]
                                            val targetX = (cos(item.angleRadians) * radiusPx).toFloat()
                                            val targetY = (sin(item.angleRadians) * radiusPx).toFloat()
                                            hypot(dragOffset.x - targetX, dragOffset.y - targetY)
                                        } ?: -1
                                    } else {
                                        hoveredIndex = -1
                                    }
                                },
                                onDragEnd = {
                                    val selectedIdx = hoveredIndex
                                    isRadialExpanded = false
                                    onRadialExpandChange(false)
                                    dragOffset = Offset.Zero
                                    hoveredIndex = -1
                                    if (selectedIdx in targets.indices) {
                                        onRadialBoardSelected(targets[selectedIdx].board)
                                    }
                                },
                                onDragCancel = {
                                    isRadialExpanded = false
                                    onRadialExpandChange(false)
                                    dragOffset = Offset.Zero
                                    hoveredIndex = -1
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                if (!isRadialExpanded) {
                                    change.consume()
                                    onVerticalDragDelta(dragAmount)
                                }
                            }
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "InstaPin Hover Button",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Small '✕' close badge on corner of the bubble so user can hide the bubble in 1 tap anytime
                if (!isRadialExpanded) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E2E))
                            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                            .clickable { onStopHoverService() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Hide Bubble",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Redesigned Compact & Safe Quick Save Overlay Sheet:
 * - Never traps the screen:
 *   1. Prominent Top Escape Bar with "Minimize to Pin" AND "✕ Close" AND "Turn Off Hover" buttons.
 *   2. Tapping anywhere on the dark backdrop immediately minimizes back to the floating pin.
 *   3. 1-Tap Instant Board Save (default): Tapping any Board chip IMMEDIATELY saves the Reel & minimizes!
 *   4. Multi-Board / Moods / Note section is scrollable and has a sticky Top & Bottom Save button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSaveOverlaySheet(
    detectedUrl: String?,
    detectedAuthor: String,
    detectedCaption: String,
    captureStatusText: String = "",
    boards: List<Board>,
    moods: List<MoodTag>,
    onDismiss: () -> Unit,
    onStopHoverService: () -> Unit = onDismiss,
    onAutoGrabReelLink: suspend () -> String? = { null },
    onCreateBoard: suspend (String) -> Long? = { null },
    onSavePin: (
        manualUrl: String,
        author: String,
        caption: String,
        selectedBoardIds: List<Long>,
        selectedMoodIds: List<Long>,
        notes: String,
        runPhase2Now: Boolean
    ) -> Unit
) {
    var urlInput by remember(detectedUrl) { mutableStateOf(detectedUrl.orEmpty()) }
    var authorInput by remember(detectedAuthor) { mutableStateOf(detectedAuthor) }
    var captionInput by remember(detectedCaption) { mutableStateOf(detectedCaption) }
    var notesInput by remember { mutableStateOf("") }
    var runPhase2Analysis by remember { mutableStateOf(false) }
    var showAdvancedMultiSelect by remember { mutableStateOf(false) }
    var showAllBoards by remember { mutableStateOf(false) }
    var showNewBoardField by remember { mutableStateOf(false) }
    var newBoardName by remember { mutableStateOf("") }
    var creatingBoard by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val selectedBoardIds = remember { mutableStateListOf<Long>() }
    val selectedMoodIds = remember { mutableStateListOf<Long>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .clickable(enabled = false, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // ALWAYS-VISIBLE STICKY ESCAPE & HEADER BAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minimize back to edge bubble button
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Minimize",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Minimize", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    // Turn Off Hover Completely button
                    TextButton(onClick = onStopHoverService) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Stop Hover",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Hide Pin", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Big Red Close ('X') Circle
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = Color(0xFF2A2A3C),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Title & Detected Creator Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE60023)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Pin Instagram Reel",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = when {
                                urlInput.contains("/reel/", ignoreCase = true) || urlInput.contains("/p/", ignoreCase = true) ->
                                    "✓ Exact Reel Video Link Captured • Opens directly to video!"
                                authorInput.isNotBlank() ->
                                    "Detected @$authorInput • Auto-capturing exact Reel link on save"
                                else ->
                                    "Tap any Board below to save exact Reel video link"
                            },
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))

                val hasExactReelUrl = urlInput.contains("/reel/", ignoreCase = true) ||
                    urlInput.contains("/reels/", ignoreCase = true) ||
                    urlInput.contains("/p/", ignoreCase = true) ||
                    urlInput.contains("/tv/", ignoreCase = true) ||
                    urlInput.contains("/share/", ignoreCase = true)

                // EXACT-LINK WARNING + MANUAL GRAB/PASTE RESCUE ROW
                // Shown whenever automation could not capture the exact /reel/... URL so the
                // user always sees WHAT failed and can fix it in 1 tap instead of a Legacy pin.
                if (!hasExactReelUrl) {
                    val scope = rememberCoroutineScope()
                    var grabbingLink by remember { mutableStateOf(false) }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF3A2A10),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "⚠ Exact Reel link not captured yet" +
                                    if (captureStatusText.isNotBlank()) "\n$captureStatusText" else "",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                label = { Text("Paste Reel link (or tap Grab)", fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    grabbingLink = true
                                    scope.launch {
                                        val grabbed = onAutoGrabReelLink()
                                        if (!grabbed.isNullOrBlank()) urlInput = grabbed
                                        grabbingLink = false
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (grabbingLink) "Reading clipboard..." else "📋 Grab Reel Link from Clipboard",
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Tip: in Instagram tap ✈ Share -> Copy link on the Reel, then tap Grab above.",
                                color = Color(0xFF9CA3AF),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // SCROLLABLE CONTENT AREA
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (!showAdvancedMultiSelect) {
                        // FAST 1-TAP BOARD SAVE MODE (Default)
                        val boardsToShow = if (showAllBoards) {
                            boards
                        } else {
                            boards.filter { it.isPinnedToRadial }.take(6)
                        }
                        Text(
                            text = if (showAllBoards) {
                                "Tap any Board to Save & Close Instantly:"
                            } else {
                                "Tap a Quick Board to Save & Close Instantly:"
                            },
                            color = Color(0xFFE0E0F0),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            boardsToShow.forEach { board ->
                                Button(
                                    onClick = {
                                        // 1-TAP INSTANT SAVE & MINIMIZE!
                                        onSavePin(
                                            urlInput,
                                            authorInput,
                                            captionInput,
                                            listOf(board.id),
                                            emptyList(),
                                            "",
                                            board.autoAnalyzePhase2
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = parseHexColor(board.colorHex).copy(alpha = 0.88f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "${board.emoji} ${board.name}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showAllBoards = !showAllBoards },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = if (showAllBoards) "⌃ Quick Boards" else "More Boards…",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = { showNewBoardField = !showNewBoardField },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("+ New Board", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (showNewBoardField) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newBoardName,
                                    onValueChange = { newBoardName = it },
                                    label = { Text("New board name", fontSize = 11.sp) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newBoardName.isNotBlank() && !creatingBoard) {
                                            creatingBoard = true
                                            scope.launch {
                                                val newBoardId = onCreateBoard(newBoardName)
                                                creatingBoard = false
                                                if (newBoardId != null) {
                                                    onSavePin(
                                                        urlInput,
                                                        authorInput,
                                                        captionInput,
                                                        listOf(newBoardId),
                                                        emptyList(),
                                                        "",
                                                        runPhase2Analysis
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    enabled = newBoardName.isNotBlank() && !creatingBoard,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023))
                                ) {
                                    Text(
                                        text = if (creatingBoard) "Saving…" else "Create & Save",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 1-Tap AI Auto-Sort Button
                        Button(
                            onClick = {
                                onSavePin(
                                    urlInput,
                                    authorInput,
                                    captionInput,
                                    emptyList(),
                                    emptyList(),
                                    "",
                                    runPhase2Analysis
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✨ 1-Tap AI Auto-Board & Categorize",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        TextButton(
                            onClick = { showAdvancedMultiSelect = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "+ Multi-Board, Mood Tags or Add Note",
                                color = Color(0xFFA0A0B8),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // MULTI-SELECT BOARDS + MOODS + NOTE MODE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1. Select Multiple Boards",
                                color = Color(0xFFE0E0F0),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = { showAdvancedMultiSelect = false }) {
                                Text("Back to 1-Tap Mode", fontSize = 11.sp, color = Color(0xFF10B981))
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            boards.forEach { board ->
                                val isSelected = board.id in selectedBoardIds
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) selectedBoardIds.remove(board.id)
                                        else selectedBoardIds.add(board.id)
                                    },
                                    label = { Text("${board.emoji} ${board.name}") },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = parseHexColor(board.colorHex).copy(alpha = 0.85f),
                                        selectedLabelColor = Color.White,
                                        labelColor = Color(0xFFD0D0E0)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = "2. Assign Mood / Intent",
                            color = Color(0xFFE0E0F0),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            moods.forEach { mood ->
                                val isSelected = mood.id in selectedMoodIds
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) selectedMoodIds.remove(mood.id)
                                        else selectedMoodIds.add(mood.id)
                                    },
                                    label = { Text("${mood.emoji} ${mood.name}") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = parseHexColor(mood.colorHex).copy(alpha = 0.85f),
                                        selectedLabelColor = Color.White,
                                        labelColor = Color(0xFFD0D0E0)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = notesInput,
                            onValueChange = { notesInput = it },
                            label = { Text("Quick Note (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Instagram Link (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Run Phase 2 Deep Analysis",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = runPhase2Analysis,
                                onCheckedChange = { runPhase2Analysis = it }
                            )
                        }
                    }
                }

                // STICKY SAVE BUTTON AT BOTTOM FOR MULTI-SELECT MODE
                if (showAdvancedMultiSelect) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onSavePin(
                                urlInput,
                                authorInput,
                                captionInput,
                                selectedBoardIds.toList(),
                                selectedMoodIds.toList(),
                                notesInput,
                                runPhase2Analysis
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (selectedBoardIds.isEmpty()) {
                                "✨ Save with AI Auto-Board"
                            } else {
                                "📌 Save to ${selectedBoardIds.size} Board(s) & Close"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
