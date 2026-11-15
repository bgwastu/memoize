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
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, snackbar: SnackbarHostState, onRestored: (String) -> Unit, onClose: () -> Unit) {
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
    val updateStatus by (graph.updates?.status ?: remember { MutableStateFlow(UpdateStatus.Idle) }).collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (graph.updates != null && graph.updates?.status?.value is UpdateStatus.Idle) {
            runCatching { graph.updates?.check(context) }
        }
    }
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    LaunchedEffect(status.needsAuthentication) { if (status.needsAuthentication) editingConnection = true }
    val repository by graph.repository.collectAsStateWithLifecycle()
    var lastServer by remember { mutableStateOf(settings.server) }
    LaunchedEffect(settings.server) {
        if (server.text.toString() == lastServer) server.edit { replace(0, length, settings.server) }
        lastServer = settings.server
    }
    BackHandler(onBack = onClose)
    val headerScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().nestedScroll(headerScroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconAction(R.drawable.ic_back, "Back to notes", action = onClose) },
                scrollBehavior = headerScroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface)
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsHeading("Account")
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val name = if (settings.connected) settings.account.ifBlank { "Memos account" } else "Connect Memos"
                        Surface(shape = MaterialShapes.Cookie7Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                if (settings.connected) Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                                else AppIcon(R.drawable.ic_public)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (settings.connected) settings.server else "Sync your notes across devices.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
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
                            shape = MaterialTheme.shapes.medium
                        )
                        OutlinedSecureTextField(
                            state = token,
                            enabled = !connecting,
                            label = { Text("Access token") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
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
                                shapes = ButtonDefaults.shapes(),
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
                                shapes = ButtonDefaults.shapes(),
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
                }

                SettingsHeading("Maintenance")

                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    val currentStatus = updateStatus
                    SegmentedListItem(
                        onClick = {
                            scope.launch {
                                try {
                                    when (currentStatus) {
                                        is UpdateStatus.Available -> graph.updates?.downloadAndInstall(context)
                                        is UpdateStatus.ReadyToInstall -> graph.updates?.install(context)
                                        else -> {
                                            val result = graph.updates?.check(context)
                                            if (result != null) snackbar.showSnackbar(result)
                                        }
                                    }
                                } catch (exception: Exception) {
                                    if (exception is CancellationException) throw exception
                                    snackbar.showSnackbar(exception.message ?: "Update check failed")
                                }
                            }
                        },
                        enabled = currentStatus !is UpdateStatus.Checking && currentStatus !is UpdateStatus.Downloading && graph.updates != null,
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        supportingContent = {
                            when (currentStatus) {
                                is UpdateStatus.Available -> Text(
                                    "Tap to download and install update in-app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                is UpdateStatus.Downloading -> {
                                    LinearProgressIndicator(
                                        progress = { currentStatus.progress },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    )
                                }
                                is UpdateStatus.ReadyToInstall -> Text(
                                    "Downloaded. Tap to install now.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                is UpdateStatus.UpToDate -> Text(
                                    currentStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                is UpdateStatus.Error -> Text(
                                    currentStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                else -> Text(
                                    "Check for new releases on GitHub",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingContent = { AppIcon(R.drawable.ic_update) },
                        trailingContent = {
                            when (currentStatus) {
                                is UpdateStatus.Checking, is UpdateStatus.Downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                is UpdateStatus.ReadyToInstall -> Button(
                                    onClick = { graph.updates?.install(context) }
                                ) { Text("Install") }
                                is UpdateStatus.Available -> Button(
                                    onClick = {
                                        scope.launch {
                                            try { graph.updates?.downloadAndInstall(context) }
                                            catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                snackbar.showSnackbar(e.message ?: "Failed to download update")
                                            }
                                        }
                                    }
                                ) { Text("Update") }
                                else -> AppIcon(R.drawable.ic_chevron_right)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when (currentStatus) {
                                is UpdateStatus.Checking -> "Checking for updates…"
                                is UpdateStatus.Available -> "Update available: Memoize ${currentStatus.update.versionName}"
                                is UpdateStatus.Downloading -> "Downloading update: ${(currentStatus.progress * 100).toInt()}%"
                                is UpdateStatus.ReadyToInstall -> "Memoize ${currentStatus.update.versionName} ready"
                                else -> "Check for updates"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    SegmentedListItem(
                        onClick = { showRecovery = true },
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        supportingContent = {
                            Text(
                                "Restore conflicted or deleted notes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = { AppIcon(R.drawable.ic_history) },
                        trailingContent = { AppIcon(R.drawable.ic_chevron_right) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Recovery versions", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Text(
                    text = "Memoize ${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRecovery) {
        RecoveryBrowser(
            notes = repository,
            onDismiss = { showRecovery = false },
            onRestore = { version ->
                val restored = repository.restoreRecovery(version.noteId, version.id)
                showRecovery = false
                onRestored(restored.id)
                scope.launch { snackbar.showSnackbar("Version restored") }
            }
        )
    }
}

@Composable
private fun SettingsHeading(label: String) {
    Text(label, Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}
