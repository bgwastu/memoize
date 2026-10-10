import Foundation
import Testing
@testable import MemoizeCore

@Test func newNotesArePrivate() {
    #expect(Note().visibility == "PRIVATE")
}
