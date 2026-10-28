package net.wastu.memoize.ui

import androidx.compose.ui.text.TextRange
import net.wastu.memoize.R
import net.wastu.memoize.data.TextMatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SourceStyle { HEADING, BOLD, ITALIC, CODE, TAG, LINK, SYNTAX, SEARCH }
data class StyledRange(val range: TextMatch, val style: SourceStyle)
data class ListParagraph(val range: TextMatch, val contentStart: Int)
data class DisplayStyles(val input: CharSequence, val query: String, val ranges: List<StyledRange>,
    val lists: List<ListParagraph> = emptyList())

private val heading = Regex("(?m)^ {0,3}#{1,6}[^\\n]*")
private val headingMarker = Regex("^ {0,3}(#{1,6})(?=[ \t]|$)")
private val closingHeadingMarker = Regex("[ \t]+(#+)[ \t]*$")
private val bold = Regex("\\*\\*[^\\n]*?\\*\\*|__[^\\n]*?__")
private val italic = Regex("(?<!\\*)\\*[^\\n*]+\\*(?!\\*)")
private val code = Regex("(?ms)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n.*?^ {0,3}\\1[^\\n]*|`[^`\\n]+`")
private val tag = Regex("#[\\p{L}\\p{N}_+/-]+")
private val listPrefix = Regex("^([ \\t]*)([-+*]|[0-9]{1,9}[.)])([ \\t]+)(?:\\[([ xX])\\](?:[ \\t]+|$))?")
private val fencePrefix = Regex("^[ \\t]*(?:>[ \\t]*)?(?:[-+*][ \\t]+|[0-9]{1,9}[.)][ \\t]+)?(`{3,}|~{3,})(.*)$")
private val thematicBreak = Regex("^[ \\t]{0,3}(?:\\*(?:[ \\t]*\\*){2,}|-(?:[ \\t]*-){2,}|_(?:[ \\t]*_){2,})[ \\t]*$")
private val markdownLink = Regex("\\[([^\\]\\n]+)\\]\\(([^)\\s\\n]+)\\)")
private val autolink = Regex("<((?:https?://|mailto:)[^>\\s\\n]+)>")
private val writtenUrl = Regex("(?:https?://|www\\.)[\\p{L}\\p{N}_.-]+[^<\\x09-\\x0d\\p{Z}]*")
private val trailingUrlPunctuation = charArrayOf('.', ',', '?', '!', ';', ':', '"', '\'')

internal fun resolveLinkUrl(linkText: String): String {
    val trimmed = linkText.trim()
    val raw = when {
        trimmed.startsWith("[") && trimmed.contains("](") && trimmed.endsWith(")") ->
            trimmed.substringAfter("](").dropLast(1).trim()
        trimmed.startsWith("<") && trimmed.endsWith(">") ->
            trimmed.drop(1).dropLast(1).trim()
        else -> trimmed
    }
    return if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
}

internal fun matchWrittenUrls(source: String): List<TextMatch> {
    val result = ArrayList<TextMatch>()
    for (match in writtenUrl.findAll(source)) {
        val start = match.range.first
        val boundary = if (start == 0) true
        else if (start == 1 && source[0] == '\uFEFF') true
        else source[start - 1] in " \t\n\r*_-~("
        if (!boundary) continue

        var label = match.value
        val hostStart = when {
            label.startsWith("https://", ignoreCase = true) -> 8
            label.startsWith("http://", ignoreCase = true) -> 7
            else -> 4
        }
        val domain = label.drop(hostStart).takeWhile { it.isLetterOrDigit() || it in "_.-" }
        val parts = domain.split('.')
        if (parts.size < 2 || parts[parts.size - 1].contains('_') || parts[parts.size - 2].contains('_')) continue

        var trimmedLen = label.length
        while (trimmedLen > 0) {
            val last = label[trimmedLen - 1]
            if (last in trailingUrlPunctuation) {
                trimmedLen--
                label = label.substring(0, trimmedLen)
            } else if (last == ')') {
                val open = label.count { it == '(' }
                val close = label.count { it == ')' }
                if (close > open) {
                    trimmedLen--
                    label = label.substring(0, trimmedLen)
                } else break
            } else break
        }
        if (trimmedLen > 0) {
            result += TextMatch(start, start + trimmedLen)
        }
    }
    return result
}

internal fun findLinkAt(display: DisplayStyles, offset: Int): Pair<String, TextMatch>? {
    val styled = display.ranges.firstOrNull { it.style == SourceStyle.LINK && offset in it.range.start until it.range.end }
        ?: return null
    val raw = display.input.subSequence(
        styled.range.start.coerceIn(0, display.input.length),
        styled.range.end.coerceIn(0, display.input.length)
    ).toString()
    return resolveLinkUrl(raw) to styled.range
}

/** Bound display annotations around the viewport; source and full-body repository search stay exact. */
suspend fun sourceStyles(input: CharSequence, query: String, anchor: Int = 0, visibleEnd: Int = anchor + 1_024): DisplayStyles {
    val body = input.toString()
    // AnnotatedString queries intersecting styles for every paragraph. Styling all of a megabyte
    // document can turn native paragraph layout quadratic even when parsing is off the UI thread.
    var start = (anchor - 512).coerceIn(0, body.length)
    var end = (visibleEnd + 512).coerceIn(start, body.length)
    if (start > 0 && start < body.length && body[start].isLowSurrogate()) start--
    if (end < body.length && body[end].isLowSurrogate()) end--
    val source = body.substring(start, end)
    val ranges = ArrayList<StyledRange>()
    val markers = ArrayList<StyledRange>()
    val codeMatches = code.findAll(source).toList()
    fun marker(from: Int, to: Int) {
        if (from < to && codeMatches.none { from >= it.range.first && to <= it.range.last + 1 })
            markers += StyledRange(TextMatch(start + from, start + to), SourceStyle.SYNTAX)
    }
    val markdownLinkMatches = markdownLink.findAll(source).filter { m ->
        codeMatches.none { m.range.first >= it.range.first && m.range.last <= it.range.last }
    }.toList()
    val autolinkMatches = autolink.findAll(source).filter { m ->
        codeMatches.none { m.range.first >= it.range.first && m.range.last <= it.range.last }
    }.toList()
    val bareUrlMatches = matchWrittenUrls(source).filter { u ->
        codeMatches.none { u.start >= it.range.first && u.end <= it.range.last + 1 } &&
        markdownLinkMatches.none { u.start >= it.range.first && u.end <= it.range.last + 1 } &&
        autolinkMatches.none { u.start >= it.range.first && u.end <= it.range.last + 1 }
    }
    for ((expression, style) in listOf(heading to SourceStyle.HEADING, bold to SourceStyle.BOLD, italic to SourceStyle.ITALIC, code to SourceStyle.CODE, tag to SourceStyle.TAG)) {
        val matches = when (style) {
            SourceStyle.CODE -> codeMatches.asSequence()
            SourceStyle.TAG -> expression.findAll(source).filter { t ->
                codeMatches.none { t.range.first >= it.range.first && t.range.last <= it.range.last } &&
                markdownLinkMatches.none { t.range.first >= it.range.first && t.range.last <= it.range.last } &&
                bareUrlMatches.none { t.range.first >= it.start && t.range.last <= it.end }
            }
            else -> expression.findAll(source)
        }
        matches.forEach { match ->
            currentCoroutineContext().ensureActive()
            val from = match.range.first
            val to = match.range.last + 1
            ranges += StyledRange(TextMatch(start + from, start + to), style)
            when (style) {
                SourceStyle.BOLD -> { marker(from, from + 2); marker(to - 2, to) }
                SourceStyle.ITALIC -> { marker(from, from + 1); marker(to - 1, to) }
                SourceStyle.HEADING -> {
                    headingMarker.find(match.value)?.groups?.get(1)?.let {
                        marker(from + it.range.first, from + it.range.last + 1)
                    }
                    closingHeadingMarker.find(match.value)?.groups?.get(1)?.let {
                        marker(from + it.range.first, from + it.range.last + 1)
                    }
                }
                else -> Unit
            }
        }
    }
    markdownLinkMatches.forEach { match ->
        val from = match.range.first
        val to = match.range.last + 1
        ranges += StyledRange(TextMatch(start + from, start + to), SourceStyle.LINK)
        marker(from, from + 1)
        val textGroup = match.groups[1]!!
        val urlGroup = match.groups[2]!!
        marker(textGroup.range.last + 1, urlGroup.range.first)
        marker(to - 1, to)
    }
    autolinkMatches.forEach { match ->
        val from = match.range.first
        val to = match.range.last + 1
        ranges += StyledRange(TextMatch(start + from, start + to), SourceStyle.LINK)
        marker(from, from + 1)
        marker(to - 1, to)
    }
    bareUrlMatches.forEach { match ->
        ranges += StyledRange(TextMatch(start + match.start, start + match.end), SourceStyle.LINK)
    }
    // Reset marker emphasis after content styles; actual search highlighting still wins.
    ranges += markers
    net.wastu.memoize.data.LiteralSearch.ranges(source, query).forEach {
        ranges += StyledRange(TextMatch(start + it.start, start + it.end), SourceStyle.SEARCH)
    }
    val lists = ArrayList<ListParagraph>()
    val context = ListContext(retainHistory = false)
    var lineStart = 0
    while (lineStart < end) {
        currentCoroutineContext().ensureActive()
        val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
        val paragraphEnd = if (lineEnd < body.length) lineEnd + 1 else lineEnd
        val prefix = context.append(body.substring(lineStart, lineEnd))
        if (prefix != null && paragraphEnd > start) {
            lists += ListParagraph(TextMatch(lineStart, paragraphEnd), lineStart + prefix.length)
        }
        lineStart = paragraphEnd
    }
    return DisplayStyles(input, query, ranges, lists)
}

private data class ListItemPrefix(val indent: String, val marker: String, val gap: String, val task: Boolean, val length: Int) {
    val column: Int get() = indent.fold(0) { column, character -> if (character == '\t') (column / 4 + 1) * 4 else column + 1 }
    val contentColumn: Int get() = (indent + marker + gap).fold(0) { column, character ->
        if (character == '\t') (column / 4 + 1) * 4 else column + 1
    }
    val next: String get() {
        val nextMarker = marker.dropLast(1).toIntOrNull()?.let { "${minOf(it + 1, 999_999_999)}${marker.last()}" } ?: marker
        return indent + nextMarker + gap + if (task) "[ ] " else ""
    }
}

private fun listItem(line: String): ListItemPrefix? {
    if (thematicBreak.matches(line)) return null
    val prefix = listPrefix.find(line) ?: return null
    return ListItemPrefix(prefix.groupValues[1], prefix.groupValues[2], prefix.groupValues[3], prefix.groups[4] != null, prefix.value.length)
}

private class ListContext(private val retainHistory: Boolean = true) {
    private var fence: Pair<Char, Int>? = null
    val inFence: Boolean get() = fence != null
    val items = ArrayList<ListItemPrefix>()
    private val parents = ArrayList<ListItemPrefix>()
    fun append(line: String): ListItemPrefix? {
        val wasFenced = inFence
        val candidate = fencePrefix.find(line)
        candidate?.let {
            val run = candidate.groupValues[1]
            val suffix = candidate.groupValues[2]
            val open = fence
            if (open != null) {
                if (run.first() == open.first && run.length >= open.second && suffix.isBlank()) fence = null
            } else if (!(run.first() == '`' && suffix.contains('`'))) fence = run.first() to run.length
        }
        if (fence == null) {
            val item = listItem(line)
            if (item != null) {
                val parent = parents.lastOrNull { it.column < item.column }
                val eligible = !wasFenced && candidate == null &&
                    (item.column < 4 || (parent != null && item.column - parent.contentColumn in 0..3))
                if (eligible) {
                    while (parents.isNotEmpty() && parents.last().column >= item.column) parents.removeAt(parents.lastIndex)
                    parents += item
                    if (retainHistory) items += item
                }
                return item.takeIf { eligible }
            } else {
                if (line.isNotBlank() && line.first() != ' ' && line.first() != '\t') {
                    items.clear()
                    parents.clear()
                }
            }
        }
        return null
    }
}
private fun precedingLists(source: String, start: Int): ListContext {
    val context = ListContext()
    source.substring(0, start).lineSequence().forEach(context::append)
    return context
}

private fun nextListNumber(items: List<ListItemPrefix>, column: Int): Int {
    for (prior in items.asReversed()) {
        if (prior.column < column) break
        if (prior.column == column) return prior.marker.dropLast(1).toIntOrNull()?.let { minOf(it + 1, 999_999_999) } ?: 1
    }
    return 1
}

/** Toggles a task checkbox when [offset] lands on its brackets or the gap after them; fenced or non-list text is ignored. */
fun taskToggleAt(source: String, offset: Int): SourceEdit? {
    if (offset < 0 || offset > source.length) return null
    val start = source.lastIndexOf('\n', offset - 1) + 1
    val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
    val line = source.substring(start, end).removeSuffix("\r")
    if ('[' !in line) return null // Skip the document-long list parse for taps on ordinary text.
    val context = precedingLists(source, start)
    val item = context.append(line) ?: return null
    if (!item.task) return null
    val bracket = start + item.indent.length + item.marker.length + item.gap.length
    if (offset < bracket || offset > start + item.length) return null
    val checked = source[bracket + 1] == ' '
    val selection = TextRange(offset)
    return SourceEdit(TextRange(bracket + 1, bracket + 2), if (checked) "x" else " ", selection)
}

/** A source replacement independent of the Android editor renderer. Offsets are UTF-16. */
data class SourceEdit(val range: TextRange, val replacement: String, val selection: TextRange)

/** Shift selected source lines, retaining smart nesting for selections of list items. */
fun sourceIndent(source: String, selection: TextRange, outdent: Boolean): SourceEdit? {
    if (selection.min < 0 || selection.max > source.length) return null
    listIndent(source, selection, outdent)?.let { return it }
    if (selection.collapsed) return null
    val start = source.lastIndexOf('\n', selection.min - 1) + 1
    val end = source.indexOf('\n', selection.max - 1).let { if (it < 0) source.length else it }
    val original = source.substring(start, end)
    var offset = start
    var delta = 0
    var mappedStart = selection.start
    var mappedEnd = selection.end
    val replacement = original.split('\n').joinToString("\n") { content ->
        val leading = content.takeWhile { it == ' ' || it == '\t' }
        val remove = if (outdent) { if (leading.startsWith('\t')) 1 else minOf(4, leading.takeWhile { it == ' ' }.length) } else 0
        val add = if (outdent) 0 else 4
        fun mapped(position: Int) = offset + delta + maxOf(0, position - offset - remove) + add
        if (selection.start in offset..offset + content.length) mappedStart = mapped(selection.start)
        if (selection.end in offset..offset + content.length) mappedEnd = mapped(selection.end)
        offset += content.length + 1
        delta += add - remove
        " ".repeat(add) + content.drop(remove)
    }
    if (selection.start > end) mappedStart = selection.start + delta
    if (selection.end > end) mappedEnd = selection.end + delta
    if (replacement == original) return null
    return SourceEdit(TextRange(start, end), replacement, TextRange(mappedStart, mappedEnd))
}

/** Match the Mac client's parent-content indentation and ordered sibling numbering. */
fun listIndent(source: String, selection: TextRange, outdent: Boolean): SourceEdit? {
    if (selection.min < 0 || selection.max > source.length) return null
    fun lineStart(offset: Int) = source.lastIndexOf('\n', offset - 1) + 1
    fun lineEnd(offset: Int): Int {
        val end = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
        return if (end > 0 && source[end - 1] == '\r') end - 1 else end
    }
    val start = lineStart(selection.min)
    val current = listItem(source.substring(start, lineEnd(selection.min))) ?: return null
    val context = precedingLists(source, start)
    val preceding = context.items
    if (context.inFence || (current.column >= 4 && preceding.none { it.column < current.column })) return null
    val previous = preceding.lastOrNull { it.column <= current.column }
    val parent = preceding.lastOrNull { it.column < current.column }
    val reference = previous ?: current
    val unit = " ".repeat(reference.contentColumn - reference.column)
    val remove = maxOf(0, current.indent.length - (parent?.indent?.length ?: maxOf(0, current.indent.length - unit.length)))
    val end = lineEnd(maxOf(selection.min, selection.max - 1))
    val original = source.substring(start, end)
    // A selection is one atomic list command. Never prefix plain text or enter a
    // fence opened later in the selected range, including a list-prefixed fence.
    if (original.lineSequence().any { listItem(it) == null || fencePrefix.containsMatchIn(it) }) return null
    var firstDelta = 0
    var totalDelta = 0
    val replacement = original.split('\n').mapIndexed { index, content ->
        val leading = content.takeWhile { it == ' ' || it == '\t' }
        var movedSource = if (outdent) content.drop(minOf(leading.length, if (leading.startsWith('\t')) 1 else remove))
            else unit + content
        val moved = listItem(movedSource)
        if (moved != null && moved.marker.dropLast(1).toIntOrNull() != null && leading.length != moved.indent.length) {
            val number = nextListNumber(preceding, moved.column)
            movedSource = moved.indent + number + moved.marker.last() + movedSource.drop(moved.indent.length + moved.marker.length)
        }
        listItem(movedSource)?.let(preceding::add)
        val delta = movedSource.length - content.length
        if (index == 0) firstDelta = delta
        totalDelta += delta
        movedSource
    }.joinToString("\n")
    if (replacement == original) return null
    val selectedStart = maxOf(start, selection.min + firstDelta)
    val selectedEnd = if (selection.collapsed) selectedStart else maxOf(selectedStart, selection.max + totalDelta)
    return SourceEdit(TextRange(start, end), replacement,
        if (selection.reversed) TextRange(selectedEnd, selectedStart) else TextRange(selectedStart, selectedEnd))
}

internal enum class SourceFormat(val label: String, val delimiter: String, val icon: Int) {
    BOLD("Bold", "**", R.drawable.ic_format_bold),
    ITALIC("Italic", "*", R.drawable.ic_format_italic),
    CODE("Inline code", "`", R.drawable.ic_code),
}

internal enum class SourceBlockFormat(val label: String, val marker: String) {
    HEADING_1("Heading 1", "# "),
    HEADING_2("Heading 2", "## "),
    HEADING_3("Heading 3", "### "),
    PARAGRAPH("Normal paragraph", ""),
    BULLETED("Bullet list", "- "),
    NUMBERED("Numbered list", "1. "),
    CHECKLIST("Checklist", "- [ ] ");

    val isList: Boolean get() = this == BULLETED || this == NUMBERED || this == CHECKLIST
}

/** Change paragraph markers without reflowing text or replacing explicit line endings. */
internal fun formatBlocks(source: String, selection: TextRange, format: SourceBlockFormat): SourceEdit? {
    if (selection.min < 0 || selection.max > source.length) return null
    val start = source.lastIndexOf('\n', selection.min - 1) + 1
    val last = maxOf(selection.min, selection.max - 1)
    val end = source.indexOf('\n', last).let { if (it < 0) source.length else it }
    val context = precedingLists(source, start)
    val original = source.substring(start, end)
    val lines = original.split('\n')
    val prefixes = lines.map { line ->
        val fenced = context.inFence || fencePrefix.containsMatchIn(line)
        val item = context.append(line)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val column = indent.fold(0) { count, char -> if (char == '\t') (count / 4 + 1) * 4 else count + 1 }
        if (fenced || (column >= 4 && item == null)) return null
        item
    }
    fun kind(item: ListItemPrefix?): SourceBlockFormat? = when {
        item == null -> null
        item.task -> SourceBlockFormat.CHECKLIST
        item.marker.first().isDigit() -> SourceBlockFormat.NUMBERED
        else -> SourceBlockFormat.BULLETED
    }
    val removeList = format.isList && prefixes.all { kind(it) == format }
    val headingMarker = Regex("^#{1,6}(?:[ \\t]+|$)")
    val preceding = precedingLists(source, start).items
    var originalOffset = start
    var replacementOffset = start
    var mappedStart = selection.start
    var mappedEnd = selection.end
    val replacement = lines.mapIndexed { index, line ->
        val item = prefixes[index]
        val indent = item?.indent ?: line.takeWhile { it == ' ' || it == '\t' }
        val contentOffset = item?.length ?: indent.length
        val headingLength = if (format.isList) 0 else headingMarker.find(line.substring(contentOffset))?.value?.length ?: 0
        val oldPrefixLength = contentOffset + headingLength
        val prefix = if (format.isList) {
            if (removeList) indent else indent + when (format) {
                SourceBlockFormat.NUMBERED -> {
                    val column = indent.fold(0) { count, char -> if (char == '\t') (count / 4 + 1) * 4 else count + 1 }
                    "${nextListNumber(preceding, column)}. "
                }
                SourceBlockFormat.CHECKLIST -> if (item?.task == true) line.substring(indent.length, contentOffset) else format.marker
                else -> format.marker
            }
        } else line.substring(0, contentOffset) + format.marker
        fun map(offset: Int): Int = replacementOffset + prefix.length + maxOf(0, offset - originalOffset - oldPrefixLength)
        if (selection.start in originalOffset..originalOffset + line.length) mappedStart = map(selection.start)
        if (selection.end in originalOffset..originalOffset + line.length) mappedEnd = map(selection.end)
        originalOffset += line.length + 1
        replacementOffset += prefix.length + line.length - oldPrefixLength + 1
        val moved = prefix + line.drop(oldPrefixLength)
        listItem(moved)?.let(preceding::add)
        moved
    }.joinToString("\n")
    if (replacement == original) return null
    return SourceEdit(TextRange(start, end), replacement, TextRange(mappedStart, mappedEnd))
}

/** Plan after a single native LF insertion. The range refers to the post-insertion buffer. */
internal fun continueList(source: String, cursor: Int): SourceEdit? {
    if (cursor !in 0..source.length) return null
    val start = source.lastIndexOf('\n', cursor - 1) + 1
    val end = source.indexOf('\n', cursor).let { if (it < 0) source.length else it }
    val line = source.substring(start, end)
    val marker = listItem(line) ?: return null
    if (cursor < start + marker.length) return null
    val context = precedingLists(source, start)
    if (context.inFence || (marker.column >= 4 && context.items.none { it.column < marker.column })) return null
    if (line.substring(marker.length).isBlank()) {
        val parent = context.items.lastOrNull { it.column < marker.column }?.next.orEmpty()
        return SourceEdit(TextRange(start, cursor + 1), parent, TextRange(start + parent.length))
    }
    val next = (if (source.contains("\r\n")) "\r\n" else "\n") + marker.next
    return SourceEdit(TextRange(cursor, cursor + 1), next, TextRange(cursor + next.length))
}

private data class SourceWrapper(val prefix: String, val suffix: String)

private fun sourceWrapper(selected: String, format: SourceFormat): SourceWrapper {
    var delimiter = format.delimiter
    var padding = ""
    if (format == SourceFormat.CODE) {
        delimiter = "`".repeat((Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0) + 1)
        if (selected.startsWith('`') || selected.endsWith('`') ||
            (selected.startsWith(' ') && selected.endsWith(' ') && selected.any { it != ' ' })) padding = " "
    }
    return SourceWrapper(delimiter + padding, padding + delimiter)
}

internal fun formatSource(source: String, selection: TextRange, format: SourceFormat): SourceEdit? {
    if (selection.collapsed || selection.min < 0 || selection.max > source.length) return null
    val selected = source.substring(selection.min, selection.max)
    val wrapper = sourceWrapper(selected, format)
    return SourceEdit(TextRange(selection.min, selection.max), wrapper.prefix + selected + wrapper.suffix,
        TextRange(selection.start + wrapper.prefix.length, selection.end + wrapper.prefix.length))
}

/** Toolbar commands also work at a caret, and toggle markers around the native selection. */
internal fun formatSourceAtSelection(source: String, selection: TextRange, format: SourceFormat): SourceEdit? {
    if (selection.min < 0 || selection.max > source.length) return null
    val selected = source.substring(selection.min, selection.max)
    val wrapper = sourceWrapper(selected, format)
    val marker = format.delimiter
    val before = selection.min - wrapper.prefix.length
    val after = selection.max + wrapper.suffix.length
    val exactRun = format == SourceFormat.BOLD ||
        ((before == 0 || source.getOrNull(before - 1) != marker.first()) && source.getOrNull(after) != marker.first())
    if (exactRun && before >= 0 && after <= source.length && source.substring(before, selection.min) == wrapper.prefix &&
        source.substring(selection.max, after) == wrapper.suffix) {
        return SourceEdit(TextRange(before, after), selected,
            TextRange(selection.start - wrapper.prefix.length, selection.end - wrapper.prefix.length))
    }
    if (!selection.collapsed) return formatSource(source, selection, format)
    return SourceEdit(selection, marker + marker, TextRange(selection.start + marker.length))
}

internal fun listPrefixLength(source: String, lineStart: Int): Int? {
    val end = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
    return listItem(source.substring(lineStart, end))?.length
}
