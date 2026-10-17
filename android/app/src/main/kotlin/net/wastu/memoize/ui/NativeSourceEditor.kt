package net.wastu.memoize.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcel
import android.text.Editable
import android.text.Layout
import android.text.NoCopySpan
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.MetricAffectingSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.viewinterop.AndroidView
import net.wastu.memoize.R
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.Note
import kotlin.math.abs
import kotlin.math.ceil

/** Immutable observation of one authoritative native buffer, not a second editor state. */
internal class NativeSourceState(note: Note, selection: TextRange) {
    var text by mutableStateOf(note.content)
        private set
    var characterEpoch by mutableLongStateOf(0L)
        private set
    var observedContent = note.content
    var selection by mutableStateOf(selection)
        private set
    var composition by mutableStateOf<TextRange?>(null)
        private set
    var scroll by mutableStateOf(note.scroll)
        private set
    var viewport by mutableStateOf(TextRange(selection.min, selection.max))
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var commandReady by mutableStateOf(false)
        private set
    private val history = NativeEditHistory()
    private var view: NativeSourceView? = null
    fun snapshot(): EditSnapshot {
        val observed = EditSnapshot(text, selection.start, selection.end, scroll)
        return view?.snapshot() ?: observed
    }
    fun attach(value: NativeSourceView) {
        view?.takeIf { it !== value }?.let(::detach)
        view = value
        value.seed(text, selection, scroll, history)
        value.onCharactersChanged = { characterEpoch++ }
        value.observe = { content, range, composing, position, visible, undo, redo, commands ->
            text = content; selection = range; composition = composing; scroll = position
            viewport = visible; canUndo = undo; canRedo = redo; commandReady = commands
        }
    }
    fun detach(value: NativeSourceView) {
        if (view !== value) { value.observe = null; value.onCharactersChanged = null; return }
        val snapshot = value.releaseSnapshot()
        text = snapshot.content
        selection = TextRange(snapshot.selectionStart, snapshot.selectionEnd)
        scroll = snapshot.scroll
        composition = null
        canUndo = history.canUndo; canRedo = history.canRedo; commandReady = false
        view = null
    }
    fun undo() { view?.undo() }
    fun redo() { view?.redo() }
    fun indent(outdent: Boolean) { view?.indent(outdent) }
    fun format(format: SourceFormat) { view?.format(format) }
    fun formatBlock(format: SourceBlockFormat) { view?.formatBlock(format) }
    fun finishComposition() { view?.finishComposition() }
    fun replace(content: String, range: TextRange, remote: Boolean = false): Boolean = view?.replaceSource(content, range, remote) == true
    /** Accept committed input only through the authoritative native buffer. */
    suspend fun acceptIncoming(note: Note, session: EditorSession) {
        val epoch = characterEpoch
        if (!session.canAcceptIncoming(note, epoch) || !commandReady) return
        // Even equal text can carry an older persistence origin or remote metadata.
        if (!session.isCurrentIncoming(note)) return
        // Room validation suspends: newer input or composition must still win.
        if (characterEpoch != epoch || !commandReady || !session.canAcceptIncoming(note, epoch)) return
        val current = snapshot()
        if (current.content == note.content || replace(note.content,
                TextRange(current.selectionStart.coerceAtMost(note.content.length), current.selectionEnd.coerceAtMost(note.content.length)), remote = true)) {
            session.acceptRemote(note, characterEpoch)
            observedContent = note.content
        }
    }
    fun focus() { view?.focusForInput() }
    fun hideKeyboard() { view?.windowInsetsController?.hide(WindowInsets.Type.ime()) }
    fun reveal(offset: Int) { view?.post { view?.bringPointIntoView(offset) } }
    fun style(display: DisplayStyles, palette: SourcePalette) { view?.style(display, palette) }
}

internal data class SourcePalette(val primary: Int, val secondary: Int, val tertiary: Int, val codeBackground: Int,
    val searchText: Int, val searchBackground: Int, val syntax: Int = secondary, val activeSyntax: Int = syntax)
internal data class SourcePresentation(val size: Float, val lineHeight: Float, val letterSpacing: Float,
    val text: Int, val hint: Int, val primary: Int, val selection: Int)

internal fun sourceTypeface(): Typeface = Typeface.create(Typeface.DEFAULT, 400, false)

/** A normal StyleSpan cannot remove bold/italic inherited from an enclosing Markdown range. */
internal class SourceSyntaxSpan : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) = resetEmphasis(paint)
    override fun updateMeasureState(paint: TextPaint) = resetEmphasis(paint)
    private fun resetEmphasis(paint: TextPaint) {
        paint.typeface = sourceTypeface()
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
    }
}

/** Editor and previews paint the same literal Markdown with the same native spans. */
internal fun sourceStyleSpans(style: SourceStyle, palette: SourcePalette): List<Any> = when (style) {
    SourceStyle.HEADING -> listOf(ForegroundColorSpan(palette.primary), StyleSpan(Typeface.BOLD))
    SourceStyle.BOLD -> listOf(StyleSpan(Typeface.BOLD))
    SourceStyle.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
    SourceStyle.CODE -> listOf(ForegroundColorSpan(palette.secondary), BackgroundColorSpan(palette.codeBackground))
    SourceStyle.TAG -> listOf(ForegroundColorSpan(palette.tertiary))
    SourceStyle.SYNTAX -> listOf(ForegroundColorSpan(palette.syntax), SourceSyntaxSpan())
    SourceStyle.SEARCH -> listOf(ForegroundColorSpan(palette.searchText), BackgroundColorSpan(palette.searchBackground))
}

@Composable
internal fun NativeSourceField(state: NativeSourceState, enabled: Boolean, modifier: Modifier) {
    val type = MaterialTheme.typography.bodyLarge
    val colors = MaterialTheme.colorScheme
    val selection = LocalTextSelectionColors.current
    val presentation = SourcePresentation(type.fontSize.value, type.lineHeight.value,
        type.letterSpacing.value / type.fontSize.value, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb(),
        selection.handleColor.toArgb(), selection.backgroundColor.toArgb())
    AndroidView(modifier = modifier, factory = { context ->
        NativeSourceView(context).also { view -> state.attach(view); view.present(presentation) }
    }, onRelease = state::detach,
        update = { view -> view.isEnabled = enabled; view.present(presentation) })
}

/** Native input, selection, magnifier and viewport; app commands share one range-delta history. */
internal class NativeSourceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    EditText(context, attrs, 0, R.style.Widget_Memoize_Source) {
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        val populated = super.dispatchPopulateAccessibilityEvent(event)
        val previous = event.beforeText
        // TextView caps current accessibility text at 100k UTF-16 units, but copies the
        // complete previous buffer. Preserve ordinary and near-limit valid events.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && previous != null && previous.length > 100_000) {
            // Repair only payloads already exceeding Binder's shared 1 MiB capacity.
            // Smaller events retain their feedback even though envelopes/concurrent
            // traffic can still prevent delivery. Repaired output targets 768 KiB,
            // an engineering headroom policy rather than a delivery guarantee.
            val budget = 768 * 1024
            fun size(): Int {
                val parcel = Parcel.obtain()
                return try { event.writeToParcel(parcel, 0); parcel.dataSize() } finally { parcel.recycle() }
            }
            if (size() > 1024 * 1024) {
                // Global indices and the Editable stay intact. Events beyond the
                // framework's current-text prefix retain its existing speech limits.
                var limit = 100_000
                while (true) {
                    if (limit > 0 && Character.isHighSurrogate(previous[limit - 1]) && Character.isLowSurrogate(previous[limit])) limit--
                    event.beforeText = previous.subSequence(0, limit)
                    if (size() <= budget || limit == 0) break
                    limit /= 2
                }
            }
        }
        return populated
    }

    var onCharactersChanged: (() -> Unit)? = null
    var observe: ((String, TextRange, TextRange?, Int, TextRange, Boolean, Boolean, Boolean) -> Unit)? = null
    private var history = NativeEditHistory()
    private var ready = false
    private var revision = ""
    private var dirty = false
    private var replaying = false
    private var decorating = false
    private var operationDepth = 0
    private var operationKind: EditKind? = null
    private var operationHadComposition = false
    private var nativeBatch = false
    private var watcherDepth = 0
    private var currentConnection: InputConnection? = null
    private var presentation: SourcePresentation? = null
    private var styled: Pair<DisplayStyles, SourcePalette>? = null
    private var styledSelection: TextRange? = null
    private val derivedSpans = ArrayList<Any>()
    private data class Change(val start: Int, val removed: String, var inserted: String = "", val continueOnNewline: Boolean)
    private val changes = ArrayDeque<Change>()
    private var watched: Editable? = null
    private var restoreWrappedLayout = false
    private var boundsWidthBeforeFallback: Boolean? = null
    private val spanObserver = object : SpanWatcher, NoCopySpan {
        override fun onSpanAdded(text: Spannable?, what: Any?, start: Int, end: Int) { scheduleSettle() }
        override fun onSpanRemoved(text: Spannable?, what: Any?, start: Int, end: Int) { scheduleSettle() }
        override fun onSpanChanged(text: Spannable?, what: Any?, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) { scheduleSettle() }
    }
    private var settlementPosted = false
    private fun scheduleSettle() {
        if (!ready || decorating || replaying || settlementPosted) return
        settlementPosted = true
        post { settlementPosted = false; settle() }
    }
    private fun range() = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
    private fun composing(): TextRange? {
        val body = text ?: return null
        val start = BaseInputConnection.getComposingSpanStart(body)
        val end = BaseInputConnection.getComposingSpanEnd(body)
        return if (start < 0 || end < 0) null else TextRange(start, end)
    }
    private fun idle() = operationDepth == 0 && !nativeBatch && watcherDepth == 0

    init {
        id = R.id.note_source
        // AndroidView saves hierarchy state automatically; full source/history belongs
        // to the logical editor and repository, never an EditText SavedState parcel.
        isSaveEnabled = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setBackgroundColor(Color.TRANSPARENT)
        setStyleShortcutsEnabled(false)
        hint = "Write a note…"
        val density = resources.displayMetrics.density
        setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        setEditableFactory(object : Editable.Factory() {
            override fun newEditable(source: CharSequence): Editable {
                prepareLineLayout("", 0, source, 0, source.length, 0)
                return object : SpannableStringBuilder(source) {
                    override fun replace(start: Int, end: Int, incoming: CharSequence, incomingStart: Int,
                        incomingEnd: Int): SpannableStringBuilder {
                        prepareLineLayout(this, start, incoming, incomingStart, incomingEnd, end)
                        return super.replace(start, end, incoming, incomingStart, incomingEnd)
                    }
                }
            }
        })
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(source: CharSequence?, start: Int, count: Int, after: Int) {
                if (!ready || replaying) return
                history.begin(range(), if (composing() != null) EditKind.COMPOSITION else EditKind.TYPING)
                watcherDepth++
                val selected = range()
                val eligible = !decorating && selected.collapsed && selected.start == start && count == 0 && after == 1
                changes.addLast(Change(start, TextUtils.substring(requireNotNull(source), start, start + count), continueOnNewline = eligible))
            }
            override fun onTextChanged(source: CharSequence?, start: Int, before: Int, count: Int) {
                if (!ready || replaying) return
                changes.last().inserted = TextUtils.substring(requireNotNull(source), start, start + count)
            }
            override fun afterTextChanged(source: Editable?) {
                // Restore layout modes only after the NEW, safe source is installed.
                // The old wide watcher can finish this edit without reshaping bounds.
                if (restoreWrappedLayout) {
                    restoreWrappedLayout = false
                    setHorizontallyScrolling(false)
                    boundsWidthBeforeFallback?.let { setUseBoundsForWidth(it) }
                    boundsWidthBeforeFallback = null
                    scrollTo(0, scrollY)
                }
                if (!ready) return
                dirty = true
                if (replaying) return
                val change = changes.removeLast()
                history.record(TextDelta(change.start, change.removed, change.inserted))
                if (change.removed != change.inserted) onCharactersChanged?.invoke()
                if (change.inserted == "\n" && change.removed.isEmpty() && change.continueOnNewline) {
                    val original = StringBuilder(requireNotNull(source)).deleteCharAt(change.start).toString()
                    continueList(original, change.start)?.let { plan ->
                        history.begin(range(), EditKind.COMMAND)
                        source.replace(plan.range.min, plan.range.max, plan.replacement)
                        setSelection(plan.selection.start, plan.selection.end)
                    }
                }
                watcherDepth--
                if (watcherDepth == 0 && operationDepth == 0) {
                    // Hardware/accessibility routes can finish selection after this callback.
                    scheduleSettle()
                }
            }
        })
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                SourceFormat.entries.forEachIndexed { index, format ->
                    val itemId = FORMAT_ID + index
                    if (menu.findItem(itemId) == null) menu.add(Menu.NONE, itemId, Menu.NONE, format.label)
                        .setIcon(context.getDrawable(format.icon)?.mutate()?.apply { setTint(presentation?.text ?: Color.BLACK) })
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val format = SourceFormat.entries.getOrNull(item.itemId - FORMAT_ID) ?: return false
                val edit = formatSource(revision, range(), format)
                if (edit != null && canCommand()) command(edit) { mode.finish() }
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    private fun prepareLineLayout(destination: CharSequence, replaceStart: Int, incoming: CharSequence,
        start: Int, end: Int, replaceEnd: Int) {
        val wide = hasLongLayoutContext(destination, replaceStart, incoming, start, end, replaceEnd)
        restoreWrappedLayout = !wide && (isHorizontallyScrollable || boundsWidthBeforeFallback != null)
        if (wide) {
            if (boundsWidthBeforeFallback == null) boundsWidthBeforeFallback = getUseBoundsForWidth()
            // Long emoji contexts repeatedly reshape glyph bounds in the native line
            // breaker. Advance widths avoid that work without changing the Editable.
            setUseBoundsForWidth(false)
        }
        if (wide && !isHorizontallyScrollable) {
            setHorizontallyScrolling(true)
            // Retire any old narrow DynamicLayout watcher before native mutation,
            // including a layout invalidated by a preceding presentation change.
            if (width > 0 && height > 0) {
                measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }
    }

    private fun hasLongLayoutContext(destination: CharSequence, replaceStart: Int, incoming: CharSequence,
        start: Int, end: Int, replaceEnd: Int): Boolean {
        var run = 0
        fun scan(text: CharSequence, from: Int, until: Int): Boolean {
            for (index in from until until) {
                val character = text[index]
                run = when {
                    character == '\n' || character == ' ' || character in '\u2000'..'\u200a' || character == '\u3000' ||
                        character in '\u2066'..'\u2069' || character in '\u202a'..'\u202e' || character == '\u200e' || character == '\u200f' -> 0
                    // Minikin breaks BEFORE ideographs; retain the character in the next context.
                    character in '\u3400'..'\u9fff' -> 1
                    else -> run + 1
                }
                if (run > 65_536) return true
            }
            return false
        }
        return scan(destination, 0, replaceStart) || scan(incoming, start, end) ||
            scan(destination, replaceEnd, destination.length)
    }

    fun seed(content: String, selection: TextRange, position: Int, retainedHistory: NativeEditHistory) {
        check(!ready)
        history = retainedHistory
        setText(content)
        revision = content
        setSelection(selection.start.coerceIn(0, length()), selection.end.coerceIn(0, length()))
        ready = true
        watchBuffer()
        post {
            scrollTo(0, position)
            if (isHorizontallyScrollable) {
                bringPointIntoView(range().end)
                scrollTo(scrollX, position)
            }
            settle()
        }
    }

    fun releaseSnapshot(): EditSnapshot {
        val snapshot = snapshot()
        history.finish(range(), composing = false)
        ready = false
        observe = null
        onCharactersChanged = null
        currentConnection = null
        watched?.removeSpan(spanObserver)
        watched = null
        // Late callbacks belong to the detached buffer, never the retained editor's history.
        history = NativeEditHistory()
        return snapshot
    }

    private fun watchBuffer() {
        val body = text ?: return
        if (watched === body) return
        decorating = true
        try {
            watched?.removeSpan(spanObserver)
            watched = body
            body.setSpan(spanObserver, 0, body.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        } finally { decorating = false }
    }

    private fun settle() {
        if (!ready || operationDepth != 0 || watcherDepth != 0 || decorating) return
        watchBuffer()
        refreshRevision()
        val composition = composing()
        // Rebuild derived drawing spans only after native input has settled.
        // Never disturb an active batch or composing buffer to reveal punctuation.
        if (!nativeBatch && composition == null && styledSelection != range()) {
            styled?.let { style(it.first, it.second) }
        }
        if (!replaying && !nativeBatch) history.finish(range(), composition != null)
        val result = layout
        val top = (scrollY - totalPaddingTop).coerceAtLeast(0)
        val bottom = (scrollY + height - totalPaddingBottom).coerceAtLeast(top)
        val visible = if (result == null) range() else TextRange(result.getLineStart(result.getLineForVertical(top)),
            result.getLineEnd(result.getLineForVertical(bottom)).coerceAtMost(revision.length))
        observe?.invoke(revision, range(), composition, scrollY, visible, history.canUndo, history.canRedo, canReplaceSource())
    }

    private fun refreshRevision() {
        if (!dirty) return
        val current = requireNotNull(text).toString()
        if (current != revision) { revision = current; styled = null }
        dirty = false
    }
    fun snapshot(): EditSnapshot {
        refreshRevision()
        val selection = range()
        return EditSnapshot(revision, selection.start, selection.end, scrollY)
    }

    private inline fun <T> operation(kind: EditKind, block: () -> T): T {
        if (operationDepth == 0) { operationKind = kind; operationHadComposition = composing() != null }
        if (ready && !replaying) history.begin(range(), kind)
        operationDepth++
        try { return block() } finally { operationDepth--; if (operationDepth == 0) { operationKind = null; operationHadComposition = false }; settle() }
    }
    override fun onBeginBatchEdit() {
        super.onBeginBatchEdit()
        if (ready && !replaying) history.begin(range(), if (composing() != null) EditKind.COMPOSITION else EditKind.TYPING)
        nativeBatch = true
        scheduleSettle()
    }
    override fun onEndBatchEdit() { super.onEndBatchEdit(); nativeBatch = false; settle() }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val delegate = super.onCreateInputConnection(outAttrs) ?: return null
        currentConnection = delegate
        return object : InputConnectionWrapper(delegate, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = operation(EditKind.COMPOSITION) {
                super.setComposingText(text, newCursorPosition)
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) = operation(EditKind.COMPOSITION) {
                super.setComposingText(text, newCursorPosition, textAttribute)
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = operation(EditKind.TYPING) {
                super.commitText(text, newCursorPosition)
            }
            override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) = operation(EditKind.TYPING) {
                super.commitText(text, newCursorPosition, textAttribute)
            }
            override fun setComposingRegion(start: Int, end: Int) = operation(EditKind.COMPOSITION) { super.setComposingRegion(start, end) }
            override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) = operation(EditKind.COMPOSITION) {
                super.setComposingRegion(start, end, textAttribute)
            }
            override fun finishComposingText() = operation(EditKind.COMPOSITION) { super.finishComposingText() }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = operation(EditKind.TYPING) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = operation(EditKind.TYPING) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
            override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
                operation(EditKind.TYPING) { super.replaceText(start, end, text, newCursorPosition, textAttribute) }
            override fun setSelection(start: Int, end: Int): Boolean {
                if (ready && composing() == null) history.selectionMoved()
                return operation(EditKind.TYPING) { super.setSelection(start, end) }
            }
            override fun closeConnection() {
                super.closeConnection()
                if (currentConnection === delegate) currentConnection = null
                scheduleSettle()
            }
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (ready && idle() && !replaying && !decorating) { history.selectionMoved(); scheduleSettle() }
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); scheduleSettle() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); scheduleSettle() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && canCommand()) {
            val edit = sourceIndent(revision, range(), event.isShiftPressed)
            if (edit != null) { command(edit); return true }
            if (!range().collapsed) return true
        }
        return operation(EditKind.TYPING) { super.onKeyDown(keyCode, event) }
    }
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z) { if (event.isShiftPressed) redo() else undo(); return true }
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Y) { redo(); return true }
        return super.onKeyShortcut(keyCode, event)
    }
    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { undo(); true }
        android.R.id.redo -> { redo(); true }
        android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.cut -> operation(EditKind.CLIPBOARD) {
            super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)
        }
        else -> super.onTextContextMenuItem(id)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        UNDO_ID -> { undo(); true }
        REDO_ID -> { redo(); true }
        INDENT_ID -> { indent(false); true }
        OUTDENT_ID -> { indent(true); true }
        AccessibilityNodeInfo.ACTION_SET_TEXT -> operation(EditKind.CLIPBOARD) { super.performAccessibilityAction(action, arguments) }
        else -> super.performAccessibilityAction(action, arguments)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!ready) return
        if (history.canUndo) info.addAction(AccessibilityNodeInfo.AccessibilityAction(UNDO_ID, "Undo edit"))
        if (history.canRedo) info.addAction(AccessibilityNodeInfo.AccessibilityAction(REDO_ID, "Redo edit"))
        if (canCommand() && sourceIndent(revision, range(), false) != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(INDENT_ID, "Indent"))
        if (canCommand() && sourceIndent(revision, range(), true) != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(OUTDENT_ID, "Outdent"))
    }

    private fun canReplaceSource() = ready && idle() && composing() == null
    private fun canCommand() = isEnabled && canReplaceSource()
    fun finishComposition() {
        if (composing() != null) {
            val connection = currentConnection
            if (connection != null) {
                operation(EditKind.COMPOSITION) { connection.finishComposingText() }
            }
            val body = text
            if (body != null) {
                BaseInputConnection.removeComposingSpans(body)
            }
            settle()
        }
    }
    fun indent(outdent: Boolean) { if (canCommand()) sourceIndent(revision, range(), outdent)?.let(::command) }
    fun format(format: SourceFormat) { if (canCommand()) formatSourceAtSelection(revision, range(), format)?.let(::command) }
    fun formatBlock(format: SourceBlockFormat) { if (canCommand()) formatBlocks(revision, range(), format)?.let(::command) }
    private fun command(edit: SourceEdit, before: () -> Unit = {}) {
        if (!canCommand()) return
        operation(EditKind.COMMAND) {
            before()
            requireNotNull(text).replace(edit.range.min, edit.range.max, edit.replacement)
            setSelection(edit.selection.start, edit.selection.end)
        }
    }
    fun replaceSource(content: String, selection: TextRange, remote: Boolean): Boolean {
        if (!canReplaceSource()) return false
        var start = 0
        while (start < revision.length && start < content.length && revision[start] == content[start]) start++
        var oldEnd = revision.length
        var newEnd = content.length
        while (oldEnd > start && newEnd > start && revision[oldEnd - 1] == content[newEnd - 1]) { oldEnd--; newEnd-- }
        operation(EditKind.COMMAND) {
            requireNotNull(text).replace(start, oldEnd, content.substring(start, newEnd))
            setSelection(selection.start, selection.end)
        }
        if (remote) { history.clear(); settle() }
        return true
    }
    private fun replay(redo: Boolean) {
        if (!isEnabled || !idle()) return
        finishComposition()
        if (!idle()) return
        settle()
        val transaction = (if (redo) history.nextRedo() else history.nextUndo()) ?: return
        val deltas = if (redo) transaction.changes else transaction.changes.asReversed()
        // Validate the complete operation before any mutation; history can never replay partially.
        val checked = StringBuilder(revision)
        for (delta in deltas) {
            val expected = if (redo) delta.removed else delta.inserted
            val replacement = if (redo) delta.inserted else delta.removed
            if (delta.start < 0 || delta.start + expected.length > checked.length ||
                checked.substring(delta.start, delta.start + expected.length) != expected) { history.clear(); settle(); return }
            checked.replace(delta.start, delta.start + expected.length, replacement)
        }
        replaying = true
        try {
            val body = requireNotNull(text)
            for (delta in deltas) {
                val expected = if (redo) delta.removed else delta.inserted
                body.replace(delta.start, delta.start + expected.length, if (redo) delta.inserted else delta.removed)
            }
            val selection = if (redo) transaction.after else transaction.before
            setSelection(selection.start, selection.end)
            if (redo) history.redone(transaction) else history.undone(transaction)
            if (transaction.changes.any { it.removed != it.inserted }) onCharactersChanged?.invoke()
        } finally { replaying = false; settle() }
    }
    fun undo() { replay(false) }
    fun redo() { replay(true) }
    fun focusForInput() { post { requestFocus(); windowInsetsController?.show(WindowInsets.Type.ime()) } }

    internal fun present(value: SourcePresentation) {
        if (presentation == value) return
        presentation = value
        typeface = sourceTypeface()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, value.size)
        setLineHeight(TypedValue.COMPLEX_UNIT_SP, value.lineHeight)
        letterSpacing = value.letterSpacing
        setTextColor(value.text); setHintTextColor(value.hint); highlightColor = value.selection
        textCursorDrawable?.mutate()?.let { it.setTint(value.primary); setTextCursorDrawable(it) }
        textSelectHandle?.mutate()?.let { it.setTint(value.primary); setTextSelectHandle(it) }
        textSelectHandleLeft?.mutate()?.let { it.setTint(value.primary); setTextSelectHandleLeft(it) }
        textSelectHandleRight?.mutate()?.let { it.setTint(value.primary); setTextSelectHandleRight(it) }
        styled?.let { previous ->
            styled = null
            style(previous.first, previous.second)
        }
    }
    fun style(display: DisplayStyles, palette: SourcePalette) {
        if (display.input !== revision || (styled == (display to palette) && styledSelection == range())) return
        val body = text ?: return
        decorating = true
        try {
            derivedSpans.forEach(body::removeSpan)
            derivedSpans.clear()
            for (styledRange in display.ranges) {
                val spans = if (styledRange.style == SourceStyle.SYNTAX) {
                    val marker = styledRange.range
                    val nearby = selectionStart >= 0 && selectionEnd >= 0 &&
                        minOf(selectionStart, selectionEnd) <= marker.end && maxOf(selectionStart, selectionEnd) >= marker.start
                    listOf(ForegroundColorSpan(if (nearby) palette.activeSyntax else palette.syntax), SourceSyntaxSpan())
                } else sourceStyleSpans(styledRange.style, palette)
                spans.forEach { span ->
                    body.setSpan(span, styledRange.range.start, styledRange.range.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    derivedSpans += span
                }
            }
            for (paragraph in display.lists) {
                // Native measurement includes the current font, letter spacing and tab stops.
                val margin = ceil(Layout.getDesiredWidth(body, paragraph.range.start, paragraph.contentStart, paint)).toInt()
                val span = LeadingMarginSpan.Standard(0, margin)
                body.setSpan(span, paragraph.range.start, paragraph.range.end, Spannable.SPAN_PARAGRAPH)
                derivedSpans += span
            }
            styled = display to palette
            styledSelection = range()
        } finally { decorating = false }
    }

    private data class ListDrag(val source: String, val offset: Int, val x: Float, val y: Float, val time: Long, var claimed: Boolean = false)
    private var drag: ListDrag? = null
    override fun performClick(): Boolean = super.performClick()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val config = ViewConfiguration.get(context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = null
                val result = layout
                if (canCommand() && range().collapsed && result != null && event.x >= 8 * resources.displayMetrics.density) {
                    val y = (event.y - totalPaddingTop + scrollY).toInt()
                    if (y in 0 until result.height) {
                        val line = result.getLineForVertical(y)
                        val start = result.getLineStart(line)
                        val offset = getOffsetForPosition(event.x, event.y)
                        val prefix = listPrefixLength(revision, start)
                        if (start == revision.lastIndexOf('\n', start - 1) + 1 && prefix != null && offset <= start + prefix &&
                            listIndent(revision, TextRange(offset), false) != null) {
                            drag = ListDrag(revision, offset, event.x, event.y, event.eventTime)
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> drag?.let { candidate ->
                val dx = event.x - candidate.x
                val dy = event.y - candidate.y
                if (!candidate.claimed && (event.pointerCount != 1 || abs(dy) > config.scaledTouchSlop ||
                    event.eventTime - candidate.time >= ViewConfiguration.getLongPressTimeout())) drag = null
                else if (candidate.claimed || (abs(dx) > config.scaledTouchSlop && abs(dx) > abs(dy) * 1.25f)) {
                    if (!candidate.claimed) {
                        candidate.claimed = true
                        MotionEvent.obtain(event).also { cancel -> cancel.action = MotionEvent.ACTION_CANCEL; super.onTouchEvent(cancel); cancel.recycle() }
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val candidate = drag
                drag = null
                if (candidate?.claimed == true) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = event.x - candidate.x
                    if (abs(dx) >= 32 * resources.displayMetrics.density && canCommand() && range().collapsed && revision === candidate.source) {
                        listIndent(revision, TextRange(candidate.offset), dx < 0)?.let(::command)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { drag = null; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return handled
    }
    private companion object {
        const val FORMAT_ID = 0x010f0010
        const val UNDO_ID = 0x010f0020
        const val REDO_ID = 0x010f0021
        const val INDENT_ID = 0x010f0022
        const val OUTDENT_ID = 0x010f0023
    }
}
