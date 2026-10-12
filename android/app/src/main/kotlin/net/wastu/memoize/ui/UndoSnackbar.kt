package net.wastu.memoize.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/** The visible action uses the same deadline as the durable deletion transaction. */
suspend fun showUndoUntil(
    snackbar: SnackbarHostState,
    message: String,
    deadline: Long,
    onUndo: suspend () -> Boolean,
    expiredMessage: String = "Undo expired. Recovery versions are available in Settings.",
) {
    snackbar.currentSnackbarData?.dismiss()
    val remaining = deadline - System.currentTimeMillis()
    if (remaining <= 0) {
        snackbar.showSnackbar(expiredMessage)
        return
    }
    val result = withTimeoutOrNull(remaining) {
        snackbar.showSnackbar(message, "Undo", duration = SnackbarDuration.Indefinite)
    }
    if (result == SnackbarResult.ActionPerformed && !onUndo()) snackbar.showSnackbar(expiredMessage)
}
