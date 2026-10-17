package net.wastu.memoize.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.memoize.R

/** The enclosing editor owns IME insets; controls keep native input focus and selection. */
@Composable
internal fun EditorFormattingBar(state: NativeSourceState, enabled: Boolean, canAttach: Boolean, onAttach: () -> Unit) {
    val actionsScroll = rememberScrollState()
    val commands = enabled
    val source = state.text
    val selection = state.selection
    val parsed by produceState<DisplayStyles?>(null, source, selection) {
        value = withContext(Dispatchers.Default) {
            sourceStyles(source, "", selection.min, minOf(selection.max, selection.min + 1_024))
        }
    }
    val activeStyles = parsed?.takeIf { it.input === source }?.ranges.orEmpty()
    fun active(style: SourceStyle): Boolean = activeStyles.any { candidate ->
        candidate.style == style && selection.min >= candidate.range.start &&
            selection.max <= candidate.range.end &&
            activeStyles.none { it.style == SourceStyle.CODE && selection.min >= it.range.start && selection.max <= it.range.end }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag("editorFormattingBar"),
            verticalAlignment = Alignment.CenterVertically) {
            FormattingButton(R.drawable.ic_undo, "Undo edit", enabled && state.canUndo, action = state::undo)
            FormattingButton(R.drawable.ic_redo, "Redo edit", enabled && state.canRedo, action = state::redo)
            Row(Modifier.weight(1f).horizontalScroll(actionsScroll)
                .testTag(if (actionsScroll.isScrollInProgress) "editorFormattingActionsScrolling" else "editorFormattingActions")) {
                FormattingButton(R.drawable.ic_format_bold, "Bold", commands, active(SourceStyle.BOLD)) {
                    state.finishComposition()
                    state.format(SourceFormat.BOLD)
                }
                FormattingButton(R.drawable.ic_format_italic, "Italic", commands, active(SourceStyle.ITALIC)) {
                    state.finishComposition()
                    state.format(SourceFormat.ITALIC)
                }
                FormattingButton(R.drawable.ic_format_heading, "Heading", commands, active(SourceStyle.HEADING)) {
                    state.finishComposition()
                    state.formatBlock(SourceBlockFormat.HEADING_1)
                }
                FormattingButton(R.drawable.ic_list_bulleted, "Bullet list", commands) {
                    state.finishComposition()
                    state.formatBlock(SourceBlockFormat.BULLETED)
                }
                FormattingButton(R.drawable.ic_list_numbered, "Numbered list", commands) {
                    state.finishComposition()
                    state.formatBlock(SourceBlockFormat.NUMBERED)
                }
                FormattingButton(R.drawable.ic_checklist, "Checklist", commands) {
                    state.finishComposition()
                    state.formatBlock(SourceBlockFormat.CHECKLIST)
                }
                FormattingButton(R.drawable.ic_code, "Inline code", commands, active(SourceStyle.CODE)) {
                    state.finishComposition()
                    state.format(SourceFormat.CODE)
                }
                FormattingButton(R.drawable.ic_format_indent_increase, "Indent", commands &&
                    sourceIndent(state.text, state.selection, false) != null) {
                    state.finishComposition()
                    state.indent(false)
                }
                FormattingButton(R.drawable.ic_format_indent_decrease, "Outdent", commands &&
                    sourceIndent(state.text, state.selection, true) != null) {
                    state.finishComposition()
                    state.indent(true)
                }
                FormattingButton(R.drawable.ic_attach, "Attach file or photo", enabled && canAttach, action = onAttach)
            }
        }
    }
}

@Composable
private fun FormattingButton(icon: Int, description: String, enabled: Boolean, selected: Boolean = false, action: () -> Unit) {
    val inputMode = LocalInputModeManager.current.inputMode
    IconButton(onClick = action, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.size(48.dp).then(
            if (description in listOf("Bold", "Italic", "Heading")) Modifier.semantics { this.selected = selected } else Modifier
        ).focusProperties { canFocus = inputMode == InputMode.Keyboard }) {
        AppIcon(icon, description)
    }
}
