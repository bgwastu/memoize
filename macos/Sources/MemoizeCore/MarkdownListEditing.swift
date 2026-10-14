import Foundation

/// Source edits for native Return/Tab commands. Ranges are UTF-16, like NSTextView.
public enum MarkdownListEditing {
    public struct Edit: Sendable {
        public let range: NSRange
        public let replacement: String
        public let selection: NSRange
    }
    private struct Item {
        let indent: String
        let marker: String
        let gap: String
        let task: Bool
        let length: Int
        var column: Int { indent.reduce(0) { $1 == "\t" ? ($0 / 4 + 1) * 4 : $0 + 1 } }
        var contentColumn: Int { (indent + marker + gap).reduce(0) { $1 == "\t" ? ($0 / 4 + 1) * 4 : $0 + 1 } }
        var next: String {
            let nextMarker = Int(marker.dropLast()).map { String(min($0 + 1,999_999_999)) + marker.suffix(1) } ?? marker
            return indent + nextMarker + gap + (task ? "[ ] " : "")
        }
    }
    private static let prefix = try! NSRegularExpression(pattern:#"^([ \t]*)([-+*]|[0-9]{1,9}[.)])([ \t]+)(?:\[([ xX])\](?:[ \t]+|$))?"#)
    private static let fence = try! NSRegularExpression(pattern:#"^[ \t]*(?:>[ \t]*)?(?:[-+*][ \t]+|[0-9]{1,9}[.)][ \t]+)?(`{3,}|~{3,})(.*)$"#)
    private static let rule = try! NSRegularExpression(pattern:#"^[ \t]{0,3}(?:\*(?:[ \t]*\*){2,}|-(?:[ \t]*-){2,}|_(?:[ \t]*_){2,})[ \t]*$"#)
    private static func item(_ line: String) -> Item? {
        let ns = line as NSString, range = NSRange(location:0,length:line.utf16.count)
        guard rule.firstMatch(in:line,range:range) == nil, let match = prefix.firstMatch(in:line,range:range) else { return nil }
        return Item(indent:ns.substring(with:match.range(at:1)),marker:ns.substring(with:match.range(at:2)),gap:ns.substring(with:match.range(at:3)),task:match.range(at:4).location != NSNotFound,length:match.range.length)
    }
    private static func line(_ source: NSString, at offset: Int) -> NSRange {
        var start = 0, end = 0, contents = 0
        source.getLineStart(&start,end:&end,contentsEnd:&contents,for:NSRange(location:offset,length:0))
        return NSRange(location:start,length:contents - start)
    }
    private static func context(_ source: NSString, before offset: Int) -> (fenced: Bool, items: [Item]) {
        var open: (Character,Int)?
        var items: [Item] = []
        for line in source.substring(to:offset).components(separatedBy:.newlines) {
            let ns = line as NSString
            if let match = fence.firstMatch(in:line,range:NSRange(location:0,length:ns.length)) {
                let marker = ns.substring(with:match.range(at:1))
                if let active = open {
                    if marker.first == active.0, marker.count >= active.1,
                       ns.substring(with:match.range(at:2)).trimmingCharacters(in:.whitespaces).isEmpty { open = nil }
                } else if !(marker.first == "`" && ns.substring(with:match.range(at:2)).contains("`")) { open = (marker.first!,marker.count) }
            }
            if open == nil, let item = item(line) { items.append(item) }
            else if open == nil, !line.trimmingCharacters(in:.whitespaces).isEmpty,
                    line.first != " ", line.first != "\t" { items.removeAll(keepingCapacity:true) }
        }
        return (open != nil,items)
    }
    public static func newline(in source: String, selection: NSRange) -> Edit? {
        let ns = source as NSString
        guard selection.location != NSNotFound, NSMaxRange(selection) <= ns.length else { return nil }
        let range = line(ns,at:selection.location)
        guard NSMaxRange(selection) <= NSMaxRange(range), let current = item(ns.substring(with:range)),
              selection.location >= range.location + current.length else { return nil }
        let context = context(ns,before:range.location)
        guard !context.fenced, current.column < 4 || context.items.contains(where:{ $0.column < current.column }) else { return nil }
        if ns.substring(with:range).dropFirst(current.length).trimmingCharacters(in:.whitespaces).isEmpty {
            let replacement = context.items.last(where:{ $0.column < current.column })?.next ?? ""
            return Edit(range:range,replacement:replacement,selection:NSRange(location:range.location + replacement.utf16.count,length:0))
        }
        let newline = ns.range(of:"\r\n").location == NSNotFound ? "\n" : "\r\n"
        let replacement = newline + current.next
        return Edit(range:selection,replacement:replacement,selection:NSRange(location:selection.location + replacement.utf16.count,length:0))
    }
    /// Selected source lines can be shifted even outside a Markdown list.
    public static func selectionIndent(in source: String, selection: NSRange, outdent: Bool) -> Edit? {
        let ns = source as NSString
        guard selection.location != NSNotFound, selection.location >= 0, NSMaxRange(selection) <= ns.length else { return nil }
        if selection.length == 0 { return indent(in:source,selection:selection,outdent:outdent) }
        let first = line(ns,at:selection.location)
        let last = line(ns,at:NSMaxRange(selection) - 1)
        let range = NSRange(location:first.location,length:NSMaxRange(last) - first.location)
        let original = ns.substring(with:range)
        let lines = original.components(separatedBy:"\n")
        if lines.allSatisfy({ item($0) != nil && fence.firstMatch(in:$0,range:NSRange(location:0,length:$0.utf16.count)) == nil }),
           let edit = indent(in:source,selection:selection,outdent:outdent) { return edit }

        var offset = range.location, delta = 0
        var start = selection.location, end = NSMaxRange(selection)
        let replacement = lines.map { content in
            let leading = content.prefix { $0 == " " || $0 == "\t" }
            let remove = outdent ? (leading.first == "\t" ? 1 : min(4,leading.prefix { $0 == " " }.count)) : 0
            let add = outdent ? 0 : 4
            func mapped(_ position: Int) -> Int { offset + delta + max(0,position - offset - remove) + add }
            if selection.location >= offset, selection.location <= offset + content.utf16.count { start = mapped(selection.location) }
            if NSMaxRange(selection) >= offset, NSMaxRange(selection) <= offset + content.utf16.count { end = mapped(NSMaxRange(selection)) }
            offset += content.utf16.count + 1
            delta += add - remove
            return String(repeating:" ",count:add) + content.dropFirst(remove)
        }.joined(separator:"\n")
        if NSMaxRange(selection) > NSMaxRange(range) { end = NSMaxRange(selection) + delta }
        guard replacement != original else { return nil }
        return Edit(range:range,replacement:replacement,selection:NSRange(location:start,length:max(0,end - start)))
    }
    public static func indent(in source: String, selection: NSRange, outdent: Bool) -> Edit? {
        let ns = source as NSString
        guard selection.location != NSNotFound, NSMaxRange(selection) <= ns.length else { return nil }
        let first = line(ns,at:selection.location)
        guard let current = item(ns.substring(with:first)) else { return nil }
        let context = context(ns,before:first.location)
        guard !context.fenced, current.column < 4 || context.items.contains(where:{ $0.column < current.column }) else { return nil }
        let last = line(ns,at:max(selection.location,NSMaxRange(selection) - 1))
        let range = NSRange(location:first.location,length:NSMaxRange(last) - first.location)
        let previous = context.items.last(where:{ $0.column <= current.column })
        let parent = context.items.last(where:{ $0.column < current.column })
        let reference = previous ?? current
        let unit = String(repeating:" ",count:reference.contentColumn - reference.column)
        let remove = current.indent.count - (parent?.indent.count ?? max(0,current.indent.count - unit.count))
        var result = "", firstDelta = 0, totalDelta = 0
        var preceding = context.items
        for (index,content) in ns.substring(with:range).components(separatedBy:"\n").enumerated() {
            if index > 0 { result += "\n" }
            let leading = content.prefix { $0 == " " || $0 == "\t" }
            var replacement: String
            if outdent {
                let count = min(leading.count,leading.first == "\t" ? 1 : remove)
                replacement = String(content.dropFirst(count))
            } else { replacement = unit + content }
            if let moved = item(replacement), Int(moved.marker.dropLast()) != nil,
               leading.count != replacement.prefix(while:{ $0 == " " || $0 == "\t" }).count {
                // An ordered sublist must start at 1 to interrupt the parent
                // paragraph. Continue an existing sibling sequence at this level.
                var number = 1
                for prior in preceding.reversed() {
                    if prior.column < moved.column { break }
                    if prior.column == moved.column {
                        number = Int(prior.marker.dropLast()).map { min($0 + 1,999_999_999) } ?? 1
                        break
                    }
                }
                replacement = moved.indent + String(number) + moved.marker.suffix(1) + replacement.dropFirst(moved.indent.count + moved.marker.count)
            }
            if let moved = item(replacement) { preceding.append(moved) }
            result += replacement
            let delta = replacement.utf16.count - content.utf16.count
            if index == 0 { firstDelta = delta }
            totalDelta += delta
        }
        let start = max(first.location,selection.location + firstDelta)
        let end = max(start,NSMaxRange(selection) + totalDelta)
        return Edit(range:range,replacement:result,selection:NSRange(location:start,length:selection.length == 0 ? 0 : end - start))
    }
}
