package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.memoize.R
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.LiteralSearch
import net.wastu.memoize.data.Note
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteState
import net.wastu.memoize.data.RecoveryRevision

private data class ObservedNote(val account: String, val id: String, val note: Note?)

@Composable
fun NoteEditor(graph: AppGraph, notes: NoteRepository, id: String, query: String, focus: Boolean,
    snackbar: SnackbarHostState, onReplace: (String) -> Unit, onClose: () -> Unit, sessionKey: String = id) {
    val attachments = remember(notes, sessionKey) { AttachmentActions() }
    DisposableEffect(attachments) { onDispose { attachments.cancel() } }
    val observed by remember(notes, id) { notes.observeNote(id).map { ObservedNote(notes.account, id, it) } }.collectAsStateWithLifecycle(null)
    // Flow state retains its last emission while a new identity's collector starts. A previous
    // missing row must never close the new recovered editor before that row is loaded.
    val matching = observed?.takeIf { it.account == notes.account && it.id == id }
    val note = matching?.note
    var lastCommitted by remember(notes, sessionKey) { mutableStateOf<Note?>(null) }
    LaunchedEffect(note) { note?.let { lastCommitted = it } }
    val current = note ?: lastCommitted
    val missing = matching != null && note == null
    LaunchedEffect(missing, current) {
        if (missing && current == null) {
            onClose()
            graph.scope.launch { snackbar.showSnackbar("This note is unavailable in the active account") }
        }
    }
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val memory: NativeEditorMemory = viewModel(factory = remember {
        viewModelFactory { initializer { NativeEditorMemory() } }
    })
    // A recovered ID changes persistence identity without replacing the native editing session.
    key(notes.account, sessionKey) { EditorContent(graph, notes, current, missing, query, focus, snackbar, attachments, memory, sessionKey, onReplace, onClose) }
}

/** File jobs survive a recovered-note ID replacement, but leave with their editor. */
private class AttachmentActions {
    var label by mutableStateOf<String?>(null)
        private set
    val busy: Boolean get() = label != null
    private var job: Job? = null

    fun start(scope: CoroutineScope, label: String, onError: suspend (Exception) -> Unit, action: suspend () -> Unit) {
        if (busy) return
        this.label = label
        job = scope.launch {
            val failure = try { action(); null }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { error }
            finally { this@AttachmentActions.label = null; job = null }
            failure?.let { onError(it) }
        }
    }

    fun cancel() { job?.cancel() }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
private fun EditorContent(graph: AppGraph, notes: NoteRepository, note: Note, missing: Boolean, query: String, focus: Boolean,
    snackbar: SnackbarHostState, attachments: AttachmentActions, memory: NativeEditorMemory, sessionKey: String, onReplace: (String) -> Unit, onClose: () -> Unit) {
    val firstHit = remember(note.id, query, focus) { if (focus) null else LiteralSearch.ranges(note.content, query).firstOrNull() }
    val retained = remember(notes, sessionKey) {
        memory.acquire(notes.account, sessionKey) {
            RetainedNativeEditor(notes.account, sessionKey,
                NativeSourceState(note, TextRange(firstHit?.start ?: note.selectionStart, firstHit?.end ?: note.selectionEnd)),
                EditorSession(notes, note, graph.scope), graph.scope)
        }
    }
    val state = retained.source
    val session = retained.session
    val lifecycleOwner = LocalLifecycleOwner.current
    val leave: () -> Unit = { memory.release(retained, null); onClose() }
    val saveStatus by session.status.collectAsStateWithLifecycle()
    LaunchedEffect(saveStatus.replacementId) { saveStatus.replacementId?.takeIf { it != note.id }?.let(onReplace) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var attachmentChoice by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var finalized by remember { mutableStateOf(false) }
    var display by remember { mutableStateOf(DisplayStyles(state.text, query, emptyList())) }
    fun snapshot() = state.snapshot()

    LaunchedEffect(state) {
        snapshotFlow { snapshot() to state.characterEpoch }.collect { (edit, epoch) -> session.submit(edit, epoch) }
    }
    LaunchedEffect(state, query) {
        snapshotFlow {
            Triple(state.text, state.viewport.min / 512 * 512, (state.viewport.max + 511) / 512 * 512)
        }.mapLatest { (input, start, end) ->
            withContext(Dispatchers.Default) { sourceStyles(input, query, start, end) }
        }.collect { display = it }
    }
    LaunchedEffect(state.viewport, firstHit) {
        if (!retained.initialMatchShown && firstHit != null && state.viewport.length > 0) {
            retained.initialMatchShown = true
            state.reveal(firstHit.start)
        }
    }
    LaunchedEffect(note, state.characterEpoch, state.composition, state.commandReady, saveStatus.receipt) {
        // Only an accepted clean native replacement advances the persistence origin.
        // An open native batch may publish characters for autosave while withholding commands.
        state.acceptIncoming(note, session)
    }
    LaunchedEffect(focus) {
        if (focus) state.focus()
    }
    val finishSnapshot by rememberUpdatedState<() -> EditSnapshot?>({
        if (finalized) null else snapshot().takeUnless { missing && it.content == note.content }
    })
    DisposableEffect(session) {
        onDispose {
            if ((lifecycleOwner as? android.app.Activity)?.isChangingConfigurations != true) {
                memory.release(retained, finishSnapshot())
            }
        }
    }
    LaunchedEffect(missing) {
        if (missing && snapshot().content == note.content) {
            finalized = true
            state.hideKeyboard()
            leave()
            graph.scope.launch { snackbar.showSnackbar("This note was removed. Its recovery version is available in Settings.") }
        }
    }
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                try {
                    val currentSnapshot = snapshot()
                    if (!missing || currentSnapshot.content != note.content) {
                        val saved = session.flush(currentSnapshot, state.characterEpoch)
                        notes.discardBlankDraft(saved.id)
                    }
                    state.hideKeyboard()
                    finalized = true
                    leave()
                } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    snackbar.showSnackbar(exception.message ?: "Unable to save. Your text is still here.")
                } finally { leaving = false }
            }
        }
    }
    BackHandler(onBack = close)

    val import: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            attachments.start(graph.scope, "Copying file…", { exception ->
                snackbar.showSnackbar(exception.message ?: "Unable to attach this file. Select it again.")
            }) {
                val saved = session.flush(snapshot(), state.characterEpoch)
                requireNotNull(graph.attachments) { "Attachments are unavailable" }.import(notes, saved.id, uri)
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), import)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), import)
    val colors = MaterialTheme.colorScheme
    val palette = SourcePalette(colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb(),
        colors.surfaceContainerHigh.toArgb(), colors.onTertiaryContainer.toArgb(), colors.tertiaryContainer.toArgb())
    LaunchedEffect(display, palette, query) {
        if (display.input === state.text && display.query == query) state.style(display, palette)
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
        TopAppBar(title = { Text("Note", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconAction(R.drawable.ic_back, "Back to notes", !leaving, close) }, actions = {
            IconAction(R.drawable.ic_more, "Note options") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Indent") }, leadingIcon = { AppIcon(R.drawable.ic_format_indent_increase) },
                    enabled = !leaving && state.commandReady && listIndent(state.text, state.selection, false) != null,
                    onClick = { menu = false; state.indent(false) })
                DropdownMenuItem(text = { Text("Outdent") }, leadingIcon = { AppIcon(R.drawable.ic_format_indent_decrease) },
                    enabled = !leaving && state.commandReady && listIndent(state.text, state.selection, true) != null,
                    onClick = { menu = false; state.indent(true) })
                DropdownMenuItem(text = { Text(if (note.state == NoteState.NORMAL) "Archive" else "Unarchive") }, onClick = {
                    menu = false
                    leaving = true
                    scope.launch {
                        try {
                            val saved = session.flush(snapshot(), state.characterEpoch)
                            notes.setState(saved.id, if (note.state == NoteState.NORMAL) NoteState.ARCHIVED else NoteState.NORMAL)
                            finalized = true
                            leave()
                        } catch (exception: Exception) {
                            if (exception is kotlinx.coroutines.CancellationException) throw exception
                            snackbar.showSnackbar(exception.message ?: "Unable to change note state")
                        } finally { leaving = false }
                    }
                })
                DropdownMenuItem(text = { Text("Recovery versions") }, onClick = { menu = false; history = true })
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menu = false
                    leaving = true
                    graph.scope.launch {
                        try {
                            val saved = session.flush(snapshot(), state.characterEpoch)
                            val deadline = notes.markDeleted(saved.id)
                            finalized = true
                            state.hideKeyboard(); leave()
                            showUndoUntil(snackbar, "Note deleted", deadline, onUndo = { notes.undoDelete(saved.id) })
                        } catch (exception: Exception) {
                            if (exception is kotlinx.coroutines.CancellationException) throw exception
                            snackbar.showSnackbar(exception.message ?: "Unable to delete this note")
                        } finally { leaving = false }
                    }
                })
            }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            NativeSourceField(state, enabled = !leaving,
                modifier = Modifier.weight(1f).fillMaxWidth())
            if (note.attachments.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 144.dp)) {
                items(note.attachments, key = { it.id }) { attachment ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !attachments.busy, onClick = {
                            attachments.start(graph.scope, "Opening ${attachment.filename}…", { exception ->
                                snackbar.showSnackbar(exception.message ?: "Unable to open this file. Try again.")
                            }) {
                                requireNotNull(graph.attachments) { "Attachments are unavailable" }.open(context, notes, attachment)
                            }
                        }, modifier = Modifier.weight(1f).semantics { contentDescription = "Open ${attachment.filename}" }) {
                            AppIcon(if (attachment.isImage) R.drawable.ic_image else R.drawable.ic_file)
                            Text(attachment.filename, Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconAction(R.drawable.ic_close, "Remove ${attachment.filename}", !attachments.busy) {
                            attachments.start(graph.scope, "Removing file…", { exception ->
                                snackbar.showSnackbar(exception.message ?: "Unable to remove this file. Try again.")
                            }) {
                                val saved = session.flush(snapshot(), state.characterEpoch)
                                val current = saved.attachments.firstOrNull { it.id == attachment.id }
                                    ?: saved.attachments.firstOrNull {
                                        it.filename == attachment.filename && it.mimeType == attachment.mimeType && it.size == attachment.size &&
                                            ((attachment.localPath != null && it.localPath == attachment.localPath) ||
                                                (attachment.remoteName != null && it.remoteName == attachment.remoteName))
                                    }
                                    ?: error("This file is no longer attached to the note.")
                                notes.removeAttachment(saved.id, current.id)
                                val deadline = notes.attachmentCleanup().firstOrNull { it.noteId == saved.id && it.attachment.id == current.id }?.deleteAfter
                                graph.scope.launch {
                                    try {
                                        if (deadline == null) snackbar.showSnackbar("Attachment removed. Recovery versions are available in Settings.")
                                        else showUndoUntil(snackbar, "Attachment removed", deadline,
                                            onUndo = { notes.undoRemoveAttachment(saved.id, current.id) },
                                            expiredMessage = "Undo expired. The file remains available in Recovery versions.")
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { snackbar.showSnackbar("Unable to restore this file. Its original remains available in Recovery versions.") }
                                }
                            }
                        }
                    }
                }
            }
            saveStatus.error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = colors.error)
                    TextButton(onClick = { scope.launch { runCatching { session.flush(snapshot(), state.characterEpoch) } } }) { Text("Retry save") }
                }
            }
            note.error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = colors.error, maxLines = 3)
                    TextButton(onClick = { history = true }) { Text("Recover") }
                }
            }
            if (attachments.busy) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(attachments.label.orEmpty(), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    TextButton(onClick = attachments::cancel, modifier = Modifier.semantics { contentDescription = "Cancel file operation" }) { Text("Cancel") }
                }
            }
            // Scaffold's imePadding owns the keyboard inset. This row shares the resized
            // content viewport and does not apply a second IME or navigation-bar inset.
            // A visible floating IME can report zero bottom inset. Visibility selects
            // the controls; Scaffold still owns any space needed by a docked IME.
            if (WindowInsets.isImeVisible) {
                EditorFormattingBar(state, enabled = !leaving,
                    canAttach = graph.attachments != null && !attachments.busy,
                    onAttach = { state.hideKeyboard(); attachmentChoice = true })
            } else Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_undo, "Undo edit", !leaving && state.canUndo) { state.undo() }
                    IconAction(R.drawable.ic_redo, "Redo edit", !leaving && state.canRedo) { state.redo() }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = graph.attachments != null && !attachments.busy && !leaving, onClick = {
                        state.hideKeyboard()
                        attachmentChoice = true
                    }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Attach" }) {
                        AppIcon(R.drawable.ic_attach)
                        Text("Attach", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        }
    }
    if (attachmentChoice) AttachmentChoiceSheet(onDismiss = { attachmentChoice = false },
        onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onFile = { filePicker.launch(arrayOf("*/*")) })
    if (history) RecoverySheet(notes, note, onDismiss = { history = false }, onRestore = { recovery ->
        scope.launch {
            val message = try {
                check(state.commandReady) { "Finish the current input before restoring this version." }
                leaving = true
                session.flush(snapshot(), state.characterEpoch)
                val restored = notes.restoreRecovery(recovery.noteId, recovery.id)
                if (restored.id == note.id) {
                    check(state.replace(restored.content, TextRange(restored.selectionStart.coerceAtMost(restored.content.length),
                        restored.selectionEnd.coerceAtMost(restored.content.length)))) { "Unable to replace the active input yet." }
                    session.acceptRemote(restored, state.characterEpoch)
                    state.observedContent = restored.content
                } else onReplace(restored.id)
                history = false
                "Version restored"
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                exception.message ?: "Unable to restore this version"
            } finally { leaving = false }
            snackbar.showSnackbar(message)
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentChoiceSheet(onDismiss: () -> Unit, onPhoto: () -> Unit, onFile: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    fun choose(action: () -> Unit) {
        if (choosing) return
        choosing = true
        scope.launch { sheet.hide(); onDismiss(); action() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Attach to note", Modifier.padding(bottom = 20.dp), style = MaterialTheme.typography.headlineSmall)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    ListItem(headlineContent = { Text("Photo") }, leadingContent = { AppIcon(R.drawable.ic_image) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !choosing, role = Role.Button) { choose(onPhoto) })
                    ListItem(headlineContent = { Text("File") }, leadingContent = { AppIcon(R.drawable.ic_file) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !choosing, role = Role.Button) { choose(onFile) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoverySheet(notes: NoteRepository, note: Note, onDismiss: () -> Unit, onRestore: (RecoveryRevision) -> Unit) {
    var versions by remember(notes, note.id) { mutableStateOf<List<RecoveryRevision>?>(null) }
    LaunchedEffect(notes, note.id) { versions = notes.recovery(note.id) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recovery versions", style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("recovery-list")
                .semantics { testTagsAsResourceId = true }) {
                val loaded = versions
                if (loaded == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else if (loaded.isEmpty()) item { Text("No recovery versions for this note.", Modifier.padding(bottom = 24.dp)) }
                else items(loaded, key = { it.id }) { recovery ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("recovery-${recovery.id}")
                        .semantics { testTagsAsResourceId = true }) {
                        Text(recovery.reason, style = MaterialTheme.typography.titleSmall)
                        Text(timestamp(recovery.savedAt), style = MaterialTheme.typography.labelSmall)
                        Text(recovery.snapshot.content.take(300), maxLines = 5, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRestore(recovery) }) { Text("Restore this version") }
                    }
                }
            }
        }
    }
}
