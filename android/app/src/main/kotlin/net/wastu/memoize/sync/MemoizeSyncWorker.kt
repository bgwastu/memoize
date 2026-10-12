package net.wastu.memoize.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.wastu.memoize.MemoizeApplication

class MemoizeSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as MemoizeApplication
        val connection = application.graph.connection as? MemoizeConnectionService ?: return Result.success()
        return if (connection.runBackground()) Result.success() else Result.retry()
    }
}
