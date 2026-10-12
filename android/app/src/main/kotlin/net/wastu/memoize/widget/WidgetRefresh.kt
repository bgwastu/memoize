package net.wastu.memoize.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.wastu.memoize.ui.AppGraph

/** App/worker adapter. Typing is coalesced; each worker can await a final committed refresh. */
object WidgetRefresh {
    private val updates = Mutex()

    suspend fun refresh(context: Context) = updates.withLock { MemoizeWidget().updateAll(context.applicationContext) }

    // flatMapLatest is a supported experimental Flow operation, not an alpha widget dependency.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun install(graph: AppGraph): Job = graph.scope.launch(Dispatchers.Default) {
        graph.awaitRepository()
        graph.repository.flatMapLatest { it.changes }.conflate().collect {
            delay(500)
            try { refresh(graph.context) }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) { Log.w("MemoizeWidget", "Could not refresh committed notes", failure) }
        }
    }
}
