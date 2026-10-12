package net.wastu.memoize.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.wastu.memoize.data.EditSnapshot

/** One logical editing session survives a new Activity; native Views never survive with it. */
internal class NativeEditorMemory : ViewModel() {
    private var active: RetainedNativeEditor? = null
    fun acquire(account: String, key: String, create: () -> RetainedNativeEditor): RetainedNativeEditor {
        active?.takeIf { it.account == account && it.key == key }?.let { return it }
        active?.finish(active?.source?.snapshot())
        return create().also { active = it }
    }
    fun release(editor: RetainedNativeEditor, snapshot: EditSnapshot?) {
        if (active === editor) active = null
        editor.finish(snapshot)
    }
    override fun onCleared() {
        active?.let { it.finish(it.source.snapshot()) }
        active = null
    }
}

internal class RetainedNativeEditor(val account: String, val key: String, val source: NativeSourceState,
    val session: EditorSession, private val scope: CoroutineScope) {
    var initialMatchShown = false
    private var finishing = false
    fun finish(snapshot: EditSnapshot?) {
        if (finishing) return
        finishing = true
        val epoch = source.characterEpoch
        scope.launch { runCatching { session.finish(snapshot, epoch) } }
    }
}
