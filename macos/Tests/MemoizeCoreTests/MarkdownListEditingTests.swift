import XCTest
@testable import MemoizeCore

final class MarkdownListEditingTests: XCTestCase {
    private func enter(_ source: String) -> String? {
        guard let edit = MarkdownListEditing.newline(in:source,selection:NSRange(location:source.utf16.count,length:0)) else { return nil }
        return (source as NSString).replacingCharacters(in:edit.range,with:edit.replacement)
    }
    func testReturnContinuesListsAndResetsCheckedTasks() {
        XCTAssertEqual(enter("- One"),"- One\n- ")
        XCTAssertEqual(enter("+ One"),"+ One\n+ ")
        XCTAssertEqual(enter("* One"),"* One\n* ")
        XCTAssertEqual(enter("9. Nine"),"9. Nine\n10. ")
        XCTAssertEqual(enter("2) Two"),"2) Two\n3) ")
        XCTAssertEqual(enter("- [x] Done"),"- [x] Done\n- [ ] ")
        XCTAssertEqual(enter("- [X] Done"),"- [X] Done\n- [ ] ")
        XCTAssertEqual(enter("1. [ ] Task"),"1. [ ] Task\n2. [ ] ")
        XCTAssertEqual(enter("- 🐈 e\u{301}"),"- 🐈 e\u{301}\n- ")
        XCTAssertEqual(enter("- One\r\n- Two"),"- One\r\n- Two\r\n- ")
    }
    func testEmptyItemBacksOutOneLevelThenExits() {
        XCTAssertEqual(enter("- [ ] "),"")
        XCTAssertEqual(enter("3. "),"")
        XCTAssertEqual(enter("- Parent\n  - "),"- Parent\n- ")
        XCTAssertEqual(enter("1. Parent\n   - [ ] "),"1. Parent\n2. ")
        XCTAssertEqual(enter("- Parent\n  - Child\n    - "),"- Parent\n  - Child\n  - ")
    }
    func testDoesNotCompleteCodeOrThematicBreaks() {
        XCTAssertNil(enter("```md\n- Literal"))
        XCTAssertNil(enter("~~~\n1. Literal"))
        XCTAssertNil(enter("    - Indented code"))
        XCTAssertNil(enter("---"))
        XCTAssertNil(enter("* * *"))
        XCTAssertNil(enter("```\n```\n````\n- Still fenced"))
        XCTAssertEqual(enter("```\n- Code\n```\n- List"),"```\n- Code\n```\n- List\n- ")
        XCTAssertEqual(enter("- Parent\n    - Nested"),"- Parent\n    - Nested\n    - ")
    }
    func testTabUsesParentContentColumnAndPreservesSelection() throws {
        let source = "10. Parent\n11. Child 🐈"
        let selection = NSRange(location:source.utf16.count,length:0)
        let edit = try XCTUnwrap(MarkdownListEditing.indent(in:source,selection:selection,outdent:false))
        let indented = (source as NSString).replacingCharacters(in:edit.range,with:edit.replacement)
        XCTAssertEqual(indented,"10. Parent\n    1. Child 🐈")
        XCTAssertEqual(edit.selection.location,indented.utf16.count)
        let back = try XCTUnwrap(MarkdownListEditing.indent(in:indented,selection:edit.selection,outdent:true))
        XCTAssertEqual((indented as NSString).replacingCharacters(in:back.range,with:back.replacement),source)
        XCTAssertEqual(back.selection,selection)
        let tabbed = "1.\tParent\n2. Child"
        let nested = try XCTUnwrap(MarkdownListEditing.indent(in:tabbed,selection:NSRange(location:tabbed.utf16.count,length:0),outdent:false))
        XCTAssertEqual((tabbed as NSString).replacingCharacters(in:nested.range,with:nested.replacement),"1.\tParent\n    1. Child")
    }
    func testSelectionIndentAndMidlineReturn() throws {
        let source = "- First\n- Second\n- Third"
        let edit = try XCTUnwrap(MarkdownListEditing.indent(in:source,selection:NSRange(location:0,length:16),outdent:false))
        XCTAssertEqual((source as NSString).replacingCharacters(in:edit.range,with:edit.replacement),"  - First\n  - Second\n- Third")
        let split = "- Alpha beta"
        let newline = try XCTUnwrap(MarkdownListEditing.newline(in:split,selection:NSRange(location:8,length:0)))
        XCTAssertEqual((split as NSString).replacingCharacters(in:newline.range,with:newline.replacement),"- Alpha \n- beta")
        XCTAssertNil(MarkdownListEditing.newline(in:source,selection:NSRange(location:4,length:12)))
    }
    func testTaskToggleOnlyOnMarker() {
        let s = "- [ ] a\n- [x] b\n- c\n```\n- [ ] d\n```"
        XCTAssertEqual(MarkdownListEditing.toggleTask(in:s,at:3)?.replacement,"x")
        XCTAssertEqual(MarkdownListEditing.toggleTask(in:s,at:11)?.replacement," ")
        XCTAssertNil(MarkdownListEditing.toggleTask(in:s,at:7))
        XCTAssertNil(MarkdownListEditing.toggleTask(in:s,at:18))
        XCTAssertNil(MarkdownListEditing.toggleTask(in:s,at:30))
    }
}
