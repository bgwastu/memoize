import Foundation
import Testing
@testable import MemoizeCore

@Test func sharedLiteralSearchContractKeepsSourceAndUTF16Ranges() throws {
    struct Fixture: Decodable {
        let name: String
        let source: String
        let query: String
        let ranges: [[Int]]
    }
    let url = try #require(Bundle.module.url(forResource: "search-parity", withExtension: "json", subdirectory: "Fixtures"))
    let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: url))
    for fixture in fixtures {
        let actual = SearchText.ranges(in: fixture.source, query: fixture.query)
        let expected = fixture.ranges.map { NSRange(location: $0[0], length: $0[1]) }
        #expect(actual == expected, "\(fixture.name): expected \(expected), got \(actual)")
    }
}
