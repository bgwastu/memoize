import Foundation
import Testing
@testable import MemoizeCore

func temporaryStore() throws -> LocalStore {
    try LocalStore(directory:FileManager.default.temporaryDirectory.appending(path:"memoize-tests-" + UUID().uuidString))
}
func save(_ store: LocalStore, _ note: Note, _ text: String) async throws -> Note {
    try await store.saveEdit(note,content:text,tags:MarkdownAnalysis.parse(text).tagValues,cursor:0,selectionLength:0,scroll:0)
}

@Test func unicodeNormalizationEditsKeepExactSourceAndTags() async throws {
    let store = try temporaryStore()
    let composed = "#caf\u{e9}"
    let decomposed = "#cafe\u{301}"
    var remote = RemoteMemo(name:"memos/unicode-source",content:composed,tags:["caf\u{e9}"])
    try await store.reconcile(remote)
    let original = try #require(await store.note("unicode-source"))
    let local = try await save(store,original,decomposed)
    #expect(Data(local.content.utf8) == Data(decomposed.utf8))
    #expect(local.dirty && !local.remote.sameBody(as:remote))
    #expect(try await store.summaries(tag:"cafe\u{301}").count == 1)
    #expect(try await store.summaries(tag:"caf\u{e9}").isEmpty)
    try await store.acknowledge(id:local.id,remote:local.remote,revision:local.revision)
    remote.content = composed // Same timestamp and equivalent graphemes still constitute a source edit.
    try await store.reconcile(remote)
    #expect(try await store.note(local.id).map { Data($0.content.utf8) } == Data(composed.utf8))
}

@Test func persistenceOrderingSearchAndTags() async throws {
    let store = try temporaryStore()
    let old = try await save(store,Note(created:Date(timeIntervalSince1970:100)),"Old #work/日本語")
    let new = try await save(store,Note(created:Date(timeIntervalSince1970:200)),"New #personal")
    _ = try await save(store,old,"Edited café #work/日本語")
    #expect(try await store.summaries().map(\.id) == [new.id,old.id])
    #expect(try await store.summaries(query:"日本").map(\.id) == [old.id])
    #expect(try await store.summaries(query:"café").map(\.id) == [old.id])
    #expect(try await store.summaries(tag:"work").map(\.id) == [old.id])
    #expect(try await LocalStore(directory:store.directory).note(old.id)?.content == "Edited café #work/日本語")
}

@Test func searchResultsExposeDistantMatchesWithoutChangingTitles() async throws {
    let store = try temporaryStore()
    let content = "# A long note\n" + String(repeating:"ordinary text ",count:100) + "📝 Café 日本語 NEEDLE " + String(repeating:"more context ",count:40)
    let note = try await save(store,Note(),content)
    let results = try await store.summaries(query:"needle")
    let result = try #require(results.first)
    #expect(result.id == note.id)
    #expect(result.preview.hasPrefix("# A long note"))
    #expect(result.excerpt?.contains("NEEDLE") == true)
    #expect((result.excerpt?.count ?? 0) < 200)
    #expect(try await store.summaries().first?.excerpt == nil)
    #expect(try await store.summaries(query:"日本").first?.excerpt?.contains("日本語") == true)
}

@Test func searchHighlightsExactUTF16Substrings() {
    let text = "📝 Native source, native color. [native](url)"
    let ranges = SearchText.ranges(in:text,query:"NATIVE")
    #expect(ranges.count == 3)
    #expect(ranges.first == NSRange(location:3,length:6))
    #expect(ranges.map { (text as NSString).substring(with:$0).lowercased() } == ["native","native","native"])
    #expect(SearchText.ranges(in:text,query:"").isEmpty)
    #expect(SearchText.ranges(in:"[a] [a]",query:"[a]").map(\.length) == [3,3])
}

@Test func acknowledgmentCannotClobberTyping() async throws {
    let store = try temporaryStore()
    let sent = try await save(store,Note(),"sent")
    try await store.beginSend(sent)
    let typed = try await save(store,sent,"typed while uploading")
    try await store.acknowledge(id:sent.id,remote:sent.remote,revision:sent.revision)
    let current = try #require(await store.note(sent.id))
    #expect(current.content == typed.content)
    #expect(current.dirty)
    #expect(current.base?.content == "sent")
}

@Test func pullDuringAutosavePreservesBothVersions() async throws {
    let store = try temporaryStore()
    var remote = RemoteMemo(name:"memos/autosave",content:"base")
    try await store.reconcile(remote)
    let editorSnapshot = try #require(await store.note("autosave"))
    remote.content = "new remote"
    try await store.reconcile(remote)
    _ = try await save(store,editorSnapshot,"not yet committed local typing")
    #expect(Set(try await store.allNotes().map(\.content)) == ["new remote","not yet committed local typing"])
}

@Test func fractionalMemosTimestamp() throws {
    let json = Data(#"{"name":"memos/a","content":"","createTime":"2026-10-10T01:31:45.123Z"}"#.utf8)
    let memo = try MemosClient.decoder().decode(RemoteMemo.self,from:json)
    #expect(memo.createTime.timeIntervalSince1970 > 1_700_000_000)
}

@Test func equalTimestampConflictPreservesBoth() async throws {
    let store = try temporaryStore()
    var remote = RemoteMemo(name:"memos/remote-id",content:"base")
    try await store.reconcile(remote)
    let note = try #require(await store.note("remote-id"))
    _ = try await save(store,note,"local")
    remote.content = "remote with exactly the same timestamp"
    #expect(try await store.reconcile(remote) == note.id)
    let all = try await store.allNotes()
    #expect(Set(all.map(\.content)) == ["local",remote.content])
    let local = try #require(all.first(where:{ $0.id == note.id }))
    #expect(local.conflict && local.visibility == "PRIVATE" && local.remoteID != "remote-id")
}

@Test func ambiguousCreateConvergesWithoutDuplicate() async throws {
    let store = try temporaryStore()
    let note = try await save(store,Note(),"first request reached server")
    try await store.beginSend(note)
    _ = try await save(store,note,"second local edit")
    try await store.reconcile(note.remote)
    let current = try #require(await store.note(note.id))
    #expect(try await store.allNotes().count == 1)
    #expect(current.content == "second local edit")
    #expect(current.dirty)
    #expect(current.base?.content == note.content)
}

@Test func deleteDeadlineSurvivesRelaunchAndUndo() async throws {
    let store = try temporaryStore()
    let note = try await save(store,Note(),"keep me")
    let now = Date()
    try await store.markDelete(note.id,now:now)
    let reopened = try LocalStore(directory:store.directory)
    #expect(try await reopened.pending(now:now.addingTimeInterval(4)).isEmpty)
    #expect(try await reopened.pending(now:now.addingTimeInterval(6)).count == 1)
    #expect(try await reopened.undoDelete(note.id,now:now.addingTimeInterval(2)))
    #expect(try await reopened.allNotes().count == 1)
}

@Test func confirmedDeletionIsImmediatelyPendingAfterRelaunch() async throws {
    let store = try temporaryStore()
    let note = try await save(store,Note(),"delete after confirmation")
    let now = Date()
    try await store.markDelete(note.id,now:now,undoInterval:0)
    let reopened = try LocalStore(directory:store.directory)
    #expect(try await reopened.allNotes().isEmpty)
    #expect(try await reopened.pending(now:now).map(\.id) == [note.id])
    #expect(try await reopened.note(note.id)?.content == note.content)
    #expect(try await reopened.undoDelete(note.id,now:now.addingTimeInterval(0.001)) == false)
}

@Test func deleteVersusEditCancelsDeletion() async throws {
    let store = try temporaryStore()
    var remote = RemoteMemo(name:"memos/server",content:"base")
    try await store.reconcile(remote)
    try await store.markDelete("server")
    remote.content = "other device changed this"
    try await store.reconcile(remote)
    let note = try #require(await store.note("server"))
    #expect(note.deleteAfter == nil)
    #expect(note.content == remote.content)
}

@Test func remoteDeletionPreservesUnsyncedWork() async throws {
    let store = try temporaryStore()
    try await store.reconcile(RemoteMemo(name:"memos/server",content:"base"))
    let note = try #require(await store.note("server"))
    _ = try await save(store,note,"offline edit")
    try await store.remoteMissing(note.id)
    let current = try #require(await store.note(note.id))
    #expect(current.content == "offline edit" && current.conflict && !current.deleted)
    #expect(current.remoteID != "server")
}

@Test func markdownSourceAndTags() {
    let source = "# Heading\n**#work/日本語** #café #👨‍👩‍👧‍👦 #can't #science&tech\n`#not-a-tag` [#link](https://example.org) \\#escaped &#35;entity #️⃣\n```swift\nlet value = \"#code\"\n```\n![alt](/file/attachments/test)"
    let analysis = MarkdownAnalysis.parse(source)
    #expect(analysis.tagValues == ["café","can't","science&tech","work","work/日本語","👨‍👩‍👧‍👦"].sorted())
    #expect(analysis.images.first?.destination == "/file/attachments/test")
    for tag in analysis.tags { #expect((source as NSString).substring(with:tag.range).hasPrefix("#")) }
    #expect(analysis.highlights.contains(where:{ if case .keyword = $0.style { true } else { false } }))
}
@Test func uploadClaimRejectsAChangedOrDiscardedSnapshot() async throws {
    let store = try temporaryStore()
    let original = try await save(store,Note(),"first")
    let edited = try await save(store,original,"second")
    #expect(try await store.beginSend(original) == false)
    #expect(try await store.beginSend(edited) == true)
    try await store.markDelete(edited.id)
    #expect(try await store.beginSend(edited) == false)
    try await store.finishDelete(edited.id)
    #expect(try await store.beginSend(edited) == false)
}
