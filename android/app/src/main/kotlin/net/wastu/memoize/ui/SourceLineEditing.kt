package net.wastu.memoize.ui

import androidx.compose.ui.text.TextRange

// Monaco-style whole-line commands, matching the Mac client. Offsets are UTF-16.

private fun lineStart(source: String, offset: Int) = source.lastIndexOf('\n', offset - 1) + 1
private fun lineEnd(source: String, offset: Int) = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
private fun TextRange.shifted(delta: Int) = TextRange(start + delta, end + delta)

/** Full lines touched by the selection, without the final line break. A selection ending at a line start leaves that line out. */
private fun lineBlock(source: String, selection: TextRange): IntRange? {
    if (selection.min < 0 || selection.max > source.length) return null
    var end = selection.max
    if (!selection.collapsed && end > 0 && source[end - 1] == '\n') end--
    val start = lineStart(source, selection.min)
    return start until lineEnd(source, maxOf(start, end))
}

fun moveSourceLines(source: String, selection: TextRange, up: Boolean): SourceEdit? {
    val lines = lineBlock(source, selection) ?: return null
    val start = lines.first
    val stop = lines.last + 1
    val text = source.substring(start, stop)
    if (up) {
        if (start == 0) return null
        val previous = lineStart(source, start - 1)
        val above = source.substring(previous, start - 1)
        return SourceEdit(TextRange(previous, stop), text + "\n" + above, selection.shifted(-(above.length + 1)))
    }
    if (stop >= source.length) return null
    val nextEnd = lineEnd(source, stop + 1)
    val below = source.substring(stop + 1, nextEnd)
    return SourceEdit(TextRange(start, nextEnd), below + "\n" + text, selection.shifted(below.length + 1))
}

/** Copying down selects the new lower copy; copying up keeps the selection on the upper one. */
fun copySourceLines(source: String, selection: TextRange, down: Boolean): SourceEdit? {
    val lines = lineBlock(source, selection) ?: return null
    val text = source.substring(lines.first, lines.last + 1)
    if (down) return SourceEdit(TextRange(lines.last + 1), "\n" + text, selection.shifted(text.length + 1))
    return SourceEdit(TextRange(lines.first), text + "\n", selection)
}

fun deleteSourceLines(source: String, selection: TextRange): SourceEdit? {
    val lines = lineBlock(source, selection) ?: return null
    val start = lines.first
    val stop = lines.last + 1
    val column = selection.min - start
    if (stop < source.length) {
        val nextLength = lineEnd(source, stop + 1) - stop - 1
        return SourceEdit(TextRange(start, stop + 1), "", TextRange(start + minOf(column, nextLength)))
    }
    // The last line takes the preceding break with it and leaves the caret on the line above.
    val from = maxOf(0, start - 1)
    val previous = lineStart(source, from)
    return SourceEdit(TextRange(from, stop), "", TextRange(previous + minOf(column, from - previous)))
}

fun insertSourceLine(source: String, selection: TextRange, above: Boolean): SourceEdit? {
    val lines = lineBlock(source, selection) ?: return null
    val indent = source.substring(lines.first).takeWhile { it == ' ' || it == '\t' }
    if (above) return SourceEdit(TextRange(lines.first), indent + "\n", TextRange(lines.first + indent.length))
    return SourceEdit(TextRange(lines.last + 1), "\n" + indent, TextRange(lines.last + 2 + indent.length))
}

/** Selects whole lines, including their line break; repeating extends by one line. */
fun selectSourceLine(source: String, selection: TextRange): TextRange? {
    if (selection.min < 0 || selection.max > source.length) return null
    fun lineEndWithBreak(offset: Int) = source.indexOf('\n', offset).let { if (it < 0) source.length else it + 1 }
    val start = lineStart(source, selection.min)
    val end = selection.max
    val whole = !selection.collapsed && selection.min == start && (end == source.length || lineStart(source, end) == end)
    val next = when {
        whole -> if (end < source.length) lineEndWithBreak(end) else end
        !selection.collapsed && end > 0 && source[end - 1] == '\n' -> lineEndWithBreak(end - 1)
        else -> lineEndWithBreak(end)
    }
    return TextRange(start, next)
}

private val listLine = Regex("""^[ \t]*(?:[-+*]|[0-9]{1,9}[.)])(?:[ \t]|$)""")

/** List lines keep Markdown nesting; other lines shift by four spaces, also at a bare caret. */
fun indentSourceLines(source: String, selection: TextRange, outdent: Boolean): SourceEdit? {
    sourceIndent(source, selection, outdent)?.let { return it }
    if (!selection.collapsed) return null
    val lines = lineBlock(source, selection) ?: return null
    val content = source.substring(lines.first, lines.last + 1)
    // A list item the list rules refuse to nest stays put rather than becoming a code block.
    if (listLine.containsMatchIn(content)) return null
    if (!outdent) return SourceEdit(TextRange(lines.first), "    ", selection.shifted(4))
    val leading = content.takeWhile { it == ' ' || it == '\t' }
    val remove = if (leading.startsWith('\t')) 1 else minOf(4, leading.takeWhile { it == ' ' }.length)
    if (remove == 0) return null
    return SourceEdit(TextRange(lines.first, lines.first + remove), "", TextRange(maxOf(lines.first, selection.min - remove)))
}
