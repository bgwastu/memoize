import Foundation
import Testing
@testable import MemoizeCore

@Test func memosTagCompatibilityFixtures() throws {
    struct Fixture: Decodable { let name: String; let content: String; let expected: [String] }
    let url = try #require(Bundle.module.url(forResource:"memos-tags",withExtension:"json",subdirectory:"Fixtures"))
    let fixtures = try JSONDecoder().decode([Fixture].self,from:Data(contentsOf:url))
    for fixture in fixtures {
        let actual = MarkdownAnalysis.parse(fixture.content).tagValues
        let expected = uniqueTags(fixture.expected)
        #expect(actual.map { Data($0.utf8) } == expected.map { Data($0.utf8) }, "\(fixture.name): expected \(expected), got \(actual)")
    }
}
