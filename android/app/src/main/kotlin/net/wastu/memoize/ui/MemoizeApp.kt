package net.wastu.memoize.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExpandedFullScreenSearchBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
    val request by requests.collectAsStateWithLifecycle()
    var consumedRequest by rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val queryState = rememberTextFieldState()
    val query by remember { derivedStateOf { queryState.text.toString() } }
    var selectedId by rememberSaveable(notes.account) { mutableStateOf<String?>(null) }
    var editorKey by rememberSaveable(notes.account) { mutableStateOf<String?>(null) }
    var focusEditor by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentType by rememberSaveable { mutableStateOf(AttachmentFilter.ANY.name) }
    var lifecycle by rememberSaveable { mutableStateOf(NoteState.NORMAL.name) }
    var sortOrder by rememberSaveable { mutableStateOf(NoteSort.NEWEST.name) }
    var preferredColumns by rememberSaveable { mutableIntStateOf(0) }
    var gridCapacity by remember { mutableIntStateOf(1) }
    var displayedColumns by remember { mutableIntStateOf(1) }
    val listState = rememberLazyGridState()
    val searchListState = rememberLazyGridState()
    val searchState = rememberSearchBarState()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val activityFocus = LocalFocusManager.current
    val filter = NoteFilter(query, tag, AttachmentFilter.valueOf(attachmentType), NoteState.valueOf(lifecycle), NoteSort.valueOf(sortOrder))
    val resultFlow = remember(notes, filter) { notes.observe(filter) }
    val results by resultFlow.collectAsStateWithLifecycle(emptyList())
    var previousFilter by remember { mutableStateOf(filter) }

    LaunchedEffect(filter) {
        if (previousFilter != filter) {
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
                val draft = notes.create()
                selectedId = draft.id
                editorKey = draft.id
                focusEditor = true
                settings = false
            } else if (requestedNoteId != null) {
                if (notes.get(requestedNoteId) != null) {
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
        settings -> SettingsScreen(graph, snackbar) { settings = false }
        else -> {
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
                        }
                    },
                )
            }
            val open: (NoteResult) -> Unit = { result ->
                scope.launch {
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
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                topBar = {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                            // Intrinsic height keeps the headline and actions intact at larger text sizes.
                            Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                                .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Notes", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                                IconAction(R.drawable.ic_settings, "Settings") { settings = true }
                            }
                            if (syncStatus.needsAuthentication) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = reconnect) { Text("Reconnect") }
                            }
                            // The header owns the safe inset; search and its filter action stay together.
                            TopSearchBar(searchState, inputField = input, windowInsets = WindowInsets(0))
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = {
                    ExtendedFloatingActionButton(onClick = {
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
                        modifier = Modifier.semantics { contentDescription = "Add note" })
                },
            ) { padding ->
                if (searchState.currentValue == SearchBarValue.Collapsed)
                    NotesList(results, filter, listState, padding, open, clearSearch, resetFilters, chips,
                        preferredColumns, { preferredColumns = it }, { maximum, columns -> gridCapacity = maximum; displayedColumns = columns })
            }
            }
            ExpandedFullScreenSearchBar(searchState, inputField = input) {
                NotesList(results, filter, searchListState, PaddingValues(), open, clearSearch, resetFilters, chips,
                    preferredColumns, { preferredColumns = it }, { maximum, columns -> gridCapacity = maximum; displayedColumns = columns }, prefix = {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        if (syncStatus.needsAuthentication) TextButton(onClick = reconnect) { Text("Reconnect") }
                    }
                })
            }
        }
    }
    if (filters) FilterSheet(notes, filter, onDismiss = { filters = false }, onChange = {
        tag = it.tag; attachmentType = it.attachments.name; lifecycle = it.state.name; sortOrder = it.sort.name
    }, columnsContent = { NoteColumnChoices(displayedColumns, gridCapacity) { preferredColumns = it } })
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
private fun NotesList(results: List<NoteResult>, filter: NoteFilter, listState: LazyGridState,
    padding: PaddingValues, onOpen: (NoteResult) -> Unit, onClearSearch: () -> Unit, onResetFilters: () -> Unit,
    chips: @Composable () -> Unit, preferredColumns: Int, onColumnsChange: (Int) -> Unit,
    onLayout: (Int, Int) -> Unit, prefix: (@Composable () -> Unit)? = null) {
    val query = filter.query
    val filtered = filter.tag != null || filter.attachments != AttachmentFilter.ANY || filter.state != NoteState.NORMAL
    if (results.isEmpty()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val fontScale = LocalDensity.current.fontScale
            SideEffect { onLayout(noteGridCapacity(maxWidth.value, fontScale), noteGridColumns(preferredColumns, maxWidth.value, fontScale)) }
            // A body action cannot fit below 48 dp; the search field still owns Clear search.
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
                        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
        return
    }
    val direction = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value - padding.calculateLeftPadding(direction).value - padding.calculateRightPadding(direction).value
        val maximum = noteGridCapacity(width, fontScale)
        val columns = noteGridColumns(preferredColumns, width, fontScale)
        SideEffect { onLayout(maximum, columns) }
        LazyVerticalGrid(columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("notes-grid").noteGridPinch(columns, maximum, onColumnsChange)
                .semantics { testTagsAsResourceId = true; stateDescription = "$columns columns, maximum $maximum" }.consumeWindowInsets(padding),
            state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(
                start = padding.calculateLeftPadding(direction) + 16.dp, end = padding.calculateRightPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 104.dp)) {
            prefix?.let { item(key = "notes-actions", span = { GridItemSpan(maxLineSpan) }) { it() } }
            if (filtered) item(key = "active-filters", span = { GridItemSpan(maxLineSpan) }) { chips() }
            if (query.isNotEmpty() || filtered) item(key = "notes-context", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when { query.isNotEmpty() -> "Search results"; filter.state == NoteState.ARCHIVED -> "Archived notes"; else -> "Filtered notes" },
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(results.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(results, key = { result -> "note:${result.note.id}" }) { result ->
                val note = result.note
                OutlinedCard(onClick = { onOpen(result) }, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("note-${note.id}")
                        .semantics { if (query.isNotEmpty()) contentDescription = "${note.title}, ${result.matches.size} matches for $query" }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkdownPreviewText(note.title, LiteralSearch.ranges(note.title, query), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
                        if (result.excerpt.isNotBlank()) MarkdownPreviewText(result.excerpt, result.excerptMatches,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(timestamp(note.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (note.attachments.isNotEmpty()) Text("${note.attachments.size} ${if (note.attachments.size == 1) "attachment" else "attachments"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (note.error != null) Text("Needs attention", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

fun timestamp(time: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
