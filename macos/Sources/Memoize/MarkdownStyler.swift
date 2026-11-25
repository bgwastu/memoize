import AppKit
import MemoizeCore

/// Applies Markdown analysis to a TextKit 1 view without rewriting the document.
///
/// Colors and underlines are layout-manager temporary attributes: they never
/// edit storage, reflow text, or touch Undo. Links and list hanging indents must
/// live in storage, so only the ranges whose values actually differ are changed.
@MainActor enum MarkdownStyler {
    /// Returns true when storage attributes that can change line geometry were edited.
    @discardableResult
    static func apply(_ result: MarkdownAnalysis, to view: NSTextView, paragraph: NSParagraphStyle) -> Bool {
        guard let storage = view.textStorage, let layout = view.layoutManager else { return false }
        let length = storage.length
        let full = NSRange(location:0,length:length)
        func clamp(_ range: NSRange) -> NSRange? {
            guard range.location != NSNotFound, range.location < length, range.length > 0 else { return nil }
            return NSRange(location:range.location,length:min(range.length,length - range.location))
        }

        // Storage: links, tooltips, and list hanging indents.
        var links: [(NSRange,AnyHashable)] = result.tags.compactMap { tag in clamp(tag.range).map { ($0,"memoize-tag:" + tag.value) } }
        var tips: [(NSRange,AnyHashable)] = []
        for link in result.links {
            guard let range = clamp(link.range) else { continue }
            links.append((range,link.destination)); tips.append((range,"⌘-click to open " + link.destination))
        }
        let source = storage.string as NSString
        let indents: [(NSRange,AnyHashable)] = result.listParagraphs.compactMap { list in
            guard let range = clamp(list.range), NSMaxRange(list.prefix) <= length else { return nil }
            // Keep the first line's source indentation; align soft wraps after its prefix.
            // Paragraph styles always cover whole paragraphs, including the line break.
            let width = paragraph.firstLineHeadIndent + storage.attributedSubstring(from:list.prefix).size().width
            return (source.paragraphRange(for:range),Double(width))
        }
        let linkFixes = differences(storage,.link,desired:links) { $0 as? String }
        let tipFixes = differences(storage,.toolTip,desired:tips) { $0 as? String }
        let indentFixes = differences(storage,.paragraphStyle,desired:indents) { value in
            guard let style = value as? NSParagraphStyle, style.headIndent != paragraph.headIndent else { return nil }
            return Double(style.headIndent)
        }
        if !linkFixes.isEmpty || !tipFixes.isEmpty || !indentFixes.isEmpty {
            storage.beginEditing()
            for (range,value) in linkFixes {
                if let value { storage.addAttribute(.link,value:value,range:range) } else { storage.removeAttribute(.link,range:range) }
            }
            for (range,value) in tipFixes {
                if let value { storage.addAttribute(.toolTip,value:value,range:range) } else { storage.removeAttribute(.toolTip,range:range) }
            }
            for (range,value) in indentFixes {
                let style = paragraph.mutableCopy() as! NSMutableParagraphStyle
                if let width = value as? Double { style.headIndent = CGFloat(width) }
                storage.addAttribute(.paragraphStyle,value:style,range:range)
            }
            storage.endEditing()
        }

        // Rendering only: never edits storage. Spelling marks use their own key.
        layout.removeTemporaryAttribute(.foregroundColor,forCharacterRange:full)
        layout.removeTemporaryAttribute(.underlineStyle,forCharacterRange:full)
        for span in result.highlights {
            guard let range = clamp(span.range) else { continue }
            layout.addTemporaryAttribute(.foregroundColor,value:color(span.style),forCharacterRange:range)
        }
        for link in result.links {
            guard let range = clamp(link.range) else { continue }
            layout.addTemporaryAttribute(.underlineStyle,value:NSUnderlineStyle.single.rawValue,forCharacterRange:range)
        }
        return !indentFixes.isEmpty
    }

    static func color(_ style: Highlight.Style) -> NSColor {
        switch style {
        case .heading, .link, .tag: .controlAccentColor
        case .strong, .emphasis, .keyword: .systemPurple
        case .code, .quote, .comment: .secondaryLabelColor
        case .string: .systemBrown
        case .number: .systemBlue
        }
    }

    /// The minimal ranges where `key` differs from `desired` (later ranges lose overlaps; gaps mean no value).
    static func differences(_ storage: NSTextStorage, _ key: NSAttributedString.Key, desired input: [(NSRange,AnyHashable)],
                            current: (Any?) -> AnyHashable?) -> [(NSRange,AnyHashable?)] {
        var desired: [(NSRange,AnyHashable)] = []
        for (range,value) in input.sorted(by:{ $0.0.location < $1.0.location }) {
            var range = range
            if let last = desired.last, NSMaxRange(last.0) > range.location {
                let end = NSMaxRange(range); range.location = NSMaxRange(last.0); range.length = max(0,end - range.location)
            }
            if range.length > 0 { desired.append((range,value)) }
        }
        var fixes: [(NSRange,AnyHashable?)] = []
        func record(_ range: NSRange, _ value: AnyHashable?) {
            if let last = fixes.last, NSMaxRange(last.0) == range.location, last.1 == value {
                fixes[fixes.count - 1].0.length += range.length
            } else { fixes.append((range,value)) }
        }
        var index = 0
        storage.enumerateAttribute(key,in:NSRange(location:0,length:storage.length),options:[]) { value,run,_ in
            let existing = current(value)
            var position = run.location
            let end = NSMaxRange(run)
            while position < end {
                while index < desired.count, NSMaxRange(desired[index].0) <= position { index += 1 }
                let wanted: AnyHashable?; let stop: Int
                if index < desired.count, desired[index].0.location <= position {
                    wanted = desired[index].1; stop = min(end,NSMaxRange(desired[index].0))
                } else {
                    wanted = nil; stop = index < desired.count ? min(end,desired[index].0.location) : end
                }
                if wanted != existing { record(NSRange(location:position,length:stop - position),wanted) }
                position = stop
            }
        }
        return fixes
    }
}
