import Foundation
import Testing
import GRDB
@testable import MemoizeCore

@Test func managedAttachmentLinksStayWithinTheirInstance() {
    let server = URL(string:"https://memos.example.com")!
    #expect(AttachmentFiles.referencedName("/file/attachments/id",server:nil) == "attachments/id")
    #expect(AttachmentFiles.referencedName("https://memos.example.com/file/attachments/id/Report.pdf",server:server) == "attachments/id")
    #expect(AttachmentFiles.referencedName("https://another.example.com/file/attachments/id",server:server) == nil)
    #expect(AttachmentFiles.referencedName("http://memos.example.com/file/attachments/id",server:server) == nil)
    #expect(AttachmentFiles.referencedName("/file/attachments/",server:server) == nil)
}

@Test func importedFilesSurviveRemovalOfOriginals() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    defer { try? FileManager.default.removeItem(at:root) }
    let source = root.appendingPathComponent("Report [日本語].txt")
    let cached = root.appendingPathComponent("cached-id")
    let bytes = Data("A file, not an image 🐈".utf8)
    try bytes.write(to:source)
    try AttachmentFiles.copy(source,to:cached)
    try FileManager.default.removeItem(at:source)
    #expect(try Data(contentsOf:cached) == bytes)
    let preview = try AttachmentFiles.previewURL(cached,filename:"../Report [日本語].txt",directory:root.appendingPathComponent("Preview"))
    #expect(preview.lastPathComponent == "Report [日本語].txt")
    #expect(try Data(contentsOf:preview) == bytes)
    let reference = AttachmentFiles.reference(id:"id",filename:"Report [日本語].txt",mime:"text/plain")
    let parsed = MarkdownAnalysis.parse(reference)
    #expect(parsed.images.isEmpty)
    #expect(parsed.links.first?.destination == "/file/attachments/id")
}

@Test func importRejectsFoldersAndPreservesExistingCache() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    defer { try? FileManager.default.removeItem(at:root) }
    let cached = root.appendingPathComponent("cached")
    try Data([1,2,3]).write(to:cached)
    #expect(throws:(any Error).self) { try AttachmentFiles.copy(root,to:cached) }
    #expect(try Data(contentsOf:cached) == Data([1,2,3]))
    #expect(try FileManager.default.contentsOfDirectory(atPath:root.path) == ["cached"])
    let empty = root.appendingPathComponent("empty.txt")
    try Data().write(to:empty)
    let imported = root.appendingPathComponent("empty-cached")
    try AttachmentFiles.copy(empty,to:imported)
    #expect(try Data(contentsOf:imported).isEmpty)
}

@Test func attachmentDownloadReplacesOnlyAfterSuccessfulCopy() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    defer { try? FileManager.default.removeItem(at:root) }
    let source = root.appendingPathComponent("cached")
    let destination = root.appendingPathComponent("Report [日本語].txt")
    let bytes = Data("Downloaded attachment 🐈".utf8)
    try bytes.write(to:source)
    try AttachmentFiles.export(source,to:destination)
    #expect(try Data(contentsOf:destination) == bytes)
    try Data("Old file".utf8).write(to:destination)
    try AttachmentFiles.export(source,to:destination)
    #expect(try Data(contentsOf:destination) == bytes)
    #expect(throws:(any Error).self) { try AttachmentFiles.export(root.appendingPathComponent("missing"),to:destination) }
    #expect(try Data(contentsOf:destination) == bytes)
    #expect(try FileManager.default.contentsOfDirectory(atPath:root.path).sorted() == ["Report [日本語].txt","cached"])
}

@Test func attachmentRemovalSurvivesOfflineRestartAndUndo() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let note = try await save(store,Note(),"Keep this note")
    let item = Attachment(id:UUID().uuidString.lowercased(),noteID:note.id,filename:"Report.txt",mime:"text/plain")
    let bytes = Data("Offline attachment".utf8)
    try bytes.write(to:store.attachmentURL(item.id))
    try await store.addAttachment(item,to:note)
    let attached = try #require(await store.note(note.id))
    let source = attached.content + "\n" + AttachmentFiles.reference(id:item.id,filename:item.filename,mime:item.mime)
    _ = try await save(store,attached,source)
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    try await store.setAttachmentAssociation(item,associated:false)
    _ = try await save(store,try #require(await store.note(note.id)),note.content)
    await api.configure(listFailure:true)
    do { try await SyncEngine(store:store,api:api).synchronizeOnce(); Issue.record("Expected offline failure") } catch is URLError { }
    #expect(await api.attachmentDeleteCalls.isEmpty)
    let reopened = try LocalStore(directory:store.directory)
    #expect(try await reopened.pendingAttachmentDeletions().map(\.id) == [item.id])
    await api.configure()
    try await SyncEngine(store:reopened,api:api).synchronizeOnce()
    #expect(await api.attachmentDeleteCalls == [item.id])
    #expect(try await reopened.pendingAttachmentDeletions().isEmpty)
    #expect(!FileManager.default.fileExists(atPath:reopened.attachmentURL(item.id).path))
    #expect(try Data(contentsOf:reopened.cachedAttachmentURL(item.id)) == bytes)
    try await reopened.setAttachmentAssociation(item,associated:true)
    _ = try await save(reopened,try #require(await reopened.note(note.id)),source)
    #expect(try await reopened.pendingAttachments(noteID:note.id).map(\.id) == [item.id])
    try await SyncEngine(store:reopened,api:api).synchronizeOnce()
    #expect(try await api.get(note.remoteID)?.attachments == [item.remote])
    #expect(await api.attachmentUploads[item.id] == bytes)
    #expect(try await reopened.note(note.id)?.dirty == false)
}

@Test func orphanUploadDeletionRetriesWithoutLosingUndoBytes() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let note = try await save(store,Note(),"Keep")
    let item = Attachment(id:"orphan",noteID:note.id,filename:"Empty.txt",mime:"text/plain")
    try Data().write(to:store.attachmentURL(item.id))
    try await store.addAttachment(item,to:note)
    // The upload committed before its association was removed, but was never bound.
    try await api.upload(item,file:store.attachmentURL(item.id)); try await store.uploaded(item.id)
    try await store.setAttachmentAssociation(item,associated:false)
    await api.configureAttachmentDeletion(failure:true)
    do { try await SyncEngine(store:store,api:api).synchronizeOnce(); Issue.record("Expected removal failure") } catch is URLError { }
    #expect(try await store.pendingAttachmentDeletions().count == 1)
    #expect(FileManager.default.fileExists(atPath:store.attachmentURL(item.id).path))
    await api.configureAttachmentDeletion()
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    #expect(await api.attachmentDeleteCalls == [item.id])
    #expect(try await store.pendingAttachmentDeletions().isEmpty)
    #expect(try await store.note(note.id)?.error == nil)
    #expect(try Data(contentsOf:store.cachedAttachmentURL(item.id)).isEmpty)
}

@Test func undoDuringAttachmentDeletionRemainsUploadable() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let note = try await save(store,Note(),"Keep")
    let item = Attachment(id:"race",noteID:note.id,filename:"File.txt",mime:"text/plain")
    let bytes = Data("Recover me".utf8)
    try bytes.write(to:store.attachmentURL(item.id))
    try await store.addAttachment(item,to:note)
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    try await store.setAttachmentAssociation(item,associated:false)
    await api.configureAttachmentDeletion(onDelete:{ try await store.setAttachmentAssociation(item,associated:true) })
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    #expect(try await store.pendingAttachmentDeletions().isEmpty)
    #expect(try await store.pendingAttachments(noteID:note.id).map(\.id) == [item.id])
    #expect(try Data(contentsOf:store.cachedAttachmentURL(item.id)) == bytes)
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    #expect(await api.attachmentUploads[item.id] == bytes)
    #expect(try await api.get(note.remoteID)?.attachments == [item.remote])
}

@Test func referencesAndUndoProtectPendingFileRemoval() async throws {
    let store = try temporaryStore()
    let note = try await save(store,Note(),"Keep")
    let item = Attachment(id:"shared",noteID:note.id,filename:"File.txt",mime:"text/plain")
    try await store.addAttachment(item,to:note)
    try await store.setAttachmentAssociation(item,associated:false)
    var other = Note(); other.attachments = [item.remote]
    let saved = try await save(store,other,"![File](/file/attachments/shared)")
    #expect(try await store.canDeleteAttachment(item.id) == false)
    try await store.markDelete(saved.id,undoInterval:0); try await store.finishDelete(saved.id)
    #expect(try await store.canDeleteAttachment(item.id))
    try await store.setAttachmentAssociation(item,associated:true)
    #expect(try await store.canDeleteAttachment(item.id) == false)
    #expect(try await store.pendingAttachmentDeletions().isEmpty)
}

@Test func uncachedAttachmentIsBackedUpBeforeDeleting() async throws {
    let store = try temporaryStore(); let api = FakeMemos()
    let item = Attachment(id:"uncached",noteID:"remote",filename:"Image.png",mime:"image/png")
    let remote = RemoteMemo(name:"memos/remote",content:"![Image](/file/attachments/uncached)",attachments:[item.remote])
    await api.set(remote); try await store.reconcile(remote)
    try await store.setAttachmentAssociation(item,associated:false)
    _ = try await save(store,try #require(await store.note(item.noteID)),"Keep the note")
    #expect(!FileManager.default.fileExists(atPath:store.cachedAttachmentURL(item.id).path))
    await api.configure(downloadFailure:true)
    do { try await SyncEngine(store:store,api:api).synchronizeOnce(); Issue.record("Expected download failure") } catch is URLError { }
    #expect(try await api.get(item.noteID)?.attachments == [item.remote])
    #expect(await api.attachmentDeleteCalls.isEmpty)
    await api.configure()
    try await SyncEngine(store:store,api:api).synchronizeOnce()
    #expect(try Data(contentsOf:store.cachedAttachmentURL(item.id)) == Data([1,2,3]))
    #expect(try await api.get(item.noteID)?.attachments.isEmpty == true)
    #expect(try await store.pendingAttachmentDeletions().isEmpty)
}

@Test func attachmentDeletionMigrationPreservesExistingNotesAndFiles() async throws {
    let store = try temporaryStore()
    let note = try await save(store,Note(),"An existing note")
    let item = Attachment(id:"existing",noteID:note.id,filename:"Existing.txt",mime:"text/plain")
    let bytes = Data("Existing attachment".utf8)
    try bytes.write(to:store.attachmentURL(item.id))
    try await store.addAttachment(item,to:note)
    let database = try DatabaseQueue(path:store.directory.appendingPathComponent("memoize.sqlite").path)
    // Recreate the immediately preceding schema, with its existing user data.
    try await database.write { db in
        try db.execute(sql:"DROP TABLE attachmentDeletions")
        try db.execute(sql:"DELETE FROM grdb_migrations WHERE identifier='attachment-deletions'")
    }
    let reopened = try LocalStore(directory:store.directory)
    #expect(try await reopened.note(note.id)?.content == note.content)
    #expect(try await reopened.note(note.id)?.attachments == [item.remote])
    #expect(try Data(contentsOf:reopened.cachedAttachmentURL(item.id)) == bytes)
    #expect(try FileManager.default.contentsOfDirectory(atPath:store.directory.path).contains(where:{ $0.hasPrefix("before-migration-") && $0.hasSuffix(".sqlite") }))
    try await reopened.setAttachmentAssociation(item,associated:false)
    #expect(try await reopened.pendingAttachmentDeletions().map(\.id) == [item.id])
}
