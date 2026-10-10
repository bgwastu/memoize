import Foundation

public enum SearchText {
    /// Literal, case-insensitive UTF-16 ranges, suitable for AppKit text attributes.
    public static func ranges(in source: String, query: String) -> [NSRange] {
        guard !query.isEmpty else { return [] }
        let text = source as NSString
        var remaining = NSRange(location:0,length:text.length)
        var matches: [NSRange] = []
        while remaining.length > 0 {
            let match = text.range(of:query,options:[.caseInsensitive,.literal],range:remaining)
            guard match.location != NSNotFound, match.length > 0 else { break }
            matches.append(match)
            remaining = NSRange(location:NSMaxRange(match),length:text.length - NSMaxRange(match))
        }
        return matches
    }
}
