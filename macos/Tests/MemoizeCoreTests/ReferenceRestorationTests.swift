import Foundation
import Testing
@testable import MemoizeCore

private func apply(_ restoration: inout ReferenceRestoration, range: NSRange, text: String) {
    let changed = NSMutableString(string:restoration.source)
    changed.replaceCharacters(in:range,with:text)
    restoration.applyEdit(range,replacementLength:(text as NSString).length)
    restoration.source = changed as String
}
private func restored(_ restoration: ReferenceRestoration, in source: String? = nil) -> String {
    let current = source ?? restoration.source
    let changed = NSMutableString(string:current)
    for insertion in restoration.insertions(into:current).reversed() { changed.insert(insertion.text,at:insertion.location) }
    return changed as String
}

@Test func attachmentUndoPreservesLaterTypingAndUTF16Positions() {
    let reference = "![cat](https://example.com/file/attachments/cat)"
    let original = "before" + reference + "after"
    var restoration = ReferenceRestoration(source:original,ranges:[(original as NSString).range(of:reference)])
    apply(&restoration,range:NSRange(location:0,length:0),text:"🐈 ")
    apply(&restoration,range:NSRange(location:(restoration.source as NSString).length,length:0),text:"\nnew words")
    apply(&restoration,range:(restoration.source as NSString).range(of:"before"),text:"Edited introduction")
    #expect(restored(restoration) == "🐈 Edited introduction" + reference + "after\nnew words")
}

@Test func attachmentUndoPreservesReplacementAcrossItsOriginalPosition() {
    let reference = "[file](attachment)"
    let original = "left" + reference + "right"
    var restoration = ReferenceRestoration(source:original,ranges:[(original as NSString).range(of:reference)])
    apply(&restoration,range:NSRange(location:0,length:(restoration.source as NSString).length),text:"Replacement 🧑🏽‍💻")
    #expect(restored(restoration) == reference + "Replacement 🧑🏽‍💻")
    apply(&restoration,range:NSRange(location:0,length:0),text:"new prefix ")
    #expect(restored(restoration) == reference + "new prefix Replacement 🧑🏽‍💻")
}

@Test func multipleAttachmentReferencesRestoreExactlyAfterAnInterveningEdit() {
    let first = "![a](same)"; let second = "[download](same)"
    let original = "🧑🏽‍💻 " + first + " middle " + second + " #cafe\u{301}"
    var restoration = ReferenceRestoration(source:original,ranges:[(original as NSString).range(of:first),(original as NSString).range(of:second)])
    apply(&restoration,range:(restoration.source as NSString).range(of:"middle"),text:"changed middle")
    #expect(sameSource(restored(restoration),original.replacingOccurrences(of:"middle",with:"changed middle")))
}

@Test func nestedAttachmentReferencesAreRemovedAndRestoredOnce() {
    let reference = "[![image](same)](same)"
    let original = "before " + reference + " after"
    let restoration = ReferenceRestoration(source:original,ranges:[(original as NSString).range(of:reference),(original as NSString).range(of:"![image](same)")])
    #expect(restoration.ranges.count == 1)
    #expect(restoration.source == "before  after")
    #expect(restored(restoration) == original)
}

@Test func attachmentUndoPreservesUntrackedRemoteReplacement() {
    let reference = "![a](same)"
    let restoration = ReferenceRestoration(source:"old " + reference,ranges:[NSRange(location:4,length:(reference as NSString).length)])
    let remote = "Remote change #cafe\u{301} 🐈"
    #expect(sameSource(restored(restoration,in:remote),remote + "\n" + reference))
}
