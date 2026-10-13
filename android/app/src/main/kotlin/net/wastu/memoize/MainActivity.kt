package net.wastu.memoize

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.flow.MutableStateFlow
import net.wastu.memoize.ui.MemoizeApp
import net.wastu.memoize.ui.MemoizeTheme

data class OpenRequest(val noteId: String? = null, val create: Boolean = false, val identity: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private val requests = MutableStateFlow(OpenRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) requests.value = request(intent)
        val graph = (application as MemoizeApplication).graph
        setContent {
            MemoizeTheme {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    MemoizeApp(graph, requests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requests.value = request(intent)
    }

    private fun request(intent: Intent): OpenRequest = OpenRequest(
        noteId = intent.getStringExtra(EXTRA_NOTE_ID),
        create = intent.action == ACTION_CREATE_NOTE || intent.getBooleanExtra(EXTRA_CREATE_NOTE, false),
    )

    companion object {
        const val ACTION_CREATE_NOTE = "net.wastu.memoize.CREATE_NOTE"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_CREATE_NOTE = "create_note"
    }
}
