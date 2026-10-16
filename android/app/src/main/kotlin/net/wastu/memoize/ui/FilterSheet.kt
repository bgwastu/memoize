package net.wastu.memoize.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.wastu.memoize.data.AttachmentFilter
import net.wastu.memoize.data.NoteFilter
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteSort
import net.wastu.memoize.data.NoteState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    notes: NoteRepository,
    filter: NoteFilter,
    onDismiss: () -> Unit,
    onChange: (NoteFilter) -> Unit,
    columnsContent: (@Composable () -> Unit)? = null
) {
    var tags by remember(notes) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(notes) { tags = notes.tags() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sort and filter",
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge
                )
                TextButton(
                    onClick = { onChange(NoteFilter(query = filter.query)) },
                    enabled = filter.tag != null || filter.attachments != AttachmentFilter.ANY ||
                        filter.state != NoteState.NORMAL || filter.sort != NoteSort.NEWEST
                ) {
                    Text("Reset")
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Sort by",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NoteSort.entries.forEach { sort ->
                                FilterChip(
                                    selected = filter.sort == sort,
                                    onClick = { onChange(filter.copy(sort = sort)) },
                                    label = {
                                        Text(when (sort) {
                                            NoteSort.NEWEST -> "Newest"
                                            NoteSort.OLDEST -> "Oldest"
                                            NoteSort.UPDATED -> "Recently updated"
                                        })
                                    }
                                )
                            }
                        }
                    }
                }
                columnsContent?.let { content -> item { content() } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Notes state",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NoteState.entries.forEach { state ->
                                FilterChip(
                                    selected = filter.state == state,
                                    onClick = { onChange(filter.copy(state = state)) },
                                    label = { Text(if (state == NoteState.NORMAL) "Active" else "Archived") }
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Attachments",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AttachmentFilter.entries.forEach { attachment ->
                                FilterChip(
                                    selected = filter.attachments == attachment,
                                    onClick = { onChange(filter.copy(attachments = attachment)) },
                                    label = {
                                        Text(when (attachment) {
                                            AttachmentFilter.ANY -> "Any"
                                            AttachmentFilter.IMAGES -> "Images"
                                            AttachmentFilter.FILES -> "Files"
                                        })
                                    }
                                )
                            }
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Tags",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = filter.tag == null,
                                    onClick = { onChange(filter.copy(tag = null)) },
                                    label = { Text("All tags") }
                                )
                                for (tag in tags) {
                                    FilterChip(
                                        selected = filter.tag == tag,
                                        onClick = { onChange(filter.copy(tag = if (filter.tag == tag) null else tag)) },
                                        label = { Text("#$tag") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                    Text("Apply")
                }
            }
        }
    }
}
