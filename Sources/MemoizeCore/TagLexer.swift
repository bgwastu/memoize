// Tag recognition adapted from Memos 0.31.0 markdown/parser/tag.go (MIT).
// Copyright (c) 2025 Memos. See THIRD_PARTY_NOTICES.md.
import Foundation

struct TagLexer {
    struct Tables: Decodable, Sendable {
        let XIDContinue: [[UInt32]]
        let DefaultIgnorable: [[UInt32]]
        let CombiningMarks: [[UInt32]]
        let emoji: Set<String>
        let entities: Set<String>
        let entityValues: [String:String]
    }
    static let tables: Tables = {
        let url = Bundle.module.url(forResource:"TagData",withExtension:"json")!
        return try! JSONDecoder().decode(Tables.self,from:Data(contentsOf:url))
    }()
    static let emojiStarters = Set(tables.emoji.compactMap { $0.unicodeScalars.first?.value })
    static func contains(_ ranges: [[UInt32]], _ value: UInt32) -> Bool {
        var low = 0; var high = ranges.count
        while low < high {
            let middle = (low + high) / 2
            if value < ranges[middle][0] { high = middle }
            else if value > ranges[middle][1] { low = middle + 1 }
            else { return true }
        }; return false
    }
    let source: [Unicode.Scalar]
    let offsets: [Int]
    init(_ text: String) {
        source = Array(text.unicodeScalars)
        var offsets = [0]; for s in source { offsets.append(offsets.last! + (s.value > 0xFFFF ? 2 : 1)) }; self.offsets = offsets
    }
    func xid(_ i: Int) -> Bool { i < source.count && Self.contains(Self.tables.XIDContinue,source[i].value) }
    func combining(_ i: Int) -> Bool { Self.contains(Self.tables.CombiningMarks,source[i].value) }
    func ignored(_ i: Int) -> Bool { Self.contains(Self.tables.DefaultIgnorable,source[i].value) }
    func emoji(_ i: Int) -> Int {
        guard i < source.count, Self.emojiStarters.contains(source[i].value) else { return i }
        var candidate = ""; var end = i; var bytes = 0
        for p in i..<source.count {
            candidate.unicodeScalars.append(source[p]); bytes += source[p].utf8.count
            if bytes > 35 { break }
            if Self.tables.emoji.contains(candidate) { end = p + 1 }
        }; return end
    }
    func entity(_ i: Int) -> Int {
        guard source[i] == "&", i + 2 < source.count else { return i }
        let limit = min(source.count,i + 35)
        guard let end = (i + 1..<limit).first(where:{ source[$0] == ";" }) else { return i }
        let name = String(String.UnicodeScalarView(source[(i + 1)..<end]))
        if name.hasPrefix("#") {
            let hexadecimal = name.lowercased().hasPrefix("#x")
            let digits = name.dropFirst(hexadecimal ? 2 : 1)
            let allowed = hexadecimal ? "0123456789abcdefABCDEF" : "0123456789"
            if !digits.isEmpty && digits.count <= (hexadecimal ? 6 : 7) && digits.allSatisfy({ allowed.contains($0) }) { return end + 1 }
        } else if Self.tables.entities.contains(name) { return end + 1 }
        return i
    }
    func segment(_ start: Int) -> (Int,String)? {
        var i = start; var value = ""; var previousXID = false
        while i < source.count {
            if entity(i) > i { break }
            let e = emoji(i)
            if e > i { value += String(String.UnicodeScalarView(source[i..<e])); i = e; previousXID = false; continue }
            let s = source[i]
            if ignored(i) { i += 1; previousXID = false; continue }
            if value.isEmpty && combining(i) && xid(i) { i += 1; continue }
            if (s == "'" || s.value == 0x2019) && previousXID && i + 1 < source.count && xid(i + 1) && !combining(i + 1) && !ignored(i + 1) && emoji(i + 1) == i + 1 {
                value.unicodeScalars.append(s); i += 1; previousXID = false; continue
            }
            if s == "-" || s == "+" || s == "&" || (xid(i) && (!value.isEmpty || !combining(i))) {
                value.unicodeScalars.append(s); previousXID = xid(i); i += 1
            } else { break }
        }
        return value.isEmpty ? nil : (i,value)
    }
    func matches() -> [TagMatch] {
        var matches: [TagMatch] = []; var i = 0
        let punctuation = CharacterSet(charactersIn:"!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
        while i < source.count {
            let e = emoji(i); if e > i { i = e; continue }
            let r = entity(i); if r > i { i = r; continue }
            if source[i] == "\\" && i + 1 < source.count && punctuation.contains(source[i+1]) { i += 2; continue }
            guard source[i] == "#", var (end,value) = segment(i + 1) else { i += 1; continue }
            while end < source.count && source[end] == "/", let (next,part) = segment(end + 1) { end = next; value += "/" + part }
            matches.append(TagMatch(value:value,range:NSRange(location:offsets[i],length:offsets[end] - offsets[i])))
            i = end
        }
        return matches
    }
}
