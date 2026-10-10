import Foundation
import Testing
@testable import MemoizeCore

actor FakeMemos: MemosAPI {
    var notes: [String:RemoteMemo] = [:]
    var createCalls = 0
    var deleteCalls = 0
    var failCreateAfterCommit = false
    var failList = false
    var failDownload = false
    var failAttachmentDelete = false
    var attachmentDeleteCalls: [String] = []
    var attachmentUploads: [String:Data] = [:]
    var onAttachmentDelete: (@Sendable () async throws -> Void)?
    var onUpdate: (@Sendable () async throws -> Void)?
    var onGet: (@Sendable () async throws -> Void)?
    func set(_ note: RemoteMemo) { notes[note.name] = note }
    func configure(createTimeout: Bool = false, listFailure: Bool = false, downloadFailure: Bool = false, onUpdate: (@Sendable () async throws -> Void)? = nil, onGet: (@Sendable () async throws -> Void)? = nil) {
        failCreateAfterCommit = createTimeout; failList = listFailure; failDownload = downloadFailure; self.onUpdate = onUpdate
        self.onGet = onGet
    }
    func list() throws -> [RemoteMemo] {
        if failList { throw URLError(.networkConnectionLost) }; return Array(notes.values)
    }
    func get(_ id: String) async throws -> RemoteMemo? {
        if let onGet { self.onGet = nil; try await onGet() }
        return notes["memos/" + id]
    }
    func create(_ note: RemoteMemo) throws -> RemoteMemo {
        createCalls += 1; notes[note.name] = note
        if failCreateAfterCommit { failCreateAfterCommit = false; throw URLError(.timedOut) }; return note
    }
    func update(_ note: RemoteMemo) async throws -> RemoteMemo {
        if let onUpdate { try await onUpdate() }
        notes[note.name] = note; return note
    }
    func delete(_ id: String) { deleteCalls += 1; notes.removeValue(forKey:"memos/" + id) }
    func configureAttachmentDeletion(failure: Bool = false, onDelete: (@Sendable () async throws -> Void)? = nil) {
        failAttachmentDelete = failure; onAttachmentDelete = onDelete
    }
    func deleteAttachment(_ id: String) async throws {
        if failAttachmentDelete { throw URLError(.networkConnectionLost) }
        attachmentDeleteCalls.append(id)
        if let onAttachmentDelete { self.onAttachmentDelete = nil; try await onAttachmentDelete() }
        attachmentUploads.removeValue(forKey:id)
    }
    func upload(_ attachment: MemoizeCore.Attachment, file: URL) throws { attachmentUploads[attachment.id] = try Data(contentsOf:file) }
    func download(_ attachment: RemoteAttachment, destination: URL) throws {
        if failDownload { throw URLError(.networkConnectionLost) }
        try Data([1,2,3]).write(to:destination)
    }
}

@Test func deletionUndoStopsSyncAndPreservesTheOriginalDate() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let original = RemoteMemo(name:"memos/undo-deletion",content:"Keep this",createTime:Date(timeIntervalSince1970:1_700_000_000))
    await api.set(original); try await store.reconcile(original)
    let now = Date()
    try await store.markDelete("undo-deletion",now:now,undoInterval:5)
    let reopened = try LocalStore(directory:store.directory)
    #expect(try await reopened.hasPendingSync())
    let persistedDeadline = try #require(await reopened.nextDeletionDeadline())
    #expect(abs(persistedDeadline.timeIntervalSince(now.addingTimeInterval(5))) < 0.002)
    let engine = SyncEngine(store:reopened,api:api)
    try await engine.synchronizeOnce(now:now.addingTimeInterval(2))
    #expect(await api.deleteCalls == 0)
    #expect(try await reopened.undoDelete("undo-deletion",now:now.addingTimeInterval(3)))
    try await engine.synchronizeOnce(now:now.addingTimeInterval(6))
    #expect(await api.deleteCalls == 0)
    #expect(try await reopened.note("undo-deletion")?.created == original.createTime)
    #expect(try await reopened.nextDeletionDeadline() == nil)
    #expect(try await reopened.hasPendingSync() == false)
}

@Test func deletionRechecksUndoAfterFetchingTheRemoteNote() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let original = RemoteMemo(name:"memos/delete-race",content:"Keep the original")
    await api.set(original); try await store.reconcile(original)
    let now = Date()
    try await store.markDelete("delete-race",now:now,undoInterval:5)
    await api.configure(onGet:{
        let undone = try await store.undoDelete("delete-race",now:now.addingTimeInterval(2))
        #expect(undone)
    })
    try await SyncEngine(store:store,api:api).synchronizeOnce(now:now.addingTimeInterval(6))
    #expect(await api.deleteCalls == 0)
    #expect(try await store.note("delete-race")?.deleted == false)
}

@Test func deletionDeadlineWakesSyncDespiteAnUnrelatedDebounce() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let original = RemoteMemo(name:"memos/deadline-wake",content:"Delete after the grace period")
    await api.set(original); try await store.reconcile(original)
    try await store.markDelete("deadline-wake",undoInterval:0.3)
    let engine = SyncEngine(store:store,api:api)
    await engine.start(); await engine.schedule(after:5)
    let deadline = ContinuousClock.now.advanced(by:.seconds(3))
    while await api.deleteCalls == 0, ContinuousClock.now < deadline { try await Task.sleep(for:.milliseconds(25)) }
    await engine.stop()
    #expect(await api.deleteCalls == 1)
    #expect(try await store.note("deadline-wake")?.deleted == true)
    #expect(try await store.nextDeletionDeadline() == nil)
}

@Test func interruptedImageCacheRetriesAndClearsError() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let image = RemoteAttachment(name:"attachments/picture",filename:"picture.png")
    let memo = RemoteMemo(name:"memos/images",content:"![picture](/file/attachments/picture)",attachments:[image])
    await api.set(memo); await api.configure(downloadFailure:true)
    let engine = SyncEngine(store:store,api:api)
    do { try await engine.synchronizeOnce(); Issue.record("Expected download interruption") } catch is URLError { }
    #expect(try await store.note("images")?.error?.hasPrefix("Attachment not cached:") == true)
    await api.configure()
    try await engine.synchronizeOnce()
    #expect(try Data(contentsOf:store.attachmentURL("picture")) == Data([1,2,3]))
    #expect(try await store.note("images")?.error == nil)
}

@Test func syncDoesNotCreateUntouchedBlankNotes() async throws {
    let store = try temporaryStore(); let api = FakeMemos(); let engine = SyncEngine(store:store,api:api)
    _ = try await save(store,Note()," \n\t")
    try await engine.synchronizeOnce()
    #expect(await api.createCalls == 0)
}

@Test func createTimeoutSurvivesRestartWithoutDuplication() async throws {
    let store = try temporaryStore(); let api = FakeMemos(); let engine = SyncEngine(store:store,api:api)
    let note = try await save(store,Note(),"durable create")
    await api.configure(createTimeout:true)
    do { try await engine.synchronizeOnce(); Issue.record("Expected a timeout") } catch is URLError { }
    let reopened = try LocalStore(directory:store.directory)
    try await SyncEngine(store:reopened,api:api).synchronizeOnce()
    #expect(await api.createCalls == 1)
    #expect(try await reopened.note(note.id)?.dirty == false)
}

@Test func failedPullNeverInfersDeletion() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let remote = RemoteMemo(name:"memos/keep",content:"not in a failed page")
    try await store.reconcile(remote); await api.configure(listFailure:true)
    do { try await SyncEngine(store:store,api:api).synchronizeOnce(); Issue.record("Expected list failure") } catch is URLError { }
    #expect(try await store.note("keep")?.deleted == false)
    #expect(try await store.allNotes().count == 1)
}

@Test func typingInsideUpdateRemainsPending() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let remote = RemoteMemo(name:"memos/typing",content:"base")
    try await store.reconcile(remote); await api.set(remote)
    let note = try #require(await store.note("typing"))
    let edited = try await save(store,note,"sent to server")
    await api.configure(onUpdate:{ _ = try await save(store,edited,"newer typing") })
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    let current = try #require(await store.note(note.id))
    #expect(current.content == "newer typing" && current.dirty)
    #expect(try await api.get("typing")?.content == "sent to server")
}

@Test func synchronizationPreservesConflictsAndConverges() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    var remote = RemoteMemo(name:"memos/conflict",content:"base")
    try await store.reconcile(remote)
    let note = try #require(await store.note("conflict"))
    _ = try await save(store,note,"local")
    remote.content = "remote"; await api.set(remote)
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    #expect(Set(try await store.allNotes().map(\.content)) == ["local","remote"])
    #expect(Set(try await api.list().map(\.content)) == ["local","remote"])
}

@Test func deletionNeverRunsBeforeUndoDeadline() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let remote = RemoteMemo(name:"memos/delete",content:"base")
    try await store.reconcile(remote); await api.set(remote)
    let now = Date(); try await store.markDelete("delete",now:now)
    let engine = SyncEngine(store:store,api:api)
    try await engine.synchronizeOnce(now:now.addingTimeInterval(3))
    #expect(await api.deleteCalls == 0)
    try await engine.synchronizeOnce(now:now.addingTimeInterval(6))
    #expect(await api.deleteCalls == 1)
    #expect(try await store.allNotes().isEmpty)
}

@Test func conflictAttachmentsKeepOriginalOwnership() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let attachment = RemoteAttachment(name:"attachments/original-image",filename:"picture.png")
    var remote = RemoteMemo(name:"memos/images",content:"![image](/file/attachments/original-image)",attachments:[attachment])
    try await store.reconcile(remote)
    let note = try #require(await store.note("images"))
    _ = try await save(store,note,remote.content + " local")
    remote.content += " remote"; await api.set(remote)
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    let local = try #require(await store.note(note.id))
    #expect(local.attachments.first?.name != attachment.name)
    #expect(local.content.contains("original-image") == false)
    #expect(try await api.get("images")?.attachments == [attachment])
}

@Test func discardedDraftCannotBeCreatedByAnAlreadyPreparingSync() async throws {
    let store = try temporaryStore(); let api = FakeMemos(); let engine = SyncEngine(store:store,api:api)
    let note = try await save(store,Note(),"typed then cleared")
    let now = Date()
    await api.configure(onGet:{ try await store.markDelete(note.id,now:now) })
    try await engine.synchronizeOnce(now:now)
    #expect(await api.createCalls == 0)
    #expect(try await store.allNotes().isEmpty)
    try await engine.synchronizeOnce(now:now.addingTimeInterval(6))
    #expect(await api.createCalls == 0)
    #expect(try await store.note(note.id)?.deleted == true)
}

@Test func restoredCaptureOwnsItsImagesAfterOriginalDeletion() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let original = try await save(store,Note(),"First image")
    let image = Attachment(id:"original-capture-image",noteID:original.id,filename:"capture.png",mime:"image/png")
    let bytes = Data("original image bytes".utf8)
    try bytes.write(to:store.attachmentURL(image.id))
    try await store.addAttachment(image,to:original)
    let engine = SyncEngine(store:store,api:api)
    try await engine.synchronizeOnce()
    let now = Date(); try await store.markDelete(original.id,now:now)
    var restored = Note(); restored.attachments = [image.remote]
    restored = try await save(store,restored,"![capture](/file/attachments/" + image.id + ")")
    #expect(try await store.needsAttachmentCopy(restored))
    try await engine.synchronizeOnce(now:now.addingTimeInterval(6))
    let saved = try #require(await store.note(restored.id))
    #expect(try await api.get(original.remoteID) == nil)
    #expect(saved.attachments.count == 1)
    #expect(saved.attachments.first?.name != image.remote.name)
    #expect(!saved.content.contains(image.id))
    #expect(try await store.needsAttachmentCopy(saved) == false)
    let copied = try #require(saved.attachments.first)
    #expect(try Data(contentsOf:store.attachmentURL(String(copied.name.dropFirst(12)))) == bytes)
    #expect(try await api.get(saved.remoteID)?.attachments == saved.attachments)
}

@Test func clearedNewNoteDeletesItsSyncedCopyAndNextNoteGetsItsOwnDate() async throws {
    let store = try temporaryStore(); let api = FakeMemos(); let engine = SyncEngine(store:store,api:api)
    let note = try await save(store,Note(),"first capture")
    try await engine.synchronizeOnce()
    let firstRemote = try #require(await api.get(note.remoteID))
    #expect(abs(firstRemote.createTime.timeIntervalSince(note.created)) < 0.001)
    let now = Date(); try await store.markDelete(note.id,now:now)
    let next = try await save(store,Note(),"next capture")
    try await engine.synchronizeOnce(now:now.addingTimeInterval(6))
    #expect(try await api.get(note.remoteID) == nil)
    let nextRemote = try #require(await api.get(next.remoteID))
    #expect(abs(nextRemote.createTime.timeIntervalSince(next.created)) < 0.001)
    #expect(next.created > note.created)
    #expect(try await store.allNotes().map(\.id) == [next.id])
}
