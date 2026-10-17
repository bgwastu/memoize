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
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.flow.MutableStateFlow
import net.wastu.memoize.data.NoteAttachment
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import net.wastu.memoize.data.NoteLocation
import net.wastu.memoize.location.LocationService
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    var history by rememberSaveable(note.id) { mutableStateOf(false) }
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
    var locationDialogOpen by remember { mutableStateOf(false) }
    var pendingLocation by remember { mutableStateOf<NoteLocation?>(null) }
    val fetchAndOpenLocation: () -> Unit = {
        scope.launch {
            val loc = LocationService.getCurrentLocation(context)
            pendingLocation = loc ?: NoteLocation("", 0.0, 0.0)
            locationDialogOpen = true
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchAndOpenLocation()
        } else {
            pendingLocation = NoteLocation("", 0.0, 0.0)
            locationDialogOpen = true
        }
    }
    val requestLocation: () -> Unit = {
        if (LocationService.hasPermission(context)) {
            fetchAndOpenLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    val colors = MaterialTheme.colorScheme
    val palette = SourcePalette(colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb(),
        colors.surfaceContainerHigh.toArgb(), colors.onTertiaryContainer.toArgb(), colors.tertiaryContainer.toArgb(),
        colors.sourceSyntaxColor().toArgb(), colors.sourceSyntaxColor(active = true).toArgb())
    LaunchedEffect(display, palette, query) {
        if (display.input === state.text && display.query == query) state.style(display, palette)
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
        TopAppBar(
            title = {
                if (saveStatus.saving) {
                    Text(
                        text = "Saving…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            ),
            navigationIcon = { IconAction(R.drawable.ic_back, "Back to notes", !leaving, close) },
            actions = {
                IconAction(
                    R.drawable.ic_pin,
                    if (note.pinned) "Unpin note" else "Pin note",
                    enabled = !leaving
                ) {
                    scope.launch {
                        try {
                            val saved = session.flush(snapshot(), state.characterEpoch)
                            notes.setPinned(saved.id, !saved.pinned)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            snackbar.showSnackbar(e.message ?: "Unable to update pin status")
                        }
                    }
                }
                IconAction(R.drawable.ic_more, "Note options") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Edited ${timestamp(note.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {},
                        enabled = false
                    )
                    DropdownMenuItem(
                        text = { Text(if (note.pinned) "Unpin note" else "Pin to top") },
                        leadingIcon = { AppIcon(R.drawable.ic_pin) },
                        onClick = {
                            menu = false
                            scope.launch {
                                try {
                                    val saved = session.flush(snapshot(), state.characterEpoch)
                                    notes.setPinned(saved.id, !saved.pinned)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    snackbar.showSnackbar(e.message ?: "Unable to update pin")
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(when (note.visibility) {
                                net.wastu.memoize.data.NoteVisibility.PRIVATE -> "Visibility: Private"
                                net.wastu.memoize.data.NoteVisibility.PROTECTED -> "Visibility: Protected"
                                net.wastu.memoize.data.NoteVisibility.PUBLIC -> "Visibility: Public"
                            })
                        },
                        onClick = {
                            menu = false
                            val next = when (note.visibility) {
                                net.wastu.memoize.data.NoteVisibility.PRIVATE -> net.wastu.memoize.data.NoteVisibility.PROTECTED
                                net.wastu.memoize.data.NoteVisibility.PROTECTED -> net.wastu.memoize.data.NoteVisibility.PUBLIC
                                net.wastu.memoize.data.NoteVisibility.PUBLIC -> net.wastu.memoize.data.NoteVisibility.PRIVATE
                            }
                            scope.launch {
                                try {
                                    val saved = session.flush(snapshot(), state.characterEpoch)
                                    notes.setVisibility(saved.id, next)
                                    snackbar.showSnackbar("Visibility set to ${next.name.lowercase().replaceFirstChar { it.uppercase() }}")
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    snackbar.showSnackbar(e.message ?: "Unable to update visibility")
                                }
                            }
                        }
                    )
                    DropdownMenuItem(text = { Text(if (note.state == NoteState.NORMAL) "Archive" else "Unarchive") },
                        leadingIcon = { AppIcon(R.drawable.ic_archive) }, onClick = {
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
                    DropdownMenuItem(text = { Text("Recovery versions") },
                        leadingIcon = { AppIcon(R.drawable.ic_history) }, onClick = { menu = false; history = true })
                    DropdownMenuItem(text = { Text("Delete") },
                        leadingIcon = { AppIcon(R.drawable.ic_close) }, onClick = {
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
            note.location?.let { loc ->
                EditorLocationChip(
                    location = loc,
                    onOpen = { target ->
                        val geoUri = Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}(${Uri.encode(target.placeholder)})")
                        val intent = Intent(Intent.ACTION_VIEW, geoUri)
                        runCatching { context.startActivity(intent) }.onFailure {
                            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${target.latitude},${target.longitude}")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            try {
                                val saved = session.flush(snapshot(), state.characterEpoch)
                                notes.setLocation(saved.id, null)
                                snackbar.showSnackbar("Location removed")
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                snackbar.showSnackbar(e.message ?: "Unable to remove location")
                            }
                        }
                    }
                )
            }
            var previewImage by remember { mutableStateOf<NoteAttachment?>(null) }
            val serverConfig by (graph.connection?.settings ?: remember { MutableStateFlow(ConnectionSettings()) }).collectAsStateWithLifecycle()
            EditorAttachmentStrip(
                attachments = note.attachments,
                repository = notes,
                server = serverConfig.server,
                busy = attachments.busy,
                onOpen = { attachment ->
                    if (attachment.isImage) {
                        previewImage = attachment
                    } else {
                        attachments.start(graph.scope, "Opening ${attachment.filename}…", { exception ->
                            snackbar.showSnackbar(exception.message ?: "Unable to open this file. Try again.")
                        }) {
                            requireNotNull(graph.attachments) { "Attachments are unavailable" }.open(context, notes, attachment)
                        }
                    }
                },
                onRemove = { attachment ->
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
            )
            MediaLightboxDialog(
                attachment = previewImage,
                repository = notes,
                server = serverConfig.server,
                onDismiss = { previewImage = null }
            )
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
    if (attachmentChoice) AttachmentChoiceSheet(
        onDismiss = { attachmentChoice = false },
        onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        onFile = { filePicker.launch(arrayOf("*/*")) },
        onLocation = requestLocation
    )
    if (locationDialogOpen && pendingLocation != null) {
        LocationEditDialog(
            initial = pendingLocation!!,
            onDismiss = { locationDialogOpen = false; pendingLocation = null },
            onConfirm = { loc ->
                locationDialogOpen = false
                pendingLocation = null
                scope.launch {
                    try {
                        val saved = session.flush(snapshot(), state.characterEpoch)
                        notes.setLocation(saved.id, loc)
                        snackbar.showSnackbar("Location attached")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        snackbar.showSnackbar(e.message ?: "Unable to attach location")
                    }
                }
            }
        )
    }
    if (history) RecoveryBrowser(notes, noteId = note.id, onDismiss = { history = false }, onRestore = { recovery ->
        check(state.commandReady) { "Finish the current input before restoring this version." }
        leaving = true
        try {
            session.flush(snapshot(), state.characterEpoch)
            val restored = notes.restoreRecovery(recovery.noteId, recovery.id)
            if (restored.id == note.id) {
                check(state.replace(restored.content, TextRange(restored.selectionStart.coerceAtMost(restored.content.length),
                    restored.selectionEnd.coerceAtMost(restored.content.length)))) { "Unable to replace the active input yet." }
                session.acceptRemote(restored, state.characterEpoch)
                state.observedContent = restored.content
            } else onReplace(restored.id)
            history = false
            scope.launch { snackbar.showSnackbar("Version restored") }
        } finally { leaving = false }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentChoiceSheet(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
) {
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
                    ListItem(headlineContent = { Text("Photo or video") }, leadingContent = { AppIcon(R.drawable.ic_image) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !choosing, role = Role.Button) { choose(onPhoto) })
                    ListItem(headlineContent = { Text("Document or file") }, leadingContent = { AppIcon(R.drawable.ic_file) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !choosing, role = Role.Button) { choose(onFile) })
                    ListItem(headlineContent = { Text("Location") }, leadingContent = { AppIcon(R.drawable.ic_location) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !choosing, role = Role.Button) { choose(onLocation) })
                }
            }
        }
    }
}

@Composable
private fun LocationEditDialog(
    initial: NoteLocation,
    onDismiss: () -> Unit,
    onConfirm: (NoteLocation) -> Unit
) {
    var name by remember { mutableStateOf(initial.placeholder) }
    var latText by remember { mutableStateOf(if (initial.latitude != 0.0) initial.latitude.toString() else "") }
    var lngText by remember { mutableStateOf(if (initial.longitude != 0.0) initial.longitude.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Place name or label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lngText,
                        onValueChange = { lngText = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latText.toDoubleOrNull() ?: 0.0
                    val lng = lngText.toDoubleOrNull() ?: 0.0
                    val label = name.ifBlank { "%.4f, %.4f".format(java.util.Locale.US, lat, lng) }
                    onConfirm(NoteLocation(label, lat, lng))
                },
                enabled = name.isNotBlank() || (latText.toDoubleOrNull() != null && lngText.toDoubleOrNull() != null)
            ) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EditorLocationChip(
    location: NoteLocation,
    onOpen: (NoteLocation) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpen(location) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_location, "Location")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = location.placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                AppIcon(R.drawable.ic_close, "Remove location")
            }
        }
    }
}
