import Foundation

/// Keep insertion points through native text edits instead of replacing a later buffer.
public struct ReferenceRestoration: Sendable {
    public struct Insertion: Sendable, Equatable {
        public var location: Int
        public let text: String
    }
    public var source: String
    public let ranges: [NSRange]
    private var fragments: [Insertion]

    public init(source: String, ranges: [NSRange]) {
        let text = source as NSString
        var merged: [NSRange] = []
        for range in ranges.sorted(by:{ $0.location < $1.location }) where range.location >= 0 && range.location != NSNotFound && range.length > 0 && range.location <= text.length && range.length <= text.length - range.location {
            if let last = merged.last, range.location <= NSMaxRange(last) {
                merged[merged.count - 1] = NSUnionRange(last,range)
            } else { merged.append(range) }
        }
        self.ranges = merged
        var removed = 0; fragments = []
        for range in merged {
            fragments.append(Insertion(location:range.location - removed,text:text.substring(with:range)))
            removed += range.length
        }
        let remaining = NSMutableString(string:source)
        for range in merged.reversed() { remaining.deleteCharacters(in:range) }
        self.source = remaining as String
    }
    public mutating func applyEdit(_ range: NSRange, replacementLength: Int) {
        for index in fragments.indices {
            let location = fragments[index].location
            if location > NSMaxRange(range) { fragments[index].location += replacementLength - range.length }
            else if location > range.location {
                fragments[index].location = range.location + (location == NSMaxRange(range) ? replacementLength : 0)
            }
        }
    }
    public func insertions(into current: String) -> [Insertion] {
        if sameSource(source,current) { return fragments }
        // A background replacement wasn't a native edit. Preserve every current
        // character and restore the links at the end rather than guessing positions.
        guard !fragments.isEmpty else { return [] }
        let separator = current.isEmpty || current.hasSuffix("\n") ? "" : "\n"
        return [Insertion(location:(current as NSString).length,text:separator + fragments.map(\.text).joined(separator:"\n"))]
    }
}
