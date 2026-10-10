import Foundation
import Testing
@testable import MemoizeCore

/// Opt-in writes are restricted to a disposable server on loopback. Never uses the user's token.
@Test(.enabled(if:ProcessInfo.processInfo.environment["MEMOIZE_TEST_SERVER"] != nil))
func memos031Contract() async throws {
    let server = try MemosClient.normalizedServer(ProcessInfo.processInfo.environment["MEMOIZE_TEST_SERVER"]!)
    #expect(server.host == "127.0.0.1")
    guard server.host == "127.0.0.1" else { throw MemoizeError.message("Contract tests require loopback.") }
    let session = URLSession(configuration:.ephemeral)
    defer { session.invalidateAndCancel() }
    func json(_ path: String, _ object: [String:Any]) async throws -> [String:Any] {
        var request = URLRequest(url:server.appending(path:"api/v1/" + path)); request.httpMethod = "POST"
        request.setValue("application/json",forHTTPHeaderField:"Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject:object)
        let (data,_) = try await session.data(for:request)
        return try JSONSerialization.jsonObject(with:data) as? [String:Any] ?? [:]
    }
    _ = try await json("users",["username":"memoize-test","password":"disposable-local-test-only","role":"ADMIN"])
    let login = try await json("auth/signin",["passwordCredentials":["username":"memoize-test","password":"disposable-local-test-only"]])
    let token = try #require(login["accessToken"] as? String)
    let client = MemosClient(server:server,token:token,user:"users/memoize-test")
    #expect(try await client.authenticate() == "users/memoize-test")
    let note = RemoteMemo(name:"memos/" + UUID().uuidString.lowercased(),content:"# Contract\n#work/日本語 #👨‍👩‍👧‍👦",createTime:Date().addingTimeInterval(-90))
    let created = try await client.create(note)
    #expect(created.name == note.name && created.visibility == "PRIVATE")
    #expect(abs(created.createTime.timeIntervalSince(note.createTime)) < 1)
    #expect(Set(created.tags) == Set(MarkdownAnalysis.parse(note.content).tagValues))
    let listed = try await client.list()
    #expect(listed.contains(where:{ $0.name == note.name }))
    var edited = created; edited.content += "\nEdited"
    let updated = try await client.update(edited)
    #expect(updated.content == edited.content)
    #expect(updated.createTime == created.createTime)
    #expect(updated.visibility == created.visibility)

    let store = try temporaryStore()
    let attachment = Attachment(id:UUID().uuidString.lowercased(),noteID:String(note.name.dropFirst(6)),filename:"test.png",mime:"image/png")
    let png = Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
    try png.write(to:store.attachmentURL(attachment.id))
    try await client.upload(attachment,file:store.attachmentURL(attachment.id))
    try await client.upload(attachment,file:store.attachmentURL(attachment.id)) // Existing caller ID: no duplicate.
    edited.content += "\n![image](/file/attachments/\(attachment.id))"; edited.attachments = [attachment.remote]
    let withImage = try await client.update(edited)
    #expect(withImage.attachments.first?.name == attachment.remote.name)
    let destination = store.attachmentURL("downloaded")
    try await client.download(attachment.remote,destination:destination)
    #expect(try Data(contentsOf:destination) == png)

    // All types use the same resumable upload contract, including empty files
    // and files larger than one chunk. Check real server metadata and bytes.
    let files: [(String,String,Data)] = [
        ("Notes [日本語].txt","text/plain",Data("Offline file attachment 🐈".utf8)),
        ("Empty.txt","text/plain",Data()),
        ("Archive.bin","application/octet-stream",Data(repeating:0xAB,count:2_500_001))
    ]
    for (name,mime,bytes) in files {
        let item = Attachment(id:UUID().uuidString.lowercased(),noteID:String(note.name.dropFirst(6)),filename:name,mime:mime)
        let file = store.attachmentURL(item.id)
        try bytes.write(to:file)
        try await client.upload(item,file:file)
        try await client.upload(item,file:file)
        edited.content += "\n" + AttachmentFiles.reference(id:item.id,filename:name,mime:mime)
        edited.attachments.append(item.remote)
        let updated = try await client.update(edited)
        #expect(updated.attachments.contains(where:{ $0.name == item.remote.name && $0.filename == name && $0.type == mime }))
        let downloaded = store.attachmentURL("downloaded-" + item.id)
        try await client.download(item.remote,destination:downloaded)
        #expect(try Data(contentsOf:downloaded) == bytes)
    }
    // Real Memos removes the underlying file during PATCH. Verify the durable
    // removal queue and a subsequent Undo, including reuse of the caller ID.
    try await store.reconcile(edited)
    try await store.setAttachmentAssociation(attachment,associated:false)
    let detached = try #require(await store.note(attachment.noteID))
    _ = try await save(store,detached,edited.content.replacingOccurrences(of:"![image](/file/attachments/\(attachment.id))",with:""))
    let engine = SyncEngine(store:store,api:client)
    try await engine.synchronizeOnce()
    #expect(try await store.pendingAttachmentDeletions().isEmpty)
    #expect(!FileManager.default.fileExists(atPath:store.attachmentURL(attachment.id).path))
    #expect(try Data(contentsOf:store.cachedAttachmentURL(attachment.id)) == png)
    do {
        try await client.download(attachment.remote,destination:store.attachmentURL("deleted-check"))
        Issue.record("Removed attachment is still on the server")
    } catch let error as APIError { #expect(error.status == 404) }
    try await client.deleteAttachment(attachment.id) // Already deleted: still succeeds.
    try await store.setAttachmentAssociation(attachment,associated:true)
    _ = try await save(store,try #require(await store.note(attachment.noteID)),edited.content)
    try await engine.synchronizeOnce()
    let restored = try #require(await client.get(attachment.noteID))
    #expect(restored.attachments.contains(where:{ $0.name == attachment.remote.name }))
    #expect(restored.createTime == created.createTime)
    let restoredFile = store.attachmentURL("restored-check")
    try await client.download(attachment.remote,destination:restoredFile)
    #expect(try Data(contentsOf:restoredFile) == png)
    // Explicit deletion also cleans an upload that was never associated with a memo.
    let orphan = Attachment(id:UUID().uuidString.lowercased(),noteID:attachment.noteID,filename:"orphan.txt",mime:"text/plain")
    let orphanFile = store.attachmentURL(orphan.id)
    try Data("orphan".utf8).write(to:orphanFile)
    try await client.upload(orphan,file:orphanFile)
    try await client.deleteAttachment(orphan.id)
    try await client.deleteAttachment(orphan.id)
    do {
        try await client.download(orphan.remote,destination:store.attachmentURL("orphan-check"))
        Issue.record("Unbound upload is still on the server")
    } catch let error as APIError { #expect(error.status == 404) }
    try await client.delete(String(note.name.dropFirst(6)))
    #expect(try await client.get(String(note.name.dropFirst(6))) == nil)
    try await client.delete(String(note.name.dropFirst(6)))
}
