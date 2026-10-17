package com.bluemachines.instapin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.entity.AuthorSummary
import com.bluemachines.instapin.data.local.entity.BoardWithCount
import com.bluemachines.instapin.data.local.entity.DomainSummary
import com.bluemachines.instapin.data.local.entity.MoodTag
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.ui.DateFilterRange
import com.bluemachines.instapin.ui.PinFilterState
import com.bluemachines.instapin.ui.detail.openInstagramPermalink
import com.bluemachines.instapin.ui.overlay.parseHexColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinFeedScreen(
    pins: List<PopulatedVideoPin>,
    boards: List<BoardWithCount>,
    moods: List<MoodTag>,
    domains: List<DomainSummary>,
    authors: List<AuthorSummary>,
    filterState: PinFilterState,
    onSearchQueryChange: (String) -> Unit,
    onSelectBoard: (Long?) -> Unit,
    onSelectMood: (Long?) -> Unit,
    onSelectDomain: (String?) -> Unit,
    onSelectAuthor: (String?) -> Unit,
    onSelectDateRange: (DateFilterRange) -> Unit,
    onToggleOnlyAnalyzed: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenPinDetail: (Long) -> Unit,
    onTriggerPhase2: (Long) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Search Bar
        OutlinedTextField(
            value = filterState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search by topic, @creator, caption, board, mood...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filterState.searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // Multi-Dimensional Filter Bar (Date, Board, Domain, Author, Mood)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reset all filters chip if any active
            val hasActiveFilter = filterState.selectedBoardId != null ||
                filterState.selectedMoodId != null ||
                filterState.selectedDomain != null ||
                filterState.selectedAuthor != null ||
                filterState.dateRange != DateFilterRange.ALL_TIME ||
                filterState.onlyPhase2Analyzed

            if (hasActiveFilter) {
                FilterChip(
                    selected = true,
                    onClick = onClearFilters,
                    label = { Text("✕ Reset Filters") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEF4444)
                    )
                )
            }

            // Date Filter Chips
            DateFilterRange.entries.forEach { range ->
                FilterChip(
                    selected = filterState.dateRange == range && range != DateFilterRange.ALL_TIME,
                    onClick = {
                        onSelectDateRange(
                            if (filterState.dateRange == range) DateFilterRange.ALL_TIME else range
                        )
                    },
                    label = { Text("📅 ${range.label}") }
                )
            }

            // Phase 2 Repurposed Filter
            FilterChip(
                selected = filterState.onlyPhase2Analyzed,
                onClick = { onToggleOnlyAnalyzed(!filterState.onlyPhase2Analyzed) },
                label = { Text("✨ AI Analyzed") }
            )

            // Domain Filter Chips
            domains.forEach { dom ->
                val isSelected = filterState.selectedDomain.equals(dom.domainCategory, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectDomain(if (isSelected) null else dom.domainCategory) },
                    label = { Text("🏷️ ${dom.domainCategory} (${dom.videoCount})") }
                )
            }

            // Author Filter Chips
            authors.take(12).forEach { author ->
                val isSelected = filterState.selectedAuthor.equals(author.authorHandle, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectAuthor(if (isSelected) null else author.authorHandle) },
                    label = { Text("@${author.authorHandle} (${author.videoCount})") }
                )
            }

            // Mood Filter Chips
            moods.forEach { mood ->
                val isSelected = filterState.selectedMoodId == mood.id
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectMood(if (isSelected) null else mood.id) },
                    label = { Text("${mood.emoji} ${mood.name}") }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (pins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = null,
                        tint = Color(0xFFE60023),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No Instagram Reels Pinned Yet",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Start the Floating Hover Bubble from the Moto G84 Setup tab, or tap '+' below to pin a Reel link, or import your historical liked_posts.json in Phase 3!",
                        color = Color(0xFFA0A0B8),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pins, key = { it.pin.id }) { populated ->
                    val pin = populated.pin
                    Card(
                        onClick = { onOpenPinDetail(pin.id) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Top Row: Domain + Author + Date
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFFE60023).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = pin.domainCategory,
                                            color = Color(0xFFFF6B81),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "@${pin.authorHandle}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.clickable {
                                            onSelectAuthor(pin.authorHandle)
                                        }
                                    )
                                }

                                Text(
                                    text = dateFormat.format(Date(pin.savedAt)),
                                    color = Color(0xFFA0A0B8),
                                    fontSize = 11.sp
                                )
                            }

                            if (pin.subjectTopic.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = pin.subjectTopic,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = pin.caption,
                                color = Color(0xFFD0D0E4),
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (populated.analysis != null) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = Color(0xFF8B5CF6).copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "✨ AI Summary: ${populated.analysis.executiveSummary}",
                                        color = Color(0xFFDDD6FE),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Bottom Row: Boards + Moods + Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    populated.boards.forEach { b ->
                                        Surface(
                                            color = parseHexColor(b.colorHex).copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable { onSelectBoard(b.id) }
                                        ) {
                                            Text(
                                                text = "${b.emoji} ${b.name}",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    populated.moods.forEach { m ->
                                        Surface(
                                            color = parseHexColor(m.colorHex).copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable { onSelectMood(m.id) }
                                        ) {
                                            Text(
                                                text = "${m.emoji} ${m.name}",
                                                color = Color(0xFFE0E0FF),
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { onTriggerPhase2(pin.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "Analyze Phase 2",
                                            tint = if (populated.analysis != null) Color(0xFF10B981) else Color(0xFF8B5CF6),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { openInstagramPermalink(context, pin.instagramUrl) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open in Instagram",
                                            tint = Color(0xFFE60023),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pinterest-style Visual Boards Grid + Author & Domain Explorer.
 */
@Composable
fun BoardsGridScreen(
    boardsWithCount: List<BoardWithCount>,
    authors: List<AuthorSummary>,
    domains: List<DomainSummary>,
    onBoardClick: (Long) -> Unit,
    onAuthorClick: (String) -> Unit,
    onDomainClick: (String) -> Unit,
    onCreateBoard: (name: String, emoji: String, colorHex: String, desc: String, pinRadial: Boolean, autoPhase2: Boolean) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Your Pinterest Boards",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Boards marked 📌 appear in your Hold-and-Slide Radial Menu",
                    color = Color(0xFFA0A0B8),
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Board", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(boardsWithCount, key = { it.board.id }) { item ->
                val board = item.board
                val baseColor = parseHexColor(board.colorHex)
                Card(
                    onClick = { onBoardClick(board.id) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(74.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(baseColor.copy(alpha = 0.85f), baseColor.copy(alpha = 0.35f))
                                    )
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = board.emoji,
                                fontSize = 28.sp,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Text(
                                    text = "${item.pinCount} Pins",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = board.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = board.description.ifBlank { "Tap to browse saved Reels" },
                                color = Color(0xFFA0A0B8),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (board.isPinnedToRadial) {
                                    Text(
                                        text = "📌 Radial Menu",
                                        color = Color(0xFF10B981),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (board.autoAnalyzePhase2) {
                                    Text(
                                        text = "✨ Auto-Phase 2",
                                        color = Color(0xFFA78BFA),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (authors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Top Saved Creators (@Author)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                authors.forEach { a ->
                    OutlinedButton(
                        onClick = { onAuthorClick(a.authorHandle) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("@${a.authorHandle} (${a.videoCount})", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("📌") }
        var desc by remember { mutableStateOf("") }
        var pinRadial by remember { mutableStateOf(true) }
        var autoPhase2 by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Board") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Board Name (e.g., Cinematography, Options)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it },
                        label = { Text("Emoji Icon (e.g., 🎥, 📊, 🧠)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description / Focus") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pinRadial, onCheckedChange = { pinRadial = it })
                        Text("Pin to Radial Hold-and-Slide Menu", fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoPhase2, onCheckedChange = { autoPhase2 = it })
                        Text("Auto-Run Phase 2 Analysis on Save", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateBoard(name, emoji, "#E60023", desc, pinRadial, autoPhase2)
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
