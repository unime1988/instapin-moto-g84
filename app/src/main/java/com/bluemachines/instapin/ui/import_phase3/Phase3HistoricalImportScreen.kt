package com.bluemachines.instapin.ui.import_phase3

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemachines.instapin.data.local.entity.ImportBatch
import com.bluemachines.instapin.data.local.entity.PopulatedVideoPin
import com.bluemachines.instapin.ui.overlay.parseHexColor
import com.bluemachines.instapin.worker.HistoricalImportWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 3 Screen:
 * - Imports Instagram's official `liked_posts.json` and `saved_posts.json` without touching Instagram.
 * - Deduplicates & runs AI categorization across historical likes/saves.
 * - Provides a Bulk Triage Inbox grouped by Domain & Board so hundreds of past videos are neatly organized.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Phase3HistoricalImportScreen(
    triagePins: List<PopulatedVideoPin>,
    importBatches: List<ImportBatch>,
    onApproveAllTriage: () -> Unit,
    onApproveSinglePin: (Long) -> Unit,
    onOpenPinDetail: (Long) -> Unit,
    onRunDemoImport: () -> Unit
) {
    val context = LocalContext.current
    var enrichOnlineMetadata by remember { mutableStateOf(true) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            HistoricalImportWorker.enqueue(
                context = context,
                fileUri = uri,
                fileName = uri.lastPathSegment ?: "instagram_export.json",
                enrichOnline = enrichOnlineMetadata
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Phase 3: Historical Account Sorter",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Organize all previously liked & saved Instagram Reels into Boards",
                                color = Color(0xFFA0A0B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "How to export from Instagram (takes 1 minute):\n" +
                            "1. Open Instagram -> Profile -> Menu -> Accounts Center\n" +
                            "2. Tap 'Your information and permissions' -> 'Download your information'\n" +
                            "3. Choose 'Some of your information' -> Select 'Likes' & 'Saved' in JSON format\n" +
                            "4. Select liked_posts.json or saved_posts.json below!",
                        color = Color(0xFFD0D0E4),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enrichOnlineMetadata,
                            onCheckedChange = { enrichOnlineMetadata = it }
                        )
                        Text(
                            text = "Fetch live caption & creator metadata for historical links",
                            color = Color(0xFFE0E0F0),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60023)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import JSON File", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = onRunDemoImport,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Test Sample Import", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (importBatches.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Import Batches",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            items(importBatches.take(3)) { batch ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = batch.sourceFileName,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Discovered: ${batch.totalDiscovered} • Imported: ${batch.newlyImported} • Duplicates Skipped: ${batch.duplicatesSkipped}",
                                color = Color(0xFFA0A0B8),
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = batch.status,
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AI Historical Triage Inbox (${triagePins.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Auto-sorted by Domain, Author & Original Liked Date",
                        color = Color(0xFFA0A0B8),
                        fontSize = 12.sp
                    )
                }
                if (triagePins.isNotEmpty()) {
                    Button(
                        onClick = onApproveAllTriage,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Approve All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(triagePins, key = { it.pin.id }) { item ->
            Card(
                onClick = { onOpenPinDetail(item.pin.id) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "@${item.pin.authorHandle}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        val likedDate = item.pin.originalLikedOrPostedAt ?: item.pin.savedAt
                        Text(
                            text = "Liked ${dateFormat.format(Date(likedDate))}",
                            color = Color(0xFFA0A0B8),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.pin.caption,
                        color = Color(0xFFD0D0E4),
                        fontSize = 13.sp,
                        maxLines = 2
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                color = Color(0xFF8B5CF6).copy(alpha = 0.22f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = item.pin.domainCategory,
                                    color = Color(0xFFC4B5FD),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            item.boards.forEach { board ->
                                Surface(
                                    color = parseHexColor(board.colorHex).copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "${board.emoji} ${board.name}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { onApproveSinglePin(item.pin.id) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Confirm", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
