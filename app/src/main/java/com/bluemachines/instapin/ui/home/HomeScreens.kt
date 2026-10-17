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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircleOutline
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bluemachines.instapin.data.local.FeedLayoutMode
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

private fun domainAccentColor(domain: String): Color {
    val d = domain.lowercase()
    return when {
        d.contains("edit") -> Color(0xFFE60023)
        d.contains("trad") -> Color(0xFF10B981)
        d.contains("health") || d.contains("fit") -> Color(0xFFF59E0B)
        d.contains("job") || d.contains("career") -> Color(0xFF3B82F6)
        else -> Color(0xFF8B5CF6)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinFeedScreen(
    pins: List<PopulatedVideoPin>,
    boards: List<BoardWithCount>,
    moods: List<MoodTag>,
    domains: List<DomainSummary>,
    authors: List<AuthorSummary>,
    filterState: PinFilterState,
    layoutMode: FeedLayoutMode = FeedLayoutMode.PINTEREST_MASONRY,
    fontMultiplier: Float = 1.0f,
    onToggleLayoutMode: () -> Unit = {},
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
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Search Bar + Masonry/List Layout Toggle Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = filterState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search topic, @creator, board, mood...", fontSize = (13 * fontMultiplier).sp) },
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
                modifier = Modifier.weight(1f)
            )

            Surface(
                onClick = onToggleLayoutMode,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (layoutMode == FeedLayoutMode.PINTEREST_MASONRY) {
                            Icons.AutoMirrored.Filled.ViewList
                        } else {
                            Icons.Default.GridView
                        },
                        contentDescription = "Switch Layout",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Multi-Dimensional Filter Bar (Date, Board, Domain, Author, Mood)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    label = { Text("✕ Reset") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEF4444),
                        selectedLabelColor = Color.White
                    )
                )
            }

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

            FilterChip(
                selected = filterState.onlyPhase2Analyzed,
                onClick = { onToggleOnlyAnalyzed(!filterState.onlyPhase2Analyzed) },
                label = { Text("✨ AI Studio") }
            )

            domains.forEach { dom ->
                val isSelected = filterState.selectedDomain.equals(dom.domainCategory, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectDomain(if (isSelected) null else dom.domainCategory) },
                    label = { Text("🏷️ ${dom.domainCategory} (${dom.videoCount})") }
                )
            }

            authors.take(12).forEach { author ->
                val isSelected = filterState.selectedAuthor.equals(author.authorHandle, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectAuthor(if (isSelected) null else author.authorHandle) },
                    label = { Text("@${author.authorHandle} (${author.videoCount})") }
                )
            }

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
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = (18 * fontMultiplier).sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Open Instagram and tap the floating red Pinterest button on the right edge of your screen, or tap '+' below, or test Phase 3 Historical Import!",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = (13 * fontMultiplier).sp
                    )
                }
            }
        } else if (layoutMode == FeedLayoutMode.PINTEREST_MASONRY) {
            // 2-COLUMN STAGGERED PINTEREST MASONRY GRID
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(pins, key = { it.pin.id }) { populated ->
                    PinterestMasonryPinCard(
                        populated = populated,
                        fontMultiplier = fontMultiplier,
                        dateFormat = dateFormat,
                        onOpenDetail = { onOpenPinDetail(populated.pin.id) },
                        onTriggerPhase2 = { onTriggerPhase2(populated.pin.id) },
                        onOpenInstagram = { openInstagramPermalink(context, populated.pin.instagramUrl) },
                        onAuthorClick = { onSelectAuthor(populated.pin.authorHandle) },
                        onBoardClick = onSelectBoard
                    )
                }
            }
        } else {
            // 1-COLUMN DETAILED LIST
            LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pins, key = { it.pin.id }) { populated ->
                    DetailedListPinCard(
                        populated = populated,
                        fontMultiplier = fontMultiplier,
                        dateFormat = dateFormat,
                        onOpenDetail = { onOpenPinDetail(populated.pin.id) },
                        onTriggerPhase2 = { onTriggerPhase2(populated.pin.id) },
                        onOpenInstagram = { openInstagramPermalink(context, populated.pin.instagramUrl) },
                        onAuthorClick = { onSelectAuthor(populated.pin.authorHandle) },
                        onBoardClick = onSelectBoard,
                        onMoodClick = onSelectMood
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinterestMasonryPinCard(
    populated: PopulatedVideoPin,
    fontMultiplier: Float,
    dateFormat: SimpleDateFormat,
    onOpenDetail: () -> Unit,
    onTriggerPhase2: () -> Unit,
    onOpenInstagram: () -> Unit,
    onAuthorClick: () -> Unit,
    onBoardClick: (Long) -> Unit
) {
    val pin = populated.pin
    val accent = domainAccentColor(pin.domainCategory)

    Card(
        onClick = onOpenDetail,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Visual Cover Header (Thumbnail or Rich Gradient Cover)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (pin.thumbnailUrl.isNotBlank()) 156.dp else 96.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.28f))
                        )
                    )
            ) {
                if (pin.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = pin.thumbnailUrl,
                        contentDescription = pin.subjectTopic,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                                )
                            )
                    )
                }

                // Top-left Domain Badge
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = pin.domainCategory,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = (10 * fontMultiplier).sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                // Top-right 1-Tap Watch on IG Icon
                IconButton(
                    onClick = onOpenInstagram,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleOutline,
                        contentDescription = "Watch Reel",
                        tint = Color.White
                    )
                }

                // Bottom-left @Author Handle inside cover
                Text(
                    text = "@${pin.authorHandle}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (12 * fontMultiplier).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable { onAuthorClick() }
                )
            }

            Column(modifier = Modifier.padding(11.dp)) {
                if (pin.subjectTopic.isNotBlank()) {
                    Text(
                        text = pin.subjectTopic,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = (13 * fontMultiplier).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Text(
                    text = pin.caption,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = (11.5f * fontMultiplier).sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = (15 * fontMultiplier).sp
                )

                if (populated.analysis != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFF8B5CF6).copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "✨ AI Script Ready",
                            color = Color(0xFFC4B5FD),
                            fontSize = (10 * fontMultiplier).sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    populated.boards.take(2).forEach { b ->
                        Surface(
                            color = parseHexColor(b.colorHex).copy(alpha = 0.22f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onBoardClick(b.id) }
                        ) {
                            Text(
                                text = "${b.emoji} ${b.name}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = (10 * fontMultiplier).sp,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateFormat.format(Date(pin.savedAt)),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        fontSize = (10 * fontMultiplier).sp
                    )
                    Row {
                        IconButton(
                            onClick = onTriggerPhase2,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Repurpose",
                                tint = if (populated.analysis != null) Color(0xFF10B981) else Color(0xFF8B5CF6),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenInstagram,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open IG",
                                tint = Color(0xFFE60023),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailedListPinCard(
    populated: PopulatedVideoPin,
    fontMultiplier: Float,
    dateFormat: SimpleDateFormat,
    onOpenDetail: () -> Unit,
    onTriggerPhase2: () -> Unit,
    onOpenInstagram: () -> Unit,
    onAuthorClick: () -> Unit,
    onBoardClick: (Long) -> Unit,
    onMoodClick: (Long) -> Unit
) {
    val pin = populated.pin
    Card(
        onClick = onOpenDetail,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = domainAccentColor(pin.domainCategory).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = pin.domainCategory,
                            color = domainAccentColor(pin.domainCategory),
                            fontWeight = FontWeight.Bold,
                            fontSize = (11 * fontMultiplier).sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "@${pin.authorHandle}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = (14 * fontMultiplier).sp,
                        modifier = Modifier.clickable { onAuthorClick() }
                    )
                }

                Text(
                    text = dateFormat.format(Date(pin.savedAt)),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = (11 * fontMultiplier).sp
                )
            }

            if (pin.subjectTopic.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = pin.subjectTopic,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (15 * fontMultiplier).sp
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = pin.caption,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                fontSize = (13 * fontMultiplier).sp,
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
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (12 * fontMultiplier).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

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
                            modifier = Modifier.clickable { onBoardClick(b.id) }
                        ) {
                            Text(
                                text = "${b.emoji} ${b.name}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = (11 * fontMultiplier).sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    populated.moods.forEach { m ->
                        Surface(
                            color = parseHexColor(m.colorHex).copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onMoodClick(m.id) }
                        ) {
                            Text(
                                text = "${m.emoji} ${m.name}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = (11 * fontMultiplier).sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onTriggerPhase2, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Analyze Phase 2",
                            tint = if (populated.analysis != null) Color(0xFF10B981) else Color(0xFF8B5CF6),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onOpenInstagram, modifier = Modifier.size(32.dp)) {
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
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Boards marked 📌 appear in your Hold-and-Slide Radial Menu",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                Text("New Board", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = board.description.ifBlank { "Tap to browse saved Reels" },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                color = MaterialTheme.colorScheme.onBackground,
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
