package net.wastu.memoize.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Source-aware Memos 0.31 tags. Syntax and exact Unicode identities match the Mac client. */
object MemosTags {
    private val parser = Parser.builder()
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

    fun extract(source: String): List<String> {
        if ('#' !in source) return emptyList()
        val prepared = MarkdownContexts.prepare(source)
        val tags = mutableSetOf<String>()
        fun visit(node: Node, allowed: Boolean) {
            val blocked = node is Code || node is FencedCodeBlock || node is IndentedCodeBlock ||
                node is Link || node is Image || node is HtmlInline || node is HtmlBlock
            if (node is Text && allowed) {
                for (span in node.sourceSpans) {
                    val range = Span(span.inputIndex, span.inputIndex + span.length)
                    val original = source.substring(range.start, range.end)
                    if ('#' !in original) continue
                    val opaque = MarkdownContexts.opaque(original, range.start, source)
                        .map { Span(range.start + it.start, range.start + it.end) } + prepared.excluded
                    for (literal in subtract(opaque, range)) {
                        for (tag in TagLexing(source.substring(literal.start, literal.end)).matches()) {
                            var prefix = ""
                            for (segment in tag.split('/')) {
                                prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
                                tags.add(prefix)
                            }
                        }
                    }
                }
            }
            var child = node.firstChild
            while (child != null) {
                visit(child, allowed && !blocked)
                child = child.next
            }
        }
        visit(parser.parse(prepared.source), true)
        return tags.sortedWith { a, b ->
            val left = a.toByteArray(Charsets.UTF_8)
            val right = b.toByteArray(Charsets.UTF_8)
            var comparison = 0
            for (i in 0 until minOf(left.size, right.size)) {
                comparison = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison == 0) left.size.compareTo(right.size) else comparison
        }
    }
}

private data class Span(val start: Int, val end: Int)

private fun subtract(exclusions: List<Span>, range: Span): List<Span> {
    var cursor = range.start
    val result = mutableListOf<Span>()
    for (excluded in exclusions.sortedBy { it.start }) {
        val start = maxOf(range.start, excluded.start)
        val end = minOf(range.end, excluded.end)
        if (end <= start) continue
        if (start > cursor) result.add(Span(cursor, start))
        cursor = maxOf(cursor, end)
    }
    if (cursor < range.end) result.add(Span(cursor, range.end))
    return result
}

// Adapted from Memos markdown/parser/tag.go and the Mac TagLexer (MIT).
private class TagLexing(text: String) {
    val source = text.codePoints().toArray()
    val offsets = IntArray(source.size + 1).also { result ->
        for (i in source.indices) result[i + 1] = result[i] + Character.charCount(source[i])
    }

    private fun xid(i: Int) = i < source.size && Tables.contains(Tables.xid, source[i])
    private fun combining(i: Int) = Tables.contains(Tables.combining, source[i])
    private fun ignored(i: Int) = Tables.contains(Tables.ignored, source[i])

    private fun emoji(i: Int): Int {
        if (i >= source.size || source[i] !in Tables.emojiStarters) return i
        val candidate = StringBuilder()
        var end = i
        var bytes = 0
        for (p in i until source.size) {
            val scalar = source[p]
            candidate.appendCodePoint(scalar)
            bytes += when {
                scalar < 0x80 -> 1
                scalar < 0x800 -> 2
                scalar < 0x10000 -> 3
                else -> 4
            }
            if (bytes > 35) break
            if (candidate.toString() in Tables.emoji) end = p + 1
        }
        return end
    }

    fun entity(i: Int): Int {
        if (source[i] != '&'.code || i + 2 >= source.size) return i
        val end = (i + 1 until minOf(source.size, i + 35)).firstOrNull { source[it] == ';'.code }
            ?: return i
        val name = String(source, i + 1, end - i - 1)
        if (name.startsWith('#')) {
            val hex = name.startsWith("#x", ignoreCase = true)
            val digits = name.drop(if (hex) 2 else 1)
            val allowed = if (hex) "0123456789abcdefABCDEF" else "0123456789"
            if (digits.isNotEmpty() && digits.length <= (if (hex) 6 else 7) &&
                digits.all { it in allowed }
            ) return end + 1
        } else if (name in Tables.entities) return end + 1
        return i
    }

    private fun segment(start: Int): Pair<Int, String>? {
        var i = start
        val value = StringBuilder()
        var previousXid = false
        while (i < source.size) {
            if (entity(i) > i) break
            val e = emoji(i)
            if (e > i) {
                value.append(String(source, i, e - i))
                i = e
                previousXid = false
                continue
            }
            val scalar = source[i]
            if (ignored(i)) {
                i++
                previousXid = false
                continue
            }
            if (value.isEmpty() && combining(i) && xid(i)) {
                i++
                continue
            }
            if ((scalar == '\''.code || scalar == 0x2019) && previousXid && i + 1 < source.size &&
                xid(i + 1) && !combining(i + 1) && !ignored(i + 1) && emoji(i + 1) == i + 1
            ) {
                value.appendCodePoint(scalar)
                i++
                previousXid = false
                continue
            }
            if (scalar == '-'.code || scalar == '+'.code || scalar == '&'.code ||
                (xid(i) && (value.isNotEmpty() || !combining(i)))
            ) {
                value.appendCodePoint(scalar)
                previousXid = xid(i)
                i++
            } else break
        }
        return if (value.isEmpty()) null else i to value.toString()
    }

    fun matches(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < source.size) {
            val e = emoji(i)
            if (e > i) { i = e; continue }
            val r = entity(i)
            if (r > i) { i = r; continue }
            if (source[i] == '\\'.code && i + 1 < source.size && source[i + 1] in punctuation) {
                i += 2
                continue
            }
            val first = if (source[i] == '#'.code) segment(i + 1) else null
            if (first == null) { i++; continue }
            var (end, value) = first
            while (end < source.size && source[end] == '/'.code) {
                val next = segment(end + 1) ?: break
                end = next.first
                value += "/" + next.second
            }
            result.add(value)
            i = end
        }
        return result
    }

    companion object {
        val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".map { it.code }.toSet()
    }
}

private object Tables {
    private val json = Tables::class.java.classLoader!!.getResourceAsStream("TagData.json")!!
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun ranges(key: String) = json.getValue(key).jsonArray.map {
        val pair = it.jsonArray
        pair[0].jsonPrimitive.content.toInt()..pair[1].jsonPrimitive.content.toInt()
    }
    val xid = ranges("XIDContinue")
    val combining = ranges("CombiningMarks")
    val ignored = ranges("DefaultIgnorable")
    val emoji = json.getValue("emoji").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val emojiStarters = emoji.map { it.codePointAt(0) }.toSet()
    val entities = json.getValue("entities").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val entityValues = json.getValue("entityValues").jsonObject.mapValues { it.value.jsonPrimitive.content }

    fun contains(ranges: List<IntRange>, value: Int): Boolean {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) / 2
            val range = ranges[middle]
            when {
                value < range.first -> high = middle
                value > range.last -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

// Memos-specific math and written GFM link rules; source remains canonical and editable.
private object MarkdownContexts {
    private val writtenUrl = Regex("(?:https?://|www\\.)[\\p{L}\\p{N}_.-]+[^<\\x09-\\x0d\\p{Z}]*")
    private val email = Regex("[A-Za-z0-9._+\\-]+@(?:[A-Za-z0-9_\\-]+\\.)+[A-Za-z0-9_\\-]*[A-Za-z0-9](?![A-Za-z0-9_\\-])")
    private val invalidReference = Regex("(?m)^ {0,3}\\[[^\\]\\n]+\\]:(?=\\([ \\t]*$)")
    private val listMarker = Regex("^(?:[-+*]|[0-9]+[.)])[ \\t]+")
    private data class Flow(val fence: Int, val quotes: Int, val indent: Int)
    private data class CodeFence(val character: Char, val count: Int)
    data class Prepared(val source: String, val excluded: List<Span>)

    fun prepare(source: String): Prepared {
        val excluded = mutableListOf<Span>()
        val masks = mutableListOf<Span>()
        var offset = 0
        var flow: Flow? = null
        var code: CodeFence? = null
        for (line in source.split('\n')) {
            var prefix = 0
            var quotes = 0
            while (prefix < line.length) {
                var p = prefix
                while (p < line.length && line[p] == ' ') p++
                if (p < line.length && line[p] == '>') {
                    quotes++
                    prefix = p + 1
                    if (prefix < line.length && line[prefix] == ' ') prefix++
                } else break
            }
            var indent = 0
            while (prefix + indent < line.length && line[prefix + indent] in " \t") indent++
            var bodyStart = prefix + indent
            listMarker.find(line.substring(bodyStart))?.let { bodyStart += it.value.length }
            val body = line.substring(bodyStart)
            val fence = body.takeWhile { it == '$' }.length
            val activeFlow = flow
            if (activeFlow != null) {
                val blank = line.trim(' ', '\t').isEmpty()
                if (quotes < activeFlow.quotes ||
                    (!blank && activeFlow.indent > 0 && indent < activeFlow.indent && quotes == activeFlow.quotes)
                ) flow = null
                else {
                    val range = Span(offset + bodyStart, offset + line.length)
                    excluded.add(range)
                    masks.add(range)
                    if (fence >= activeFlow.fence && body.drop(fence).trim(' ', '\t').isEmpty()) flow = null
                    offset += line.length + 1
                    continue
                }
            }
            val activeCode = code
            if (activeCode != null) {
                if (body.takeWhile { it == activeCode.character }.length >= activeCode.count) code = null
                offset += line.length + 1
                continue
            }
            val first = body.firstOrNull()
            if (first == '`' || first == '~') {
                val count = body.takeWhile { it == first }.length
                if (count >= 3) {
                    code = CodeFence(first, count)
                    offset += line.length + 1
                    continue
                }
            }
            if (indent < 4 && fence >= 2 && '$' !in body.drop(fence)) {
                flow = Flow(fence, quotes, if (bodyStart > prefix + indent) bodyStart - prefix else 0)
                val range = Span(offset + bodyStart, offset + line.length)
                excluded.add(range)
                masks.add(range)
            }
            offset += line.length + 1
        }
        for (match in invalidReference.findAll(source)) masks.add(Span(match.range.last, match.range.last + 1))
        if (masks.isEmpty()) return Prepared(source, emptyList())
        val result = source.toCharArray()
        for (mask in masks) for (i in mask.start until mask.end) result[i] = ' '
        return Prepared(String(result), excluded)
    }

    fun opaque(text: String, absoluteOffset: Int, fullSource: String): List<Span> {
        val output = mutableListOf<Span>()
        for (match in writtenUrl.findAll(text)) {
            val absolute = absoluteOffset + match.range.first
            val boundary = absolute == 0 || (absolute == 1 && fullSource[0] == '\uFEFF') ||
                (absolute > 0 && fullSource[absolute - 1] in " \t\n\u000b\u000c\r*_~(")
            if (!boundary) continue
            val label = match.value
            val hostStart = when {
                label.startsWith("https://") -> 8
                label.startsWith("http://") -> 7
                else -> 4
            }
            val domain = label.drop(hostStart).takeWhile { it.isLetterOrDigit() || it in "_.-" }
            val parts = domain.split('.')
            if (parts.size >= 2 && '_' !in parts.last() && '_' !in parts[parts.lastIndex - 1]) {
                output.add(Span(match.range.first, match.range.last + 1))
            }
        }
        val lexer = TagLexing(text)
        val projected = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        fun append(value: String, start: Int, end: Int) {
            projected.append(value)
            repeat(value.length) { starts.add(start); ends.add(end) }
        }
        var p = 0
        while (p < lexer.source.size) {
            val e = lexer.entity(p)
            if (e > p) {
                val spelling = String(lexer.source, p + 1, e - p - 2)
                val decoded = if (spelling.startsWith('#')) {
                    val hex = spelling.startsWith("#x", ignoreCase = true)
                    val scalar = spelling.drop(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)
                    if (scalar != null && Character.isValidCodePoint(scalar) && scalar !in 0xD800..0xDFFF)
                        String(Character.toChars(scalar)) else "�"
                } else Tables.entityValues[spelling] ?: "�"
                append(decoded, lexer.offsets[p], lexer.offsets[e])
                p = e
            } else if (lexer.source[p] == '\\'.code && p + 1 < lexer.source.size &&
                lexer.source[p + 1] in TagLexing.punctuation
            ) {
                append(String(Character.toChars(lexer.source[p + 1])), lexer.offsets[p], lexer.offsets[p + 2])
                p += 2
            } else {
                append(String(Character.toChars(lexer.source[p])), lexer.offsets[p], lexer.offsets[p + 1])
                p++
            }
        }
        for (match in email.findAll(projected)) {
            output.add(Span(starts[match.range.first], ends[match.range.last]))
        }
        var i = 0
        fun whitespace(c: Char) = c.isWhitespace()
        while (i < text.length) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] != '$') { i++; continue }
            var openEnd = i
            while (openEnd < text.length && text[openEnd] == '$') openEnd++
            val count = openEnd - i
            if (count == 1 && (openEnd == text.length || whitespace(text[openEnd]))) { i = openEnd; continue }
            var close = openEnd
            var found: Int? = null
            while (close < text.length) {
                if (text[close] != '$') { close++; continue }
                var end = close
                while (end < text.length && text[end] == '$') end++
                if (end - close == count) {
                    if (count == 1 && (close == 0 || whitespace(text[close - 1]) ||
                            (end < text.length && text[end] in '0'..'9'))
                    ) break
                    found = end
                    break
                }
                close = end
            }
            if (found != null) { output.add(Span(i, found)); i = found } else i = openEnd
        }
        return output
    }
}
