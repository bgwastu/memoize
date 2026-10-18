package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopSearchBar
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.wastu.memoize.OpenRequest
import net.wastu.memoize.R
import net.wastu.memoize.data.AttachmentFilter
import net.wastu.memoize.data.LiteralSearch
import net.wastu.memoize.data.NoteFilter
import net.wastu.memoize.data.NoteSort
import net.wastu.memoize.data.NoteResult
import net.wastu.memoize.data.NoteState
import net.wastu.memoize.data.NoteVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import net.wastu.memoize.data.Note
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoizeApp(graph: AppGraph, requests: StateFlow<OpenRequest>) {
    var ready by remember(graph) { mutableStateOf(false) }
    LaunchedEffect(graph) {
        graph.awaitRepository(); ready = true
    }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val notes by graph.repository.collectAsStateWithLifecycle()
    val syncStatus by (graph.connection?.status ?: remember { MutableStateFlow(SyncStatus()) }).collectAsStateWithLifecycle()
    val syncSettings by (graph.connection?.settings ?: remember { MutableStateFlow(ConnectionSettings()) }).collectAsStateWithLifecycle()
    var manualRefreshing by remember { mutableStateOf(false) }
    val isRefreshing = manualRefreshing || syncStatus.busy
    var previewImage by remember { mutableStateOf<NoteAttachment?>(null) }
    val request by requests.collectAsStateWithLifecycle()
    var consumedRequest by rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val queryState = rememberTextFieldState()
    val query by remember { derivedStateOf { queryState.text.toString() } }
    var selectedId by rememberSaveable(notes.account) { mutableStateOf<String?>(null) }
    var editorKey by rememberSaveable(notes.account) { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(notes.account) { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable(notes.account) { mutableStateOf(false) }
    var batchBusy by remember { mutableStateOf(false) }
    var focusEditor by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentType by rememberSaveable { mutableStateOf(AttachmentFilter.ANY.name) }
    var lifecycle by rememberSaveable { mutableStateOf(NoteState.NORMAL.name) }
    var sortOrder by rememberSaveable { mutableStateOf(NoteSort.NEWEST.name) }
    val context = LocalContext.current
    var preferredColumns by rememberSaveable { mutableIntStateOf(loadPreferredColumns(context)) }
    val updateColumns: (Int) -> Unit = { count ->
        preferredColumns = count
        savePreferredColumns(context, count)
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val initialCapacity = remember(configuration.screenWidthDp, density.fontScale) {
        noteGridCapacity(configuration.screenWidthDp.toFloat(), density.fontScale)
    }
    val initialColumns = remember(preferredColumns, configuration.screenWidthDp, density.fontScale) {
        noteGridColumns(preferredColumns, configuration.screenWidthDp.toFloat(), density.fontScale)
    }
    var gridCapacity by remember { mutableIntStateOf(initialCapacity) }
    var displayedColumns by remember { mutableIntStateOf(initialColumns) }
    val listState = rememberLazyStaggeredGridState()
    val searchListState = rememberLazyStaggeredGridState()
    var expandedCreateButton by remember { mutableStateOf(true) }
    val createButtonScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y < -1f) expandedCreateButton = false
                    else if (available.y > 1f) expandedCreateButton = true
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState.canScrollBackward) {
        if (!listState.canScrollBackward) expandedCreateButton = true
    }
    val searchState = rememberSearchBarState()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val activityFocus = LocalFocusManager.current
    val filter = NoteFilter(query, tag, AttachmentFilter.valueOf(attachmentType), NoteState.valueOf(lifecycle), NoteSort.valueOf(sortOrder))
    val resultFlow: kotlinx.coroutines.flow.Flow<List<NoteResult>?> = remember(notes, filter) { notes.observe(filter) }
    val loadedResults by resultFlow.collectAsStateWithLifecycle(null)
    val results = loadedResults.orEmpty()
    val appBarState = rememberTopAppBarState()
    val headerScroll = TopAppBarDefaults.pinnedScrollBehavior(appBarState)
    LaunchedEffect(listState.canScrollBackward) {
        if (!listState.canScrollBackward) appBarState.heightOffset = 0f
    }
    LaunchedEffect(selecting) {
        if (selecting) { appBarState.heightOffset = 0f; activityFocus.clearFocus(); keyboard?.hide() }
    }
    LaunchedEffect(loadedResults) {
        if (loadedResults != null && selection.isNotEmpty()) {
            val present = results.map { it.note.id }.toSet()
            selection = selection.filter { it in present }
            if (selection.isEmpty()) selecting = false
        }
    }
    var previousFilter by remember { mutableStateOf(filter) }

    LaunchedEffect(filter) {
        if (previousFilter != filter) {
            if (previousFilter.copy(sort = filter.sort) != filter) {
                selection = emptyList(); selecting = false
            }
            appBarState.heightOffset = 0f
            previousFilter = filter
            listState.scrollToItem(0)
            searchListState.scrollToItem(0)
        }
    }

    LaunchedEffect(request.identity, notes.account) {
        if (consumedRequest == request.identity) return@LaunchedEffect
        consumedRequest = request.identity
        val requestedNoteId = request.noteId
        try {
            if (request.create) {
                selecting = false; selection = emptyList()
                val draft = notes.create()
                searchState.animateToCollapsed()
                selectedId = draft.id
                editorKey = draft.id
                focusEditor = true
                settings = false
            } else if (requestedNoteId != null) {
                selecting = false; selection = emptyList()
                if (notes.get(requestedNoteId) != null) {
                    searchState.animateToCollapsed()
                    if (selectedId != requestedNoteId) editorKey = requestedNoteId
                    selectedId = requestedNoteId
                    focusEditor = false
                    settings = false
                } else snackbar.showSnackbar("This note is unavailable in the active account")
            }
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            snackbar.showSnackbar(exception.message ?: "Unable to open this note")
        }
    }

    when {
        selectedId != null -> NoteEditor(graph, notes, selectedId!!, query, focusEditor,
            sessionKey = editorKey ?: selectedId!!,
            onReplace = { replacement -> selectedId = replacement
                scope.launch { snackbar.showSnackbar("Server removed the note. Your edit was saved as a new private note.") } },
            snackbar = snackbar, onClose = { selectedId = null; editorKey = null; focusEditor = false })
        settings -> SettingsScreen(graph, snackbar, onRestored = { id ->
            settings = false; selectedId = id; editorKey = id; focusEditor = false
        }, onClose = { settings = false })
        else -> {
            var listMenu by remember { mutableStateOf(false) }
            fun exitSelection() { selection = emptyList(); selecting = false }
            fun toggle(noteId: String) {
                if (batchBusy) return
                selection = if (noteId in selection) selection - noteId else selection + noteId
                if (selection.isEmpty()) selecting = false
            }
            fun beginSelection(result: NoteResult? = null) {
                if (batchBusy) return
                activityFocus.clearFocus(); keyboard?.hide()
                val expanded = searchState.currentValue == SearchBarValue.Expanded || searchState.targetValue == SearchBarValue.Expanded
                val index = searchListState.firstVisibleItemIndex
                val offset = searchListState.firstVisibleItemScrollOffset
                scope.launch {
                    // Fullscreen search measures its collapsed anchor during the animation.
                    // Keep that header attached until collapse completes, as note opening does.
                    if (expanded) searchState.animateToCollapsed()
                    if (result != null && result.note.id !in selection) selection += result.note.id
                    selecting = true
                    if (expanded) listState.scrollToItem(index, offset)
                }
            }
            fun changeSelection(delete: Boolean) {
                if (batchBusy || selection.isEmpty()) return
                val ids = selection.toSet()
                val repository = notes
                val archived = filter.state == NoteState.ARCHIVED
                batchBusy = true
                scope.launch {
                    try {
                        if (delete) {
                            val deadline = repository.markDeleted(ids)
                            exitSelection(); batchBusy = false
                            showUndoUntil(snackbar, "${ids.size} ${if (ids.size == 1) "note" else "notes"} deleted", deadline,
                                onUndo = { repository.undoDelete(ids, deadline) })
                        } else {
                            repository.setState(ids, if (archived) NoteState.NORMAL else NoteState.ARCHIVED)
                            exitSelection(); batchBusy = false
                            snackbar.showSnackbar("${ids.size} ${if (ids.size == 1) "note" else "notes"} ${if (archived) "unarchived" else "archived"}")
                        }
                    } catch (exception: Exception) {
                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                        batchBusy = false
                        snackbar.showSnackbar(exception.message ?: "Unable to update selected notes")
                    } finally { batchBusy = false }
                }
            }
            fun shareSelection() {
                if (batchBusy || selection.isEmpty()) return
                val repository = notes
                val ids = results.filter { it.note.id in selection }.map { it.note.id }
                batchBusy = true
                scope.launch {
                    try {
                        val selected = ids.map { requireNotNull(repository.get(it)) { "A selected note is no longer available." } }
                        exitSelection(); batchBusy = false
                        shareSelectedNotes(context, selected)
                    } catch (exception: Exception) {
                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                        snackbar.showSnackbar(exception.message ?: "Unable to share selected notes")
                    } finally { batchBusy = false }
                }
            }
            val selectedNotes = results.filter { it.note.id in selection }
            val allPinned = selectedNotes.isNotEmpty() && selectedNotes.all { it.note.pinned }
            fun toggleSelectionPin() {
                if (batchBusy || selection.isEmpty()) return
                val ids = selection.toSet()
                val repository = notes
                val targetPinned = !allPinned
                batchBusy = true
                scope.launch {
                    try {
                        for (id in ids) {
                            repository.setPinned(id, targetPinned)
                        }
                        exitSelection(); batchBusy = false
                        snackbar.showSnackbar("${ids.size} ${if (ids.size == 1) "note" else "notes"} ${if (targetPinned) "pinned" else "unpinned"}")
                    } catch (exception: Exception) {
                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                        snackbar.showSnackbar(exception.message ?: "Unable to update pinned notes")
                    } finally {
                        batchBusy = false
                    }
                }
            }
            BackHandler(selecting) { if (!batchBusy) exitSelection() }
            val input: @Composable () -> Unit = {
                val fieldFocus = LocalFocusManager.current
                SearchBarDefaults.InputField(
                    modifier = Modifier.testTag("notes-search").semantics { testTagsAsResourceId = true },
                    textFieldState = queryState, searchBarState = searchState,
                    onSearch = {
                        fieldFocus.clearFocus(); activityFocus.clearFocus(); keyboard?.hide()
                        scope.launch { searchState.animateToCollapsed() }
                    }, placeholder = { Text("Search notes") },
                    leadingIcon = {
                        if (searchState.currentValue == SearchBarValue.Expanded) IconAction(R.drawable.ic_back, "Close search") { fieldFocus.clearFocus(); activityFocus.clearFocus(); keyboard?.hide(); scope.launch { searchState.animateToCollapsed() } }
                        else AppIcon(R.drawable.ic_search, "Search notes")
                    },
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) IconAction(R.drawable.ic_close, "Clear search") { queryState.edit { replace(0, length, "") } }
                            IconAction(R.drawable.ic_filter, "Sort and filter") {
                                fieldFocus.clearFocus(); activityFocus.clearFocus(); keyboard?.hide()
                                scope.launch { searchState.animateToCollapsed(); filters = true }
                            }
                            Box {
                                IconAction(R.drawable.ic_more, "Note list options") { listMenu = true }
                                DropdownMenu(
                                    expanded = listMenu,
                                    onDismissRequest = { listMenu = false },
                                    modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { AppIcon(R.drawable.ic_settings) },
                                        onClick = { listMenu = false; settings = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Select notes") },
                                        leadingIcon = { AppIcon(R.drawable.ic_checklist) },
                                        onClick = { listMenu = false; beginSelection() }
                                    )
                                }
                            }
                        }
                    },
                )
            }
            val open: (NoteResult) -> Unit = { result ->
                if (selecting) toggle(result.note.id) else scope.launch {
                    searchState.animateToCollapsed()
                    selectedId = result.note.id
                    editorKey = result.note.id
                    focusEditor = false
                }
            }
            val chips: @Composable () -> Unit = {
                FilterChips(tag, AttachmentFilter.valueOf(attachmentType), NoteState.valueOf(lifecycle),
                    onClearTag = { tag = null }, onClearAttachments = { attachmentType = AttachmentFilter.ANY.name },
                    onClearState = { lifecycle = NoteState.NORMAL.name })
            }
            val clearSearch: () -> Unit = { queryState.edit { replace(0, length, "") } }
            val reconnect: () -> Unit = {
                activityFocus.clearFocus(); keyboard?.hide()
                scope.launch { searchState.animateToCollapsed(); settings = true }
            }
            val resetFilters: () -> Unit = {
                tag = null; attachmentType = AttachmentFilter.ANY.name; lifecycle = NoteState.NORMAL.name
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Scaffold(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().nestedScroll(createButtonScroll).nestedScroll(headerScroll.nestedScrollConnection),
                topBar = {
                    if (selecting) NoteSelectionBar(
                        count = selection.size,
                        busy = batchBusy,
                        archived = filter.state == NoteState.ARCHIVED,
                        pinned = allPinned,
                        onClose = ::exitSelection,
                        onArchive = { changeSelection(false) },
                        onDelete = { changeSelection(true) },
                        onShare = ::shareSelection,
                        onSelectAll = { selection = results.map { it.note.id } },
                        onClear = ::exitSelection,
                        onFilters = { filters = true },
                        onTogglePin = ::toggleSelectionPin
                    )
                    else TopAppBar(title = {
                        // Mirror the app bar's title inset at the trailing edge.
                        TopSearchBar(searchState, inputField = input, modifier = Modifier.padding(end = 16.dp),
                            windowInsets = WindowInsets(0))
                    }, scrollBehavior = headerScroll,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface))
                },
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = {
                    if (!selecting) ExtendedFloatingActionButton(onClick = {
                        scope.launch {
                            try {
                                val draft = notes.create()
                                selectedId = draft.id
                                editorKey = draft.id
                                focusEditor = true
                            } catch (exception: Exception) {
                                if (exception is kotlinx.coroutines.CancellationException) throw exception
                                snackbar.showSnackbar(exception.message ?: "Unable to create a note")
                            }
                        }
                    }, icon = { AppIcon(R.drawable.ic_add) }, text = { Text("New note") },
                        expanded = expandedCreateButton,
                        modifier = Modifier.semantics { contentDescription = "Add note" })
                },
            ) { padding ->
                if (searchState.currentValue == SearchBarValue.Collapsed)
                    NotesList(
                        results, filter, listState, padding, open, clearSearch, resetFilters, chips,
                        preferredColumns, updateColumns, { maximum, columns -> gridCapacity = maximum; displayedColumns = columns },
                        selection = selection.toSet(), selecting = selecting, enabled = !batchBusy, onSelect = ::beginSelection,
                        prefix = { if (syncStatus.needsAuthentication && !selecting) TextButton(onClick = reconnect) { Text("Reconnect") } },
                        repository = notes,
                        server = syncSettings.server,
                        onImageClick = { previewImage = it },
                        onFileClick = { file ->
                            scope.launch {
                                try { requireNotNull(graph.attachments) { "Attachments are unavailable" }.open(context, notes, file) }
                                catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Unable to open file") }
                            }
                        },
                        onTagClick = { tag = it },
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            scope.launch {
                                manualRefreshing = true
                                try { graph.connection?.sync(force = true) } finally { manualRefreshing = false }
                            }
                        }
                    )
            }
            MediaLightboxDialog(
                attachment = previewImage,
                repository = notes,
                server = syncSettings.server,
                onDismiss = { previewImage = null }
            )
            }
            ExpandedFullScreenSearchBar(searchState, inputField = input) {
                NotesList(
                    results, filter, searchListState, PaddingValues(), open, clearSearch, resetFilters, chips,
                    preferredColumns, updateColumns, { maximum, columns -> gridCapacity = maximum; displayedColumns = columns },
                    selection = selection.toSet(), selecting = selecting, enabled = !batchBusy, onSelect = ::beginSelection,
                    prefix = {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                            if (syncStatus.needsAuthentication && !selecting) TextButton(onClick = reconnect) { Text("Reconnect") }
                        }
                    },
                    repository = notes,
                    server = syncSettings.server,
                    onImageClick = { previewImage = it },
                    onFileClick = { file ->
                        scope.launch {
                            try { requireNotNull(graph.attachments) { "Attachments are unavailable" }.open(context, notes, file) }
                            catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Unable to open file") }
                        }
                    },
                    onTagClick = { tag = it; scope.launch { searchState.animateToCollapsed() } },
                    isRefreshing = false,
                    onRefresh = {}
                )
            }
        }
    }
    if (filters) FilterSheet(notes, filter, onDismiss = { filters = false }, onChange = {
        tag = it.tag; attachmentType = it.attachments.name; lifecycle = it.state.name; sortOrder = it.sort.name
    }, columnsContent = { NoteColumnChoices(displayedColumns, gridCapacity, updateColumns) })
}

@Composable
private fun FilterChips(tag: String?, attachments: AttachmentFilter, state: NoteState,
    onClearTag: () -> Unit, onClearAttachments: () -> Unit, onClearState: () -> Unit) {
    if (tag == null && attachments == AttachmentFilter.ANY && state == NoteState.NORMAL) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tag?.let { item { CompactFilterChip("#$tag", "Remove tag filter", onClearTag) } }
        if (attachments != AttachmentFilter.ANY) item {
            CompactFilterChip(if (attachments == AttachmentFilter.IMAGES) "Images" else "Files", "Remove attachment filter", onClearAttachments)
        }
        if (state == NoteState.ARCHIVED) item { CompactFilterChip("Archived", "Show active notes", onClearState) }
    }
}

@Composable
private fun CompactFilterChip(label: String, removeDescription: String, onClear: () -> Unit) {
    InputChip(selected = true, onClick = onClear,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_close), removeDescription, Modifier.size(16.dp)) },
        modifier = Modifier.minimumInteractiveComponentSize())
}

@Composable
private fun NotesList(
    results: List<NoteResult>,
    filter: NoteFilter,
    listState: LazyStaggeredGridState,
    padding: PaddingValues,
    onOpen: (NoteResult) -> Unit,
    onClearSearch: () -> Unit,
    onResetFilters: () -> Unit,
    chips: @Composable () -> Unit,
    preferredColumns: Int,
    onColumnsChange: (Int) -> Unit,
    onLayout: (Int, Int) -> Unit,
    selection: Set<String>,
    selecting: Boolean,
    enabled: Boolean,
    onSelect: (NoteResult) -> Unit,
    prefix: (@Composable () -> Unit)? = null,
    repository: NoteRepository,
    server: String?,
    onImageClick: (NoteAttachment) -> Unit,
    onFileClick: (NoteAttachment) -> Unit,
    onTagClick: (String) -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val query = filter.query
    val filtered = filter.tag != null || filter.attachments != AttachmentFilter.ANY || filter.state != NoteState.NORMAL

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        if (results.isEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                val fontScale = LocalDensity.current.fontScale
                SideEffect { onLayout(noteGridCapacity(maxWidth.value, fontScale), noteGridColumns(preferredColumns, maxWidth.value, fontScale)) }
                val compactSearch = query.isNotEmpty() && maxHeight < 48.dp
                Column(Modifier.fillMaxSize().testTag("notes-empty").semantics { testTagsAsResourceId = true }
                    .verticalScroll(rememberScrollState())) {
                    if (compactSearch) {
                        Text("No matches", Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        prefix?.invoke()
                        chips()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp).padding(bottom = 88.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Box(Modifier.padding(20.dp)) { AppIcon(if (query.isNotEmpty()) R.drawable.ic_search else R.drawable.ic_file) }
                            }
                            Text(when { query.isNotEmpty() -> "No matching notes"; filtered -> "No notes match these filters"; else -> "No notes yet" },
                                modifier = Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineSmall)
                            Text(when { query.isNotEmpty() -> "Try a different phrase."; filtered -> "Change or clear your filters."; else -> "Start with New note." },
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotEmpty()) TextButton(onClick = onClearSearch) { Text("Clear search") }
                            else if (filtered) TextButton(onClick = onResetFilters) { Text("Reset filters") }
                        }
                    }
                }
            }
            return@PullToRefreshBox
        }

        val direction = LocalLayoutDirection.current
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value - padding.calculateLeftPadding(direction).value - padding.calculateRightPadding(direction).value
            val maximum = noteGridCapacity(width, fontScale)
            val columns = noteGridColumns(preferredColumns, width, fontScale)
            SideEffect { onLayout(maximum, columns) }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("notes-grid").noteGridPinch(columns, maximum, onColumnsChange)
                    .semantics { testTagsAsResourceId = true; stateDescription = "$columns columns, maximum $maximum" }.consumeWindowInsets(padding),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
                contentPadding = PaddingValues(
                    start = padding.calculateLeftPadding(direction) + 16.dp, end = padding.calculateRightPadding(direction) + 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 104.dp
                )
            ) {
                prefix?.let { action -> item(key = "notes-actions", span = StaggeredGridItemSpan.FullLine) { action() } }
                if (filtered) item(key = "active-filters", span = StaggeredGridItemSpan.FullLine) { chips() }
                if (query.isNotEmpty() || filtered) item(key = "notes-context", span = StaggeredGridItemSpan.FullLine) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when { query.isNotEmpty() -> "Search results"; filter.state == NoteState.ARCHIVED -> "Archived notes"; else -> "Filtered notes" },
                            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(results.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(results, key = { result -> "note:${result.note.id}" }) { result ->
                    val note = result.note
                    val checked = note.id in selection
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        border = BorderStroke(
                            if (checked) 2.dp else 1.dp,
                            if (checked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("note-${note.id}")
                            .combinedClickable(
                                enabled = enabled,
                                role = Role.Button,
                                onClick = { onOpen(result) },
                                onLongClickLabel = "Select note",
                                onLongClick = { onSelect(result) }
                            )
                            .semantics {
                                selected = checked
                                if (selecting) stateDescription = if (checked) "Selected" else "Not selected"
                            }
                            .semantics {
                                if (query.isNotEmpty()) contentDescription = "${note.title}, ${result.matches.size} matches for $query"
                            }
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    val showHeader = note.pinned || note.visibility == NoteVisibility.PUBLIC
                                    if (showHeader) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        ) {
                                            if (note.pinned) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_pin),
                                                        contentDescription = "Pinned",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        "Pinned",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            if (note.visibility == NoteVisibility.PUBLIC) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_public),
                                                            contentDescription = "Public note",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                        Spacer(Modifier.width(3.dp))
                                                        Text(
                                                            "Public",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                    }
                                    }
                                    val previewContent = if (query.isEmpty()) note.content.trim() else result.excerpt
                                    val previewMatches = if (query.isEmpty()) emptyList() else result.excerptMatches
                                    val sourceOffset = if (query.isEmpty()) note.content.indexOf(previewContent).coerceAtLeast(0) else result.excerptOffset
                                    if (previewContent.isNotBlank()) {
                                        Box(Modifier.fillMaxWidth().clipToBounds()) {
                                            MarkdownPreviewText(
                                                previewContent,
                                                previewMatches,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 8,
                                                fullSource = note.content,
                                                sourceOffset = sourceOffset
                                            )
                                        }
                                    }
                                }
                                if (selecting) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(24.dp).clearAndSetSemantics {}
                                    )
                                }
                            }

                            if (note.attachments.isNotEmpty()) {
                                NoteMediaGrid(
                                    attachments = note.attachments,
                                    repository = repository,
                                    server = server,
                                    onImageClick = onImageClick,
                                    onFileClick = onFileClick
                                )
                            }

                            if (note.tags.isNotEmpty()) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (t in note.tags) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                            modifier = Modifier.clickable { onTagClick(t) }
                                        ) {
                                            Text(
                                                text = "#$t",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        timestamp(note.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    note.location?.let { loc ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_location),
                                                contentDescription = "Location",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                loc.placeholder,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (note.error != null) {
                                        Text(
                                            "Needs attention",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall
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

fun timestamp(time: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
