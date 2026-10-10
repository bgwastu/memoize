import Foundation
import Markdown

/// Memos uses exact UTF-8 tag identities, including canonically equivalent Unicode spellings.
public func uniqueTags(_ values: [String]) -> [String] {
    var seen = Set<Data>()
    return values.filter { seen.insert(Data($0.utf8)).inserted }.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }
}

public actor MarkdownWorker {
    public init() { }
    public func analyze(_ source: String) -> MarkdownAnalysis? {
        guard !Task.isCancelled else { return nil }
        return MarkdownAnalysis.parse(source)
    }
}

public struct TagMatch: Sendable, Equatable {
    public let value: String
    public let range: NSRange
}
public struct Highlight: Sendable {
    public enum Style: Sendable { case heading, emphasis, strong, code, link, quote, tag, keyword, string, number, comment }
    public let range: NSRange
    public let style: Style
}
public struct ImageReference: Sendable, Equatable {
    public let destination: String
    public let range: NSRange
}
public struct MarkdownAnalysis: Sendable {
    public let highlights: [Highlight]
    public let tags: [TagMatch]
    public let images: [ImageReference]
    public let links: [ImageReference]
    public var tagValues: [String] {
        uniqueTags(tags.flatMap { tag -> [String] in
            var values: [String] = []; var prefix = ""
            for segment in tag.value.split(separator:"/") { prefix = prefix.isEmpty ? String(segment) : prefix + "/" + segment; values.append(prefix) }
            return values
        })
    }

    public static func parse(_ source: String) -> MarkdownAnalysis {
        let (prepared,mathBlocks) = MemosMarkdown.prepared(source)
        let document = Document(parsing:prepared)
        let ns = source as NSString
        // CommonMark reports UTF-8 columns; NSTextView uses UTF-16 offsets.
        var map = [Int](repeating:0,count:source.utf8.count + 1)
        var lines = [0]; var byte = 0; var utf16 = 0
        for scalar in source.unicodeScalars {
            for offset in 0..<scalar.utf8.count { map[byte + offset] = utf16 }
            byte += scalar.utf8.count; utf16 += scalar.value > 0xFFFF ? 2 : 1
            if scalar == "\n" { lines.append(byte) }
        }; map[byte] = utf16
        func range(_ node: any Markup) -> NSRange? {
            guard let r = node.range, r.lowerBound.line > 0, r.upperBound.line <= lines.count else { return nil }
            let start = min(map.count - 1,lines[r.lowerBound.line - 1] + r.lowerBound.column - 1)
            let end = min(map.count - 1,lines[r.upperBound.line - 1] + r.upperBound.column - 1)
            guard end >= start else { return nil }; return NSRange(location:map[start],length:map[end] - map[start])
        }
        var highlights: [Highlight] = []; var tags: [TagMatch] = []; var images: [ImageReference] = []; var links: [ImageReference] = []
        func visit(_ node: any Markup, tagsAllowed: Bool = true) {
            let r = range(node)
            let blocked = node is CodeBlock || node is InlineCode || node is Link || node is Image || node is InlineHTML || node is HTMLBlock
            if let r {
                var style: Highlight.Style?
                switch node {
                case is Heading: style = .heading
                case is Strong: style = .strong
                case is Emphasis: style = .emphasis
                case is BlockQuote: style = .quote
                case is CodeBlock, is InlineCode: style = .code
                case is Link, is Image: style = .link
                default: break
                }
                if let style { highlights.append(.init(range:r,style:style)) }
                if let code = node as? CodeBlock { highlights += CodeHighlights.parse(ns.substring(with:r),language:code.language ?? "",offset:r.location) }
                if let image = node as? Image, let destination = image.source { images.append(.init(destination:destination,range:r)) }
                if let link = node as? Link, let destination = link.destination { links.append(.init(destination:destination,range:r)) }
                if node is Markdown.Text && tagsAllowed && ns.range(of:"#",range:r).location != NSNotFound {
                    let opaque = MemosMarkdown.opaqueRanges(ns.substring(with:r),absoluteOffset:r.location,fullSource:ns).map { NSRange(location:r.location + $0.location,length:$0.length) } + mathBlocks
                    for literal in MemosMarkdown.subtract(opaque,from:r) {
                        for match in TagLexer(ns.substring(with:literal)).matches() {
                            let absolute = NSRange(location:literal.location + match.range.location,length:match.range.length)
                            tags.append(.init(value:match.value,range:absolute)); highlights.append(.init(range:absolute,style:.tag))
                        }
                    }
                }
            }
            for child in node.children { visit(child,tagsAllowed:tagsAllowed && !blocked) }
        }
        visit(document)
        return MarkdownAnalysis(highlights:highlights,tags:tags,images:images,links:links)
    }
}

private enum CodeHighlights {
    struct Rule: Sendable { let expression: NSRegularExpression; let style: Highlight.Style }
    static let patterns: [(String,Highlight.Style)] = [
        (#"\b(?:actor|async|await|break|case|catch|class|const|continue|def|defer|do|else|enum|export|extension|false|final|for|from|func|function|guard|if|import|in|init|interface|let|nil|null|package|private|protocol|public|raise|return|self|static|struct|switch|throw|throws|true|try|type|var|void|while|with|yield|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|JOIN|AND|OR|AS|INTO|VALUES)\b"#,.keyword),
        (#"\b\d+(?:\.\d+)?\b"#,.number),
        (#"\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'"#,.string),
        (#"(?m)//[^\n]*|/\*[\s\S]*?\*/"#,.comment),
    ]
    static let rules = patterns.map { Rule(expression:try! NSRegularExpression(pattern:$0.0),style:$0.1) }
    static let hashComments = try! NSRegularExpression(pattern:#"(?m)#[^\n]*"#)
    static func parse(_ source: String, language: String, offset: Int) -> [Highlight] {
        let supported = ["swift","js","javascript","ts","typescript","python","py","sh","bash","shell","zsh","json","yaml","yml","sql","go","rust","c","cpp"]
        guard supported.contains(language.lowercased()) else { return [] }
        let ns = source as NSString; let whole = NSRange(location:0,length:ns.length)
        var output: [Highlight] = []
        for rule in rules {
            for match in rule.expression.matches(in:source,range:whole) { output.append(.init(range:NSRange(location:offset + match.range.location,length:match.range.length),style:rule.style)) }
        }
        if ["python","py","sh","bash","shell","zsh","yaml","yml"].contains(language.lowercased()) {
            for match in hashComments.matches(in:source,range:whole) { output.append(.init(range:NSRange(location:offset + match.range.location,length:match.range.length),style:.comment)) }
        }
        return output
    }
}
