package net.wastu.memoize.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.PopupProperties
import net.wastu.memoize.R

private enum class FormattingMenu { HEADING, LIST, MORE }

/** The enclosing editor owns IME insets; controls keep native input focus and selection. */
@Composable
internal fun EditorFormattingBar(state: NativeSourceState, enabled: Boolean, canAttach: Boolean, onAttach: () -> Unit) {
    val actionsScroll = rememberScrollState()
    var menu by remember { mutableStateOf<FormattingMenu?>(null) }
    val commands = enabled && state.commandReady
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
    // Touch menus retain the editor's IME; hardware navigation needs a focused popup.
    val popup = PopupProperties(focusable = LocalInputModeManager.current.inputMode == InputMode.Keyboard)
    BackHandler(menu != null) { menu = null }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag("editorFormattingBar"),
            verticalAlignment = Alignment.CenterVertically) {
            // Keep the existing native undo controls reachable even on compact windows.
            FormattingButton(R.drawable.ic_undo, "Undo edit", enabled && state.canUndo, action = state::undo)
            FormattingButton(R.drawable.ic_redo, "Redo edit", enabled && state.canRedo, action = state::redo)
            // Accessibility scrolling is asynchronous; expose settled chrome through
            // the existing test boundary without changing labels or input behavior.
            Row(Modifier.weight(1f).horizontalScroll(actionsScroll)
                .testTag(if (actionsScroll.isScrollInProgress) "editorFormattingActionsScrolling" else "editorFormattingActions")) {
                FormattingButton(R.drawable.ic_format_bold, "Bold", commands, active(SourceStyle.BOLD)) { state.format(SourceFormat.BOLD) }
                FormattingButton(R.drawable.ic_format_italic, "Italic", commands, active(SourceStyle.ITALIC)) { state.format(SourceFormat.ITALIC) }
                Box {
                    FormattingButton(R.drawable.ic_format_heading, "Heading", commands, active(SourceStyle.HEADING)) { menu = FormattingMenu.HEADING }
                    DropdownMenu(expanded = menu == FormattingMenu.HEADING, onDismissRequest = { menu = null }, properties = popup) {
                        for (format in listOf(SourceBlockFormat.HEADING_1, SourceBlockFormat.HEADING_2,
                                SourceBlockFormat.HEADING_3, SourceBlockFormat.PARAGRAPH)) {
                            DropdownMenuItem(text = { Text(format.label) }, enabled = commands,
                                onClick = { menu = null; state.formatBlock(format) })
                        }
                    }
                }
                Box {
                    FormattingButton(R.drawable.ic_list_bulleted, "List types", commands) { menu = FormattingMenu.LIST }
                    DropdownMenu(expanded = menu == FormattingMenu.LIST, onDismissRequest = { menu = null }, properties = popup) {
                        DropdownMenuItem(text = { Text("Bullet list") }, leadingIcon = { AppIcon(R.drawable.ic_list_bulleted) },
                            enabled = commands, onClick = { menu = null; state.formatBlock(SourceBlockFormat.BULLETED) })
                        DropdownMenuItem(text = { Text("Numbered list") }, leadingIcon = { AppIcon(R.drawable.ic_list_numbered) },
                            enabled = commands, onClick = { menu = null; state.formatBlock(SourceBlockFormat.NUMBERED) })
                    }
                }
                FormattingButton(R.drawable.ic_format_indent_increase, "Indent", commands &&
                    sourceIndent(state.text, state.selection, false) != null) { state.indent(false) }
                FormattingButton(R.drawable.ic_format_indent_decrease, "Outdent", commands &&
                    sourceIndent(state.text, state.selection, true) != null) { state.indent(true) }
                FormattingButton(R.drawable.ic_checklist, "Checklist", commands) { state.formatBlock(SourceBlockFormat.CHECKLIST) }
                FormattingButton(R.drawable.ic_attach, "Attach file or photo", enabled && canAttach, action = onAttach)
            }
            Box {
                FormattingButton(R.drawable.ic_more, "More formatting", enabled) { menu = FormattingMenu.MORE }
                DropdownMenu(expanded = menu == FormattingMenu.MORE, onDismissRequest = { menu = null }, properties = popup) {
                    DropdownMenuItem(text = { Text("Inline code") }, leadingIcon = { AppIcon(R.drawable.ic_code) },
                        enabled = commands, onClick = { menu = null; state.format(SourceFormat.CODE) })
                    DropdownMenuItem(text = { Text("Checklist") }, leadingIcon = { AppIcon(R.drawable.ic_checklist) },
                        enabled = commands, onClick = { menu = null; state.formatBlock(SourceBlockFormat.CHECKLIST) })
                    DropdownMenuItem(text = { Text("Attach") }, leadingIcon = { AppIcon(R.drawable.ic_attach) },
                        enabled = enabled && canAttach, onClick = { menu = null; onAttach() })
                }
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
