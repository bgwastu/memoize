package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconAction(R.drawable.ic_back, "Back to notes", action = onClose) })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (settings.connected) {
                            Text(settings.account, style = MaterialTheme.typography.titleMedium)
                            Text(settings.server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Connect Memos", style = MaterialTheme.typography.titleMedium)
                            Text("Your notes stay on this device. Connect to sync across devices.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (status.needsAuthentication) Text("Sign in again to resume sync.",
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (!settings.connected || editingConnection || status.needsAuthentication) {
                            OutlinedTextField(state = server, enabled = !connecting, label = { Text("Server URL") }, placeholder = { Text("https://memos.example.com") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                            OutlinedSecureTextField(state = token, enabled = !connecting, label = { Text("Access token") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                connecting = true; connectionError = null
                                val submittedServer = server.text.toString()
                                val submittedToken = token.text.toString()
                                scope.launch {
                                    try {
                                        requireNotNull(connection) { "Connection is unavailable" }.connect(submittedServer, submittedToken)
                                        token.edit { replace(0, length, "") }
                                        editingConnection = false
                                        snackbar.showSnackbar("Connected")
                                    } catch (exception: Exception) {
                                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                                        connectionError = exception.message ?: "Unable to connect"
                                    } finally { connecting = false }
                                }
                            }, enabled = !connecting && connection != null && server.text.isNotBlank() && token.text.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (connecting) "Connecting…" else if (settings.connected) "Reconnect" else "Connect")
                            }
                            if (editingConnection && !status.needsAuthentication && settings.connected)
                                TextButton(enabled = !connecting, onClick = { editingConnection = false; token.edit { replace(0, length, "") } }) { Text("Cancel") }
                        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(enabled = !status.busy && connection != null, onClick = {
                            connectionError = null
                                scope.launch {
                                try { connection?.retry(); connectionError = null }
                                    catch (exception: Exception) {
                                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                                        connectionError = exception.message ?: "Unable to sync"
                                    }
                                }
                            }) { AppIcon(R.drawable.ic_sync); Text("Sync now", Modifier.padding(start = 8.dp)) }
                            TextButton(enabled = !connecting, onClick = { editingConnection = true }) { Text("Change connection") }
                        }
                        connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                }
                Text("App", Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ListItem(headlineContent = { Text(if (updating) "Checking for updates…" else "Check for updates") },
                            leadingContent = { AppIcon(R.drawable.ic_update) },
                            trailingContent = { if (updating) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else AppIcon(R.drawable.ic_chevron_right) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !updating && graph.updates != null, role = Role.Button) {
                                updating = true; updateError = null
                                scope.launch {
                                    try { snackbar.showSnackbar(requireNotNull(graph.updates) { "Updates are unavailable" }.check(context)) }
                                    catch (exception: Exception) {
                                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                                        updateError = exception.message ?: "Unable to check for updates"
                                    } finally { updating = false }
                                }
                            })
                        updateError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                        ListItem(headlineContent = { Text("Recovery versions") },
                            supportingContent = { Text("Restore conflicted or deleted notes.") },
                            leadingContent = { AppIcon(R.drawable.ic_history) }, trailingContent = { AppIcon(R.drawable.ic_chevron_right) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { showRecovery = true })
                    }
                }
                Text("Memoize ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showRecovery) AccountRecoverySheet(repository, onDismiss = { showRecovery = false }, onRestored = {
        showRecovery = false
        scope.launch { snackbar.showSnackbar("Restored note. Open it from the notes list.") }
    })
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
            if (exception is kotlinx.coroutines.CancellationException) throw exception
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
                                    if (exception is kotlinx.coroutines.CancellationException) throw exception
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
