import Foundation

/// Monaco-style whole-line commands. Ranges are UTF-16, like NSTextView.
public enum MarkdownLineEditing {
    public typealias Edit = MarkdownListEditing.Edit

    /// Full lines touched by the selection, without the final line break. A selection
    /// ending at a line start leaves that line out, as Monaco does.
    private static func block(_ source: NSString, _ selection: NSRange) -> NSRange? {
        guard selection.location != NSNotFound, NSMaxRange(selection) <= source.length else { return nil }
        var end = NSMaxRange(selection)
        if selection.length > 0, end > 0, source.character(at:end - 1) == 10 { end -= 1 }
        let start = source.lineRange(for:NSRange(location:selection.location,length:0)).location
        var contentsEnd = 0
        source.getLineStart(nil,end:nil,contentsEnd:&contentsEnd,for:NSRange(location:max(start,end),length:0))
        return NSRange(location:start,length:contentsEnd - start)
    }
    private static func shifted(_ selection: NSRange, by delta: Int) -> NSRange {
        NSRange(location:selection.location + delta,length:selection.length)
    }

    public static func moveLines(in source: String, selection: NSRange, up: Bool) -> Edit? {
        let ns = source as NSString
        guard let lines = block(ns,selection) else { return nil }
        let text = ns.substring(with:lines)
        if up {
            guard lines.location > 0 else { return nil }
            let previous = ns.lineRange(for:NSRange(location:lines.location - 1,length:0))
            let above = ns.substring(with:NSRange(location:previous.location,length:lines.location - 1 - previous.location))
            return Edit(range:NSRange(location:previous.location,length:NSMaxRange(lines) - previous.location),
                        replacement:text + "\n" + above,selection:shifted(selection,by:-(above.utf16.count + 1)))
        }
        guard NSMaxRange(lines) < ns.length else { return nil }
        var nextEnd = 0
        ns.getLineStart(nil,end:nil,contentsEnd:&nextEnd,for:NSRange(location:NSMaxRange(lines) + 1,length:0))
        let below = ns.substring(with:NSRange(location:NSMaxRange(lines) + 1,length:nextEnd - NSMaxRange(lines) - 1))
        return Edit(range:NSRange(location:lines.location,length:nextEnd - lines.location),
                    replacement:below + "\n" + text,selection:shifted(selection,by:below.utf16.count + 1))
    }

    public static func copyLines(in source: String, selection: NSRange, down: Bool) -> Edit? {
        let ns = source as NSString
        guard let lines = block(ns,selection) else { return nil }
        let text = ns.substring(with:lines)
        // Copying down selects the new lower copy; copying up keeps the selection on the upper one.
        if down { return Edit(range:NSRange(location:NSMaxRange(lines),length:0),replacement:"\n" + text,selection:shifted(selection,by:text.utf16.count + 1)) }
        return Edit(range:NSRange(location:lines.location,length:0),replacement:text + "\n",selection:selection)
    }

    public static func deleteLines(in source: String, selection: NSRange) -> Edit? {
        let ns = source as NSString
        guard let lines = block(ns,selection) else { return nil }
        let column = selection.location - lines.location
        if NSMaxRange(lines) < ns.length {
            let removed = NSRange(location:lines.location,length:lines.length + 1)
            var nextEnd = 0
            ns.getLineStart(nil,end:nil,contentsEnd:&nextEnd,for:NSRange(location:NSMaxRange(removed),length:0))
            return Edit(range:removed,replacement:"",selection:NSRange(location:lines.location + min(column,nextEnd - NSMaxRange(removed)),length:0))
        }
        // The last line takes the preceding break with it and leaves the caret on the line above.
        let start = max(0,lines.location - 1)
        let previous = ns.lineRange(for:NSRange(location:start,length:0))
        return Edit(range:NSRange(location:start,length:NSMaxRange(lines) - start),replacement:"",
                    selection:NSRange(location:previous.location + min(column,start - previous.location),length:0))
    }

    public static func insertLine(in source: String, selection: NSRange, above: Bool) -> Edit? {
        let ns = source as NSString
        guard let lines = block(ns,selection) else { return nil }
        let first = ns.substring(with:ns.lineRange(for:NSRange(location:lines.location,length:0)))
        let indent = String(first.prefix { $0 == " " || $0 == "\t" })
        let length = indent.utf16.count
        if above { return Edit(range:NSRange(location:lines.location,length:0),replacement:indent + "\n",selection:NSRange(location:lines.location + length,length:0)) }
        return Edit(range:NSRange(location:NSMaxRange(lines),length:0),replacement:"\n" + indent,selection:NSRange(location:NSMaxRange(lines) + 1 + length,length:0))
    }

    /// Selects whole lines, including their line break; repeating extends by one line.
    public static func selectLine(in source: String, selection: NSRange) -> NSRange? {
        let ns = source as NSString
        guard selection.location != NSNotFound, NSMaxRange(selection) <= ns.length else { return nil }
        let start = ns.lineRange(for:NSRange(location:selection.location,length:0)).location
        let end = NSMaxRange(selection)
        let whole = selection.length > 0 && selection.location == start && (end == ns.length || ns.lineRange(for:NSRange(location:end,length:0)).location == end)
        let anchor = whole ? end : (selection.length > 0 && end > 0 && ns.character(at:end - 1) == 10 ? end - 1 : end)
        let next = whole ? (end < ns.length ? NSMaxRange(ns.lineRange(for:NSRange(location:end,length:0))) : end)
                         : NSMaxRange(ns.lineRange(for:NSRange(location:min(anchor,ns.length),length:0)))
        return NSRange(location:start,length:next - start)
    }

    private static let listItem = try! NSRegularExpression(pattern:#"^[ \t]*(?:[-+*]|[0-9]{1,9}[.)])(?:[ \t]|$)"#)
    /// List lines keep Markdown nesting; other lines shift by four spaces.
    public static func indent(in source: String, selection: NSRange, outdent: Bool) -> Edit? {
        if let edit = MarkdownListEditing.selectionIndent(in:source,selection:selection,outdent:outdent) { return edit }
        let ns = source as NSString
        guard selection.length == 0, let line = block(ns,selection) else { return nil }
        let content = ns.substring(with:line)
        // A list item the list rules refuse to nest stays put rather than becoming a code block.
        guard listItem.firstMatch(in:content,range:NSRange(location:0,length:line.length)) == nil else { return nil }
        if !outdent { return Edit(range:NSRange(location:line.location,length:0),replacement:"    ",selection:shifted(selection,by:4)) }
        let leading = content.prefix { $0 == " " || $0 == "\t" }
        let remove = leading.first == "\t" ? 1 : min(4,leading.prefix { $0 == " " }.count)
        guard remove > 0 else { return nil }
        return Edit(range:NSRange(location:line.location,length:remove),replacement:"",
                    selection:NSRange(location:max(line.location,selection.location - remove),length:0))
    }
}
