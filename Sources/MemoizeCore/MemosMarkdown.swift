// Compatibility with Memos 0.31's math and written GFM link rules.
// Adapted from Memos markdown/{parser,extensions} (MIT, Copyright (c) 2025 Memos).
import Foundation

enum MemosMarkdown {
    static let writtenURL = try! NSRegularExpression(pattern:#"(?:https?://|www\.)[\p{L}\p{N}_.-]+[^<\x09-\x0d\p{Z}]*"#)
    static let email = try! NSRegularExpression(pattern:#"[A-Za-z0-9._+\-]+@(?:[A-Za-z0-9_\-]+\.)+[A-Za-z0-9_\-]*[A-Za-z0-9](?![A-Za-z0-9_\-])"#)
    static let invalidReference = try! NSRegularExpression(pattern:#"(?m)^ {0,3}\[[^\]\n]+\]:(?=\([ \t]*$)"#)
    static let listMarker = try! NSRegularExpression(pattern:#"^(?:[-+*]|[0-9]+[.)])[ \t]+"#)

    /// Mask extension-only flow blocks before CommonMark determines list/paragraph boundaries.
    /// UTF-8 byte counts and newlines are preserved; the canonical source is never changed.
    static func prepared(_ source: String) -> (String,[NSRange]) {
        let ns = source as NSString
        var excluded: [NSRange] = []; var masks: [NSRange] = []
        var offset = 0; var flow: (fence:Int,quotes:Int,indent:Int)?; var code: (Character,Int)?
        for raw in source.split(separator:"\n",omittingEmptySubsequences:false) {
            let line = String(raw); let lineNS = line as NSString
            var prefix = 0; var quotes = 0
            while prefix < lineNS.length {
                var p = prefix
                while p < lineNS.length && lineNS.character(at:p) == 32 { p += 1 }
                if p < lineNS.length && lineNS.character(at:p) == 62 {
                    quotes += 1; prefix = p + 1; if prefix < lineNS.length && lineNS.character(at:prefix) == 32 { prefix += 1 }
                } else { break }
            }
            var indent = 0
            while prefix + indent < lineNS.length && [32,9].contains(lineNS.character(at:prefix + indent)) { indent += 1 }
            var bodyStart = prefix + indent
            let remainder = lineNS.substring(from:bodyStart)
            if let marker = listMarker.firstMatch(in:remainder,range:NSRange(location:0,length:(remainder as NSString).length)) { bodyStart += marker.range.length }
            let body = lineNS.substring(from:bodyStart)
            let fence = body.prefix(while:{ $0 == "$" }).count
            if let active = flow {
                let blank = line.trimmingCharacters(in:.whitespaces).isEmpty
                if quotes < active.quotes || (!blank && active.indent > 0 && indent < active.indent && quotes == active.quotes) { flow = nil }
                else {
                    let range = NSRange(location:offset + bodyStart,length:lineNS.length - bodyStart); excluded.append(range); masks.append(range)
                    if fence >= active.fence && body.dropFirst(fence).trimmingCharacters(in:.whitespaces).isEmpty { flow = nil }
                    offset += lineNS.length + 1; continue
                }
            }
            if let active = code {
                if body.prefix(while:{ $0 == active.0 }).count >= active.1 { code = nil }
                offset += lineNS.length + 1; continue
            }
            if let first = body.first, first == "`" || first == "~" {
                let count = body.prefix(while:{ $0 == first }).count
                if count >= 3 { code = (first,count); offset += lineNS.length + 1; continue }
            }
            if indent < 4 && fence >= 2 && !body.dropFirst(fence).contains("$") {
                flow = (fence,quotes,bodyStart > prefix + indent ? bodyStart - prefix : 0)
                let range = NSRange(location:offset + bodyStart,length:lineNS.length - bodyStart); excluded.append(range); masks.append(range)
            }
            offset += lineNS.length + 1
        }
        for match in invalidReference.matches(in:source,range:NSRange(location:0,length:ns.length)) { masks.append(NSRange(location:NSMaxRange(match.range)-1,length:1)) }
        guard !masks.isEmpty else { return (source,[]) }
        masks.sort { $0.location < $1.location }
        var result: [UInt8] = []; result.reserveCapacity(source.utf8.count)
        var position = 0; var index = 0
        for scalar in source.unicodeScalars {
            while index < masks.count && NSMaxRange(masks[index]) <= position { index += 1 }
            if index < masks.count && NSLocationInRange(position,masks[index]) { result.append(contentsOf:repeatElement(UInt8(32),count:scalar.utf8.count)) }
            else { result.append(contentsOf:scalar.utf8) }
            position += scalar.value > 0xFFFF ? 2 : 1
        }
        return (String(decoding:result,as:UTF8.self),excluded)
    }
    static func opaqueRanges(_ text: String, absoluteOffset: Int, fullSource: NSString) -> [NSRange] {
        let ns = text as NSString; let whole = NSRange(location:0,length:ns.length)
        var output: [NSRange] = []
        for match in writtenURL.matches(in:text,range:whole) {
            let absolute = absoluteOffset + match.range.location
            let boundary: Bool
            if absolute == 0 { boundary = true }
            else if absolute == 1 && fullSource.character(at:0) == 0xFEFF { boundary = true }
            else { boundary = [32,9,10,11,12,13,42,95,126,40].contains(fullSource.character(at:absolute - 1)) }
            guard boundary else { continue }
            let label = ns.substring(with:match.range)
            let hostStart = label.hasPrefix("https://") ? 8 : label.hasPrefix("http://") ? 7 : 4
            let domain = label.dropFirst(hostStart).unicodeScalars.prefix { CharacterSet.letters.contains($0) || CharacterSet.decimalDigits.contains($0) || "_.-".unicodeScalars.contains($0) }
            let parts = String(String.UnicodeScalarView(domain)).split(separator:".",omittingEmptySubsequences:false)
            if parts.count >= 2 && !parts[parts.count - 1].contains("_") && !parts[parts.count - 2].contains("_") { output.append(match.range) }
        }
        // Project escapes and HTML entities before recognizing written email addresses.
        let lexer = TagLexer(text); var projected = ""; var starts: [Int] = []; var ends: [Int] = []; var p = 0
        func append(_ value: String, start: Int, end: Int) {
            projected += value
            starts.append(contentsOf:repeatElement(start,count:value.utf16.count)); ends.append(contentsOf:repeatElement(end,count:value.utf16.count))
        }
        let punctuation = CharacterSet(charactersIn:"!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
        while p < lexer.source.count {
            let e = lexer.entity(p)
            if e > p {
                let spelling = String(String.UnicodeScalarView(lexer.source[(p+1)..<(e-1)]))
                let decoded: String
                if spelling.hasPrefix("#") {
                    let hex = spelling.lowercased().hasPrefix("#x")
                    decoded = UInt32(spelling.dropFirst(hex ? 2 : 1),radix:hex ? 16 : 10).flatMap(Unicode.Scalar.init).map(String.init) ?? "�"
                } else { decoded = TagLexer.tables.entityValues[spelling] ?? "�" }
                append(decoded,start:lexer.offsets[p],end:lexer.offsets[e]); p = e
            } else if lexer.source[p] == "\\" && p + 1 < lexer.source.count && punctuation.contains(lexer.source[p+1]) {
                append(String(lexer.source[p+1]),start:lexer.offsets[p],end:lexer.offsets[p+2]); p += 2
            } else { append(String(lexer.source[p]),start:lexer.offsets[p],end:lexer.offsets[p+1]); p += 1 }
        }
        for match in email.matches(in:projected,range:NSRange(location:0,length:(projected as NSString).length)) {
            output.append(NSRange(location:starts[match.range.location],length:ends[NSMaxRange(match.range)-1] - starts[match.range.location]))
        }
        // Inline math uses exact dollar runs; currency-like closing delimiters remain text.
        var i = 0
        func whitespace(_ unit: unichar) -> Bool { Unicode.Scalar(unit).map { CharacterSet.whitespacesAndNewlines.contains($0) } ?? false }
        while i < ns.length {
            if ns.character(at:i) == 92 { i += 2; continue }
            guard ns.character(at:i) == 36 else { i += 1; continue }
            var openEnd = i; while openEnd < ns.length && ns.character(at:openEnd) == 36 { openEnd += 1 }
            let count = openEnd - i
            if count == 1 && (openEnd == ns.length || whitespace(ns.character(at:openEnd))) { i = openEnd; continue }
            var close = openEnd; var found: Int?
            while close < ns.length {
                if ns.character(at:close) != 36 { close += 1; continue }
                var end = close; while end < ns.length && ns.character(at:end) == 36 { end += 1 }
                if end - close == count {
                    if count == 1 && (close == 0 || whitespace(ns.character(at:close - 1)) || (end < ns.length && (48...57).contains(ns.character(at:end)))) { break }
                    found = end; break
                }; close = end
            }
            if let end = found { output.append(NSRange(location:i,length:end - i)); i = end } else { i = openEnd }
        }
        return output
    }
    static func subtract(_ exclusions: [NSRange], from range: NSRange) -> [NSRange] {
        var cursor = range.location; var result: [NSRange] = []
        for exclusion in exclusions.sorted(by:{ $0.location < $1.location }) {
            let intersection = NSIntersectionRange(range,exclusion)
            if intersection.length == 0 { continue }
            if intersection.location > cursor { result.append(NSRange(location:cursor,length:intersection.location - cursor)) }
            cursor = max(cursor,NSMaxRange(intersection))
        }
        if cursor < NSMaxRange(range) { result.append(NSRange(location:cursor,length:NSMaxRange(range) - cursor)) }; return result
    }
}
