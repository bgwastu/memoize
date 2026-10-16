package net.wastu.memoize.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.memoize.R
import net.wastu.memoize.data.Note
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteSelectionBar(count: Int, busy: Boolean, archived: Boolean, pinned: Boolean = false, onClose: () -> Unit,
    onArchive: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilters: () -> Unit, onTogglePin: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(modifier = Modifier.testTag("note-selection-bar"),
        title = { Text("$count selected", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = { IconAction(R.drawable.ic_close, "Exit selection", !busy, onClose) },
        actions = {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            else {
                IconAction(R.drawable.ic_pin, if (pinned) "Unpin selected notes" else "Pin selected notes", count > 0, onTogglePin)
                IconAction(R.drawable.ic_archive, if (archived) "Unarchive selected notes" else "Archive selected notes", count > 0, onArchive)
                Box {
                    IconAction(R.drawable.ic_more, "Selection actions") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, enabled = count > 0,
                            onClick = { menu = false; onDelete() })
                        DropdownMenuItem(text = { Text("Share text") }, enabled = count > 0,
                            onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Select all results") }, onClick = { menu = false; onSelectAll() })
                        DropdownMenuItem(text = { Text("Sort and filter") }, onClick = { menu = false; onFilters() })
                        DropdownMenuItem(text = { Text("Clear selection") }, enabled = count > 0,
                            onClick = { menu = false; onClear() })
                    }
                }
            }
        })
}

/** Keep long sources out of Binder extras; the chooser receives a read-only Markdown file. */
internal suspend fun shareSelectedNotes(context: Context, notes: List<Note>) {
    val length = notes.sumOf { it.content.length.toLong() } + (notes.size - 1).coerceAtLeast(0) * 9L
    val send = Intent(Intent.ACTION_SEND)
    if (length <= 100_000) {
        val source = withContext(Dispatchers.Default) { notes.joinToString("\n\n---\n\n") { it.content } }
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, source)
    } else {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared-notes").apply { mkdirs() }
            File.createTempFile("memoize-notes-", ".md", directory).apply {
                bufferedWriter().use { output ->
                    notes.forEachIndexed { index, note ->
                        if (index > 0) output.write("\n\n---\n\n")
                        output.write(note.content)
                    }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file, "Memoize notes.md")
        send.type = "text/markdown"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = android.content.ClipData.newRawUri("Selected notes", uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share selected notes"))
}
