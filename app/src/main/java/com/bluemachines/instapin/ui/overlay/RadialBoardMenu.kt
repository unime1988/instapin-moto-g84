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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.PI
import kotlin.math.atan2
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
 * - Single Tap -> Opens Quick Save Multi-Board & Mood Sheet.
 * - Press & Hold -> Expands circular arc of Boards around your thumb; slide & release to save in 1 second!
 */
@Composable
fun PinterestRadialOverlay(
    radialBoards: List<Board>,
    isRightSide: Boolean = true,
    onSingleTapOpenSheet: () -> Unit,
    onRadialBoardSelected: (Board?) -> Unit // null = AI Auto-Categorize
) {
    var isRadialExpanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableIntStateOf(-1) }

    val density = LocalDensity.current
    val radiusPx = with(density) { 122.dp.toPx() }

    // Build radial targets: up to 5 pinned boards + 1 "AI Smart Sort" node
    val targets = remember(radialBoards, isRightSide) {
        val items = mutableListOf<Pair<Board?, Triple<String, String, Color>>>()
        items.add(null to Triple("AI Auto-Sort", "✨", Color(0xFF8B5CF6)))
        radialBoards.take(5).forEach { b ->
            items.add(b to Triple(b.name, b.emoji, parseHexColor(b.colorHex)))
        }
        val count = items.size
        // Arc angles facing inward from the right or left screen edge
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

    Box(
        modifier = Modifier.fillMaxSize(),
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
            modifier = Modifier.padding(horizontal = 12.dp),
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

            // Main Floating Pinterest Trigger Bubble
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFE60023), Color(0xFFFF4B2B))
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
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
                                dragOffset = Offset.Zero
                                hoveredIndex = -1
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount
                                val distance = hypot(dragOffset.x, dragOffset.y)
                                if (distance > 36f) {
                                    // Find radial target with closest angle/position
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
                                dragOffset = Offset.Zero
                                hoveredIndex = -1
                                if (selectedIdx in targets.indices) {
                                    onRadialBoardSelected(targets[selectedIdx].board)
                                }
                            },
                            onDragCancel = {
                                isRadialExpanded = false
                                dragOffset = Offset.Zero
                                hoveredIndex = -1
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "InstaPin Hover Button",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * Phase 1 Quick Save Sheet (shown when single-tapping the floating bubble or via Android Share Sheet).
 * Allows selecting MULTIPLE Boards, MULTIPLE Moods, custom note, and optional immediate Phase 2 Deep Analysis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSaveOverlaySheet(
    detectedUrl: String?,
    detectedAuthor: String,
    detectedCaption: String,
    boards: List<Board>,
    moods: List<MoodTag>,
    onDismiss: () -> Unit,
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

    val selectedBoardIds = remember { mutableStateListOf<Long>() }
    val selectedMoodIds = remember { mutableStateListOf<Long>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE60023)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Pin Instagram Reel",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = if (authorInput.isNotBlank()) {
                                    "Detected @$authorInput • Multi-Board & Mood Save"
                                } else {
                                    "Saves link pointer only (No video download)"
                                },
                                color = Color(0xFFA0A0B8),
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Select Multiple Boards
                Text(
                    text = "1. Select Boards (Multi-select or leave empty for AI Auto-Board)",
                    color = Color(0xFFE0E0F0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
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
                            label = {
                                Text("${board.emoji} ${board.name}")
                            },
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

                Spacer(Modifier.height(12.dp))

                // Select Moods
                Text(
                    text = "2. Assign Mood / Intent",
                    color = Color(0xFFE0E0F0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
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
                            label = {
                                Text("${mood.emoji} ${mood.name}")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = parseHexColor(mood.colorHex).copy(alpha = 0.85f),
                                selectedLabelColor = Color.White,
                                labelColor = Color(0xFFD0D0E0)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Optional URL / Note override
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Instagram Reel Link (auto-filled from Share/Clipboard)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("Quick Note (Why did you like this video?)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                // Phase 2 Toggle
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
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Run Phase 2 Deep Video Breakdown Now",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Extracts summary, hook, transcript & recreate script",
                                color = Color(0xFFA0A0B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = runPhase2Analysis,
                        onCheckedChange = { runPhase2Analysis = it }
                    )
                }

                Spacer(Modifier.height(16.dp))

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
                        .height(50.dp)
                ) {
                    Text(
                        text = if (selectedBoardIds.isEmpty()) {
                            "✨ Smart Pin (AI Auto-Board + Metadata)"
                        } else {
                            "📌 Pin to ${selectedBoardIds.size} Board(s)"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
