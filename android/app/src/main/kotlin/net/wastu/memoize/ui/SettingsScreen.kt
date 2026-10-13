package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.wastu.memoize.BuildConfig
import net.wastu.memoize.R
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.RecoveryRevision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val connection = graph.connection
    val settings by (connection?.settings ?: remember { MutableStateFlow(ConnectionSettings()) }).collectAsStateWithLifecycle()
    val status by (connection?.status ?: remember { MutableStateFlow(SyncStatus()) }).collectAsStateWithLifecycle()
    val server = rememberTextFieldState(settings.server)
    // Do not persist an unsubmitted token in Activity saved state.
    val token = remember { TextFieldState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var connecting by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var showRecovery by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    LaunchedEffect(status.needsAuthentication) { if (status.needsAuthentication) editingConnection = true }
    val repository by graph.repository.collectAsStateWithLifecycle()
    var lastServer by remember { mutableStateOf(settings.server) }
    LaunchedEffect(settings.server) {
        if (server.text.toString() == lastServer) server.edit { replace(0, length, settings.server) }
        lastServer = settings.server
    }
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconAction(R.drawable.ic_back, "Back to notes", action = onClose) }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (settings.connected) {
                        Text(settings.account.ifBlank { "Memos account" },
                            style = MaterialTheme.typography.titleMedium)
                        Text(settings.server, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Connect Memos", style = MaterialTheme.typography.titleMedium)
                        Text("Sync your notes across devices.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (settings.connected && status.message.isNotBlank()) {
                        Text(status.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }

                    if (status.needsAuthentication) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Sign in again to resume sync.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }

                    if (!settings.connected || editingConnection || status.needsAuthentication) {
                        OutlinedTextField(
                            state = server,
                            enabled = !connecting,
                            label = { Text("Server URL") },
                            placeholder = { Text("https://memos.example.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedSecureTextField(
                            state = token,
                            enabled = !connecting,
                            label = { Text("Access token") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    connecting = true
                                    connectionError = null
                                    val submittedServer = server.text.toString()
                                    val submittedToken = token.text.toString()
                                    scope.launch {
                                        try {
                                            requireNotNull(connection) { "Connection is unavailable" }.connect(submittedServer, submittedToken)
                                            token.edit { replace(0, length, "") }
                                            editingConnection = false
                                            snackbar.showSnackbar("Connected")
                                        } catch (exception: Exception) {
                                            if (exception is CancellationException) throw exception
                                            connectionError = exception.message ?: "Unable to connect"
                                        } finally {
                                            connecting = false
                                        }
                                    }
                                },
                                enabled = !connecting && connection != null && server.text.isNotBlank() && token.text.isNotBlank(),
                                modifier = if (!settings.connected) Modifier.fillMaxWidth() else Modifier
                            ) {
                                if (connecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (connecting) "Connecting…" else if (settings.connected) "Reconnect" else "Connect")
                            }
                            if (editingConnection && !status.needsAuthentication && settings.connected) {
                                TextButton(
                                    enabled = !connecting,
                                    onClick = {
                                        editingConnection = false
                                        token.edit { replace(0, length, "") }
                                    }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                enabled = !status.busy && connection != null,
                                onClick = {
                                    connectionError = null
                                    scope.launch {
                                        try {
                                            connection?.retry()
                                            connectionError = null
                                        } catch (exception: Exception) {
                                            if (exception is CancellationException) throw exception
                                            connectionError = exception.message ?: "Unable to sync"
                                        }
                                    }
                                }
                            ) {
                                if (status.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (status.busy) "Syncing…" else "Sync now")
                            }
                            TextButton(
                                enabled = !connecting,
                                onClick = { editingConnection = true }
                            ) {
                                Text("Change connection")
                            }
                        }
                    }

                    connectionError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }

                Text(
                    text = "Maintenance",
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (updating) "Checking for updates…" else "Check for updates",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            supportingContent = {
                                Text(
                                    "Check for new releases on GitHub",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = { AppIcon(R.drawable.ic_update) },
                            trailingContent = {
                                if (updating) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    AppIcon(R.drawable.ic_chevron_right)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !updating && graph.updates != null, role = Role.Button) {
                                    updating = true
                                    updateError = null
                                    scope.launch {
                                        try {
                                            snackbar.showSnackbar(requireNotNull(graph.updates) { "Updates are unavailable" }.check(context))
                                        } catch (exception: Exception) {
                                            if (exception is CancellationException) throw exception
                                            updateError = exception.message ?: "Unable to check for updates"
                                        } finally {
                                            updating = false
                                        }
                                    }
                                }
                        )

                        updateError?.let { err ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        ListItem(
                            headlineContent = {
                                Text("Recovery versions", style = MaterialTheme.typography.bodyLarge)
                            },
                            supportingContent = {
                                Text(
                                    "Restore conflicted or deleted notes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = { AppIcon(R.drawable.ic_history) },
                            trailingContent = { AppIcon(R.drawable.ic_chevron_right) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { showRecovery = true }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Memoize ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showRecovery) {
        AccountRecoverySheet(
            notes = repository,
            onDismiss = { showRecovery = false },
            onRestored = {
                showRecovery = false
                scope.launch { snackbar.showSnackbar("Restored note. Open it from the notes list.") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountRecoverySheet(notes: NoteRepository, onDismiss: () -> Unit, onRestored: () -> Unit) {
    var versions by remember(notes) { mutableStateOf(emptyList<RecoveryRevision>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(notes) {
        try { versions = notes.recoveries() }
        catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            error = exception.message ?: "Unable to load recovery versions"
        } finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recovery versions", style = MaterialTheme.typography.headlineSmall)
            Text("Saved copies of conflicted or deleted notes.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) Text("Loading…")
            else if (versions.isEmpty()) Text("No recovery versions.", Modifier.padding(bottom = 24.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(versions, key = { it.id }) { version ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(version.reason, style = MaterialTheme.typography.titleSmall)
                        Text(timestamp(version.savedAt), style = MaterialTheme.typography.labelSmall)
                        Text(version.snapshot.content.take(300), maxLines = 5)
                        TextButton(enabled = !restoring, onClick = {
                            restoring = true; error = null
                            scope.launch {
                                try { notes.restoreRecovery(version.noteId, version.id); onRestored() }
                                catch (exception: Exception) {
                                    if (exception is CancellationException) throw exception
                                    error = exception.message ?: "Unable to restore this version"
                                } finally { restoring = false }
                            }
                        }) { Text("Restore this version") }
                    }
                }
            }
        }
    }
}
