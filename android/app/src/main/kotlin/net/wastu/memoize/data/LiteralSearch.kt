package net.wastu.memoize.data

import android.icu.lang.UCharacter

/** Literal case folding with source boundaries: never match half of an expansion or surrogate. */
object LiteralSearch {
    private data class Folded(val text: String, val starts: IntArray? = null, val ends: IntArray? = null)
    internal data class Query(val text: String, val folded: String, val indexExpression: String?)

    internal fun prepare(query: String): Query {
        val value = folded(query)
        val expression = if (value.codePointCount(0, value.length) >= 3 && !value.contains('\u0000'))
            "\"${value.replace("\"", "\"\"")}\"" else null
        return Query(query, value, expression)
    }

    fun folded(source: String): String {
        val text = StringBuilder(source.length)
        var start = 0
        var position = 0
        while (position < source.length) {
            val scalar = source.codePointAt(position)
            val length = Character.charCount(scalar)
            if (length > 1) {
                text.append(UCharacter.foldCase(source.substring(start, position), true))
                text.append(source, position, position + length)
                start = position + length
            }
            position += length
        }
        text.append(UCharacter.foldCase(source.substring(start), true))
        return text.toString()
    }

    private fun fold(source: String, complete: String = folded(source)): Folded {
        // Full BMP folds map each scalar to one or more UTF-16 units. When the length is
        // unchanged, offsets are identical; supplementary scalars were copied literally.
        if (complete.length == source.length) return Folded(complete)
        val text = StringBuilder(source.length)
        var starts = IntArray(source.length + 1) { -1 }
        var ends = IntArray(source.length + 1) { -1 }
        var position = 0
        while (position < source.length) {
            val scalar = source.codePointAt(position)
            val length = Character.charCount(scalar)
            // Foundation's literal matcher folds BMP characters, not supplementary letters.
            val value = source.substring(position, position + length)
            val expansion = if (scalar <= 0xffff) UCharacter.foldCase(value, true) else value
            if (text.length + expansion.length >= starts.size) {
                val size = maxOf(text.length + expansion.length + 1, starts.size * 2)
                val old = starts.size
                starts = starts.copyOf(size); ends = ends.copyOf(size)
                starts.fill(-1, old); ends.fill(-1, old)
            }
            starts[text.length] = position
            text.append(expansion)
            position += length
            ends[text.length] = position
        }
        return Folded(text.toString(), starts, ends)
    }

    fun ranges(source: String, query: String): List<TextMatch> {
        return ranges(source, prepare(query))
    }

    private fun ranges(source: String, query: Query, indexedSource: String? = null): List<TextMatch> {
        if (query.text.isEmpty()) return emptyList()
        val body = if (indexedSource == null) fold(source) else fold(source, indexedSource)
        val needle = query.folded
        if (needle.isEmpty()) return emptyList()
        val matches = ArrayList<TextMatch>()
        var position = 0
        while (position <= body.text.length - needle.length) {
            val start = body.text.indexOf(needle, position)
            if (start < 0) break
            val end = start + needle.length
            val sourceStart = body.starts?.get(start) ?: sourceOffset(source, start)
            val sourceEnd = body.ends?.get(end) ?: sourceOffset(source, end)
            if (sourceStart >= 0 && sourceEnd >= 0) {
                matches += TextMatch(sourceStart, sourceEnd)
                position = end
            } else position = start + 1
        }
        return matches
    }

    private fun sourceOffset(source: String, offset: Int): Int =
        if (offset > 0 && offset < source.length && Character.isHighSurrogate(source[offset - 1]) &&
            Character.isLowSurrogate(source[offset])) -1 else offset

    fun result(note: Note, query: String): NoteResult {
        return result(NotePreview(note.id, note.account, note.content, note.createdAt, note.updatedAt,
            note.state, note.remoteName, note.error, note.attachments, note.tags), prepare(query))
    }

    /** The repository's persisted fold avoids scanning each committed body again per query. */
    internal fun result(note: NotePreview, query: Query, indexedSource: String? = null): NoteResult {
        val matches = ranges(note.content, query, indexedSource)
        val first = matches.firstOrNull()?.start ?: 0
        var start = if (query.text.isEmpty()) {
            val firstLine = note.content.indexOfFirst { !it.isWhitespace() }
            val lineEnd = note.content.indexOf('\n', maxOf(0, firstLine))
            if (lineEnd < 0) note.content.length else lineEnd + 1
        } else maxOf(0, first - 16, if (first == 0) 0 else note.content.lastIndexOf('\n', first - 1) + 1)
        var end = minOf(note.content.length, maxOf(first + query.text.length + 96, start + 180))
        if (start in 1 until note.content.length && Character.isLowSurrogate(note.content[start])) start--
        if (end < note.content.length && Character.isLowSurrogate(note.content[end])) end--
        return NoteResult(note, note.content.substring(start, end), start, matches)
    }

    internal fun indexQuery(query: String): String? = prepare(query).indexExpression
}
