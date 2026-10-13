package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import net.wastu.memoize.R
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.RecoveryRevision

/** One browser for Settings and the editor; restoration remains repository-owned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryBrowser(notes: NoteRepository, noteId: String? = null, onDismiss: () -> Unit,
    onRestore: suspend (RecoveryRevision) -> Unit) {
    var versions by remember(notes, noteId) { mutableStateOf<List<RecoveryRevision>?>(null) }
    var selectedId by rememberSaveable(notes.account, noteId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(notes.account, noteId) { mutableStateOf("") }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    LaunchedEffect(notes, noteId, loadAttempt) {
        error = null
        versions = null
        try {
            versions = (if (noteId == null) notes.recoveries() else notes.recovery(noteId)).sortedByDescending { it.savedAt }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            error = exception.message ?: "Unable to load saved versions. Try again."
        }
    }
    val selected = versions?.firstOrNull { it.id == selectedId }
    fun back() {
        if (restoring) return
        if (selectedId != null) { selectedId = null; error = null } else onDismiss()
    }
    BackHandler { back() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
                TopAppBar(title = { Text(if (selectedId == null) "Recovery versions" else "Saved version") },
                    navigationIcon = {
                        IconButton(onClick = ::back, enabled = !restoring) {
                            AppIcon(if (selectedId == null) R.drawable.ic_close else R.drawable.ic_back,
                                if (selectedId == null) "Close recovery" else "Back to versions")
                        }
                    })
            }, bottomBar = {
                if (selected != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your current version is kept in recovery before restoring.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                            restoring = true
                            error = null
                            scope.launch {
                                try { onRestore(selected) }
                                catch (exception: Exception) {
                                    if (exception is CancellationException) throw exception
                                    error = exception.message ?: "Unable to restore this version. Try again."
                                } finally { restoring = false }
                            }
                        }) {
                            if (restoring) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (restoring) "Restoring…" else "Restore this version")
                        }
                    }
                }
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 840.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { message ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                                if (versions == null) TextButton(onClick = { loadAttempt++ }) { Text("Try again") }
                            }
                        }
                    }
                    when {
                        versions == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading recovery versions" })
                        }
                        selected != null -> key(selected.id) { RecoveryPreview(selected, Modifier.weight(1f)) }
                        versions != null -> {
                            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                                label = { Text("Search saved versions") },
                                leadingIcon = { AppIcon(R.drawable.ic_search) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                    AppIcon(R.drawable.ic_close, "Clear recovery search")
                                } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            val loaded = requireNotNull(versions)
                            val filtered = remember(loaded, query) {
                                loaded.filter { query.isBlank() || it.snapshot.content.contains(query, ignoreCase = true) ||
                                    it.reason.contains(query, ignoreCase = true) }
                            }
                            if (filtered.isEmpty()) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (loaded.isEmpty()) "No saved versions yet" else "No matching versions", style = MaterialTheme.typography.titleMedium)
                                    Text(if (loaded.isEmpty()) "Copies appear here when a note is deleted, restored or has a sync conflict."
                                        else "Try another word or clear the search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Text("${filtered.size} saved ${if (filtered.size == 1) "version" else "versions"}",
                                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).testTag("recovery-list"),
                                    contentPadding = PaddingValues(bottom = 24.dp)) {
                                    filtered.groupBy { it.noteId }.forEach { (id, group) ->
                                        if (noteId == null) item(key = "note-$id") {
                                            Text(recoveryTitle(group.first()), Modifier.fillMaxWidth().padding(16.dp).semantics { heading() },
                                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        items(group, key = { it.id }) { version ->
                                            ListItem(headlineContent = { Text(recoveryTimestamp(version.savedAt), style = MaterialTheme.typography.titleSmall) },
                                                supportingContent = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(version.reason, style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(version.snapshot.content.ifEmpty { "Empty note" }, maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                        if (version.snapshot.attachments.isNotEmpty()) Text(
                                                            "${version.snapshot.attachments.size} attachments", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }, trailingContent = { AppIcon(R.drawable.ic_chevron_right) },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recovery-${version.id}")
                                                    .clickable(role = Role.Button) { keyboard?.hide(); selectedId = version.id; error = null })
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp))
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
}

private fun recoveryTimestamp(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

private fun recoveryTitle(version: RecoveryRevision): String =
    version.snapshot.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "Untitled note"

@Composable
private fun RecoveryPreview(version: RecoveryRevision, modifier: Modifier) {
    // Bound individual layouts, including million-character notes, while keeping
    // every saved source character available in the scrolling preview.
    val parts = remember(version.id, version.snapshot.content) {
        val source = version.snapshot.content
        buildList {
            var start = 0
            while (start < source.length) {
                var end = minOf(start + 4_000, source.length)
                if (end < source.length && source[end].isLowSurrogate()) end--
                add(source.substring(start, end))
                start = end
            }
        }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("recovery-preview"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(recoveryTitle(version), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(recoveryTimestamp(version.savedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(version.reason, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(top = 16.dp))
        }
        if (parts.isEmpty()) item { Text("Empty note", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(parts.size) { index ->
            SelectionContainer { Text(parts[index], style = MaterialTheme.typography.bodyLarge) }
        }
        items(version.snapshot.attachments, key = { "attachment-${it.id}" }) { attachment ->
            ListItem(headlineContent = { Text(attachment.filename) }, leadingContent = { AppIcon(R.drawable.ic_attach) },
                supportingContent = { Text("Included when restored") })
        }
    }
}
