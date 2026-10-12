package net.wastu.memoize.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

internal fun noteGridCapacity(widthDp: Float, fontScale: Float): Int =
    floor((widthDp - 32f + 12f) / (160f * max(fontScale, 1f) + 12f)).toInt().coerceIn(1, 4)

internal fun noteGridColumns(preferred: Int, widthDp: Float, fontScale: Float): Int {
    val default = when {
        widthDp < 300f || fontScale >= 1.5f -> 1
        widthDp < 600f -> 2
        else -> 3
    }
    return (if (preferred > 0) preferred else default).coerceIn(1, noteGridCapacity(widthDp, fontScale))
}

internal fun pinchedNoteColumns(columns: Int, maximum: Int, zoom: Float): Int = when {
    zoom >= 1.18f -> (columns - 1).coerceIn(1, maximum)
    zoom <= 0.85f -> (columns + 1).coerceIn(1, maximum)
    else -> columns.coerceIn(1, maximum)
}

/** Single-pointer events pass through unchanged; only a deliberate two-finger zoom is claimed. */
@Composable
internal fun Modifier.noteGridPinch(columns: Int, maximum: Int, onColumnsChange: (Int) -> Unit): Modifier {
    val currentColumns by rememberUpdatedState(columns)
    val currentMaximum by rememberUpdatedState(maximum)
    val change by rememberUpdatedState(onColumnsChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var multiple = false
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0 || (multiple && pressed < 2)) break
                if (pressed < 2) continue
                multiple = true
                if (!claimed) {
                    zoom *= event.calculateZoom()
                    if (zoom >= 1.18f || zoom <= 0.85f) {
                        claimed = true
                        change(pinchedNoteColumns(currentColumns, currentMaximum, zoom))
                    }
                }
                if (claimed) event.changes.filter { it.pressed }.forEach { it.consume() }
            }
        }
    }
}

@Composable
internal fun NoteColumnChoices(columns: Int, maximum: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Columns", Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (count in 1..maximum) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = columns == count, role = Role.RadioButton, onClick = { onChange(count) })
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = columns == count, onClick = null)
                Text(if (count == 1) "1 column" else "$count columns", Modifier.padding(start = 8.dp))
            }
        }
    }
}
