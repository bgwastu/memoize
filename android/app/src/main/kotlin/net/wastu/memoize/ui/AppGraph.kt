package net.wastu.memoize.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.attachments.MemoizeAttachmentService
import net.wastu.memoize.sync.MemoizeConnectionService
import net.wastu.memoize.updates.GitHubUpdateService

data class ConnectionSettings(val server: String = "", val account: String = "", val connected: Boolean = false)
data class SyncStatus(val message: String = "On this device", val busy: Boolean = false, val needsAuthentication: Boolean = false)

interface ConnectionService {
    val settings: StateFlow<ConnectionSettings>
    val status: StateFlow<SyncStatus>
    suspend fun connect(server: String, token: String)
    suspend fun retry()
    suspend fun sync(force: Boolean = true): Boolean
}

interface AttachmentService {
    suspend fun import(repository: NoteRepository, noteId: String, uri: Uri): NoteAttachment
    suspend fun open(context: Context, repository: NoteRepository, attachment: NoteAttachment)
}

interface UpdateService {
    suspend fun check(context: Context): String
}

/** Process-wide dependencies. Adapters install themselves without exposing transport/SQL to UI. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = MutableStateFlow(NoteRepository.open(context))
    var connection: ConnectionService? = null
    var attachments: AttachmentService? = MemoizeAttachmentService(context) { repository, attachment, destination ->
        requireNotNull(connection as? MemoizeConnectionService) { "Connect in Settings to download this file. Saved originals remain available offline." }
            .downloadAttachment(repository, attachment, destination)
    }
    var updates: UpdateService? = GitHubUpdateService()
    var refreshWidgets: (suspend () -> Unit)? = null

    init { connection = MemoizeConnectionService(this) }

    suspend fun awaitRepository(): NoteRepository =
        (connection as? MemoizeConnectionService)?.awaitRepository() ?: repository.value
}
