package net.wastu.memoize.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.wastu.memoize.data.AttachmentFilter
import net.wastu.memoize.data.NoteFilter
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteState
import net.wastu.memoize.data.NoteSort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(notes: NoteRepository, filter: NoteFilter, onDismiss: () -> Unit, onChange: (NoteFilter) -> Unit,
    columnsContent: (@Composable () -> Unit)? = null) {
    var tags by remember(notes) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(notes) { tags = notes.tags() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Sort and filter", Modifier.padding(bottom = 20.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sort by", Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionGroup {
                            NoteSort.entries.forEach { sort ->
                                TagChoice(when (sort) {
                                    NoteSort.NEWEST -> "Newest first"
                                    NoteSort.OLDEST -> "Oldest first"
                                    NoteSort.UPDATED -> "Recently updated"
                                }, filter.sort == sort) { onChange(filter.copy(sort = sort)) }
                            }
                        }
                    }
                }
                columnsContent?.let { content -> item { content() } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notes", Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionGroup {
                            NoteState.entries.forEach { state ->
                                TagChoice(if (state == NoteState.NORMAL) "Active" else "Archived", filter.state == state) {
                                    onChange(filter.copy(state = state))
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Attachments", Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionGroup {
                            AttachmentFilter.entries.forEach { attachment ->
                                TagChoice(when (attachment) {
                                        AttachmentFilter.ANY -> "Any"
                                        AttachmentFilter.IMAGES -> "Images"
                                        AttachmentFilter.FILES -> "Files"
                                    }, filter.attachments == attachment) {
                                    onChange(filter.copy(attachments = attachment))
                                }
                            }
                        }
                    }
                }
                item { Text("Tag", Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { TagChoice("All tags", filter.tag == null) { onChange(filter.copy(tag = null)) } }
                items(tags, key = { it }) { tag ->
                    TagChoice("#$tag", filter.tag == tag) { onChange(filter.copy(tag = tag)) }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = { onChange(NoteFilter(query = filter.query)) }, modifier = Modifier.heightIn(min = 48.dp),
                    enabled = filter.tag != null || filter.attachments != AttachmentFilter.ANY || filter.state != NoteState.NORMAL || filter.sort != NoteSort.NEWEST) {
                    Text("Reset filters")
                }
                Button(onClick = onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SelectionGroup(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun TagChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
