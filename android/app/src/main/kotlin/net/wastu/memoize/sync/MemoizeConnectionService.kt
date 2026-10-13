package net.wastu.memoize.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteAttachment
import java.io.File
import net.wastu.memoize.ui.AppGraph
import net.wastu.memoize.ui.ConnectionService
import net.wastu.memoize.ui.ConnectionSettings
import net.wastu.memoize.ui.SyncStatus
import java.util.concurrent.TimeUnit

/** Foreground and WorkManager use one active account and the same serialized durable sync engine. */
class MemoizeConnectionService(private val graph: AppGraph, private val allowLoopbackForTests: Boolean = false) : ConnectionService {
    private val context: Context = graph.context.applicationContext
    private val accounts = AccountStore(context, allowLoopbackForTests)
    private val ready = CompletableDeferred<Unit>()
    private val activation = Mutex()
    private val settingsFlow = MutableStateFlow(ConnectionSettings())
    private val statusFlow = MutableStateFlow(SyncStatus("Restoring account…", busy = true))
    override val settings: StateFlow<ConnectionSettings> = settingsFlow
    override val status: StateFlow<SyncStatus> = statusFlow
    private var engine: SyncEngine? = null
    private var activeApi: MemosApi? = null
    private var syncJob: Job? = null
    private var wakeJob: Job? = null
    private var repeatSync = false
    private var forceRequested = false

    init {
        graph.scope.launch {
            try {
                val config = accounts.configuration()
                if (config != null) {
                    val notes = graph.repository.value.forAccount(config.namespace)
                    notes.importLocalDrafts()
                    graph.repository.value = notes
                    settingsFlow.value = ConnectionSettings(config.server, config.user.removePrefix("users/"), connected = true)
                    val credentials = accounts.load()
                    if (credentials != null) {
                        activeApi = MemosClient(config.server, credentials.token, config.user, allowLoopbackForTests)
                        engine = SyncEngine(notes, requireNotNull(activeApi))
                        statusFlow.value = SyncStatus("Saved on this device")
                        registerBackgroundWork()
                    } else statusFlow.value = SyncStatus("Reconnect in Settings to sync. Notes remain on this device.", needsAuthentication = true)
                } else statusFlow.value = SyncStatus()
            } catch (_: Exception) {
                statusFlow.value = SyncStatus("Account could not be restored. Reconnect in Settings; saved notes remain on this device.", needsAuthentication = true)
            } finally { ready.complete(Unit) }
            trigger()
        }
        graph.scope.launch {
            ready.await()
            graph.repository.collectLatest { notes ->
                var previous: String? = null
                notes.changes.collect {
                    val pending = notes.pending()
                    val cleanup = notes.attachmentCleanup()
                    val fingerprint = pending.joinToString("|") { "${it.noteId}:${it.revision}:${it.kind}:${it.retryAt}" } +
                        cleanup.joinToString("|") { "${it.attachment.id}:${it.deleteAfter}:${it.retryAt}" }
                    if (previous != fingerprint && (pending.isNotEmpty() || cleanup.isNotEmpty())) trigger()
                    previous = fingerprint
                }
            }
        }
        (context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { graph.scope.launch { ready.await(); trigger() } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Namespace restoration only; it never waits for authentication or network. */
    suspend fun awaitRepository(): NoteRepository {
        ready.await()
        return graph.repository.value
    }

    /** On-demand downloads use current credentials without exposing tokens or cross-account transport. */
    suspend fun downloadAttachment(repository: NoteRepository, attachment: NoteAttachment, destination: File) {
        ready.await()
        activation.withLock {
            require(repository.account == graph.repository.value.account) { "This file belongs to a different account." }
            val api = activeApi ?: error("Connect in Settings to download this file. Saved originals remain available offline.")
            try { api.download(attachment, destination) }
            catch (error: MemosApiException) {
                if (error.needsAuthentication) {
                    repository.setMetadata("authenticationPaused", "true")
                    statusFlow.value = SyncStatus("Reconnect in Settings to download files. Notes remain on this device.", needsAuthentication = true)
                }
                throw error
            }
        }
    }

    override suspend fun connect(server: String, token: String) {
        ready.await()
        activation.withLock {
            statusFlow.value = SyncStatus("Connecting…", busy = true)
            try {
                val normalized = MemosClient.normalizedServer(server, allowLoopbackForTests).toASCIIString()
                val user = MemosClient(normalized, token, allowLoopbackForTests = allowLoopbackForTests).authenticate()
                val config = AccountConfiguration(normalized, user,
                    MemosClient.accountNamespace(MemosClient.normalizedServer(normalized, allowLoopbackForTests), user))
                syncJob?.cancel(); syncJob?.join(); wakeJob?.cancel()
                val notes = graph.repository.value.forAccount(config.namespace)
                // Only the never-configured local namespace is claimable, and the claim is transactional.
                accounts.save(AccountCredentials(config, token))
                notes.importLocalDrafts()
                graph.repository.value = notes
                activeApi = MemosClient(normalized, token, user, allowLoopbackForTests)
                engine = SyncEngine(notes, requireNotNull(activeApi))
                notes.setMetadata("authenticationPaused", "false")
                notes.setMetadata("retryAt", "")
                notes.retry()
                settingsFlow.value = ConnectionSettings(normalized, user.removePrefix("users/"), connected = true)
                statusFlow.value = SyncStatus("Connected. Saved on this device")
                registerBackgroundWork()
                trigger()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                statusFlow.value = SyncStatus(error.message ?: "Connection failed. Saved notes remain on this device.",
                    needsAuthentication = error is MemosApiException && error.needsAuthentication)
                throw error
            }
        }
    }

    override suspend fun retry() {
        ready.await()
        graph.repository.value.retry()
        graph.repository.value.setMetadata("retryAt", "")
        trigger(force = true)
    }

    suspend fun runBackground(): Boolean {
        ready.await()
        val active = engine ?: return true
        syncJob?.join()
        return synchronize(active, force = false)
    }

    private fun trigger(force: Boolean = false) {
        if (engine == null) return
        forceRequested = forceRequested || force
        wakeJob?.cancel()
        if (syncJob?.isActive == true) { repeatSync = true; return }
        syncJob = graph.scope.launch {
            do {
                repeatSync = false
                val active = engine ?: break
                val requested = forceRequested
                forceRequested = false
                synchronize(active, requested)
            } while (repeatSync)
        }
    }

    private suspend fun synchronize(active: SyncEngine, force: Boolean): Boolean = activation.withLock {
        if (active !== engine) return@withLock true
        val notes = graph.repository.value
        statusFlow.value = SyncStatus("Syncing…", busy = true)
        var completed = true
        try {
            active.synchronizeOnce(force)
            graph.refreshWidgets?.invoke()
            val paused = notes.metadata("authenticationPaused") == "true"
            val failures = notes.pending().count { it.error != null } + notes.attachmentCleanup().count { it.error != null }
            statusFlow.value = when {
                paused -> SyncStatus("Reconnect in Settings to sync. Saved notes remain on this device.", needsAuthentication = true)
                failures > 0 -> SyncStatus("$failures ${if (failures == 1) "item needs" else "items need"} attention. Saved on this device")
                notes.pending().isNotEmpty() -> SyncStatus("Waiting to sync")
                else -> SyncStatus("Up to date")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val auth = error is MemosApiException && error.needsAuthentication
            statusFlow.value = SyncStatus(if (auth) "Reconnect in Settings to sync. Saved notes remain on this device."
                else error.message ?: "Offline. Notes are saved on this device.", needsAuthentication = auth)
            completed = auth || (error is MemosApiException && !error.retryable)
        }
        val wake = active.nextWake()
        if (wake != null && notes.metadata("authenticationPaused") != "true") {
            val wait = maxOf(0, wake - System.currentTimeMillis())
            wakeJob?.cancel()
            wakeJob = graph.scope.launch { delay(wait); trigger() }
            scheduleBackgroundWork(wait)
        }
        completed
    }

    private fun registerBackgroundWork() {
        val periodic = PeriodicWorkRequestBuilder<MemoizeSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connectivity()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        scheduleBackgroundWork(0)
    }

    private fun scheduleBackgroundWork(delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<MemoizeSyncWorker>().setConstraints(connectivity())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(PENDING_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun connectivity(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        private const val PERIODIC_WORK = "memoize-periodic-sync"
        private const val PENDING_WORK = "memoize-pending-sync"
    }
}
