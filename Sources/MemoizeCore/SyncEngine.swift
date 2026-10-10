import Foundation
import CryptoKit

/// One worker, durable note state, and three-way comparisons. No clock-based merge rules.
public actor SyncEngine {
    private let store: LocalStore
    private let api: any MemosAPI
    private let notify: @Sendable (SyncEvent) async -> Void
    private var worker: Task<Void, Never>?
    private var events: Task<Void, Never>?
    private var scheduled: Task<Void, Never>?
    private var deletionWake: Task<Void, Never>?
    private var again = false
    private var stopped = false
    private var failures = 0
    private var nextAttempt: Date?
    private var pausedForAuthentication = false
    public init(store: LocalStore, api: any MemosAPI, notify: @escaping @Sendable (SyncEvent) async -> Void = { _ in }) {
        self.store = store; self.api = api; self.notify = notify
    }
    public func start() async {
        stopped = false
        if let value = try? await store.metadata("retryAt"), let timestamp = Double(value) { nextAttempt = Date(timeIntervalSince1970:timestamp) }
        if let value = try? await store.metadata("retryAttempts"), let attempts = Int(value) { failures = attempts }
        trigger()
        guard events == nil, let client = api as? MemosClient else { return }
        events = Task { [weak self] in
            var delay = 2.0
            while !Task.isCancelled {
                do {
                    for try await _ in client.events() {
                        guard !Task.isCancelled else { return }
                        await self?.schedule(after:0.3); delay = 2
                    }
                } catch let error as APIError where error.status == 401 || error.status == 403 { return }
                catch { }
                if Task.isCancelled { return }
                // A proxy may block SSE while ordinary API requests still work.
                await self?.trigger()
                try? await Task.sleep(for:.seconds(delay))
                delay = min(delay * 2, 300)
            }
        }
    }
    public func stop() async {
        stopped = true; events?.cancel(); events = nil; scheduled?.cancel(); scheduled = nil
        deletionWake?.cancel(); deletionWake = nil
        worker?.cancel() // A cancelled write remains reconcilable through its saved sent snapshot.
        await worker?.value
    }
    public func schedule(after seconds: Double = 1) {
        guard !stopped else { return }
        scheduled?.cancel()
        scheduled = Task { [weak self] in
            do { try await Task.sleep(for:.seconds(seconds)); await self?.trigger() } catch { }
        }
    }
    public func trigger(force: Bool = false) {
        guard !stopped else { return }
        if force { nextAttempt = nil; pausedForAuthentication = false }
        guard !pausedForAuthentication else { return }
        if let nextAttempt, nextAttempt > Date() { schedule(after:nextAttempt.timeIntervalSinceNow); return }
        if worker != nil { again = true; return }
        worker = Task { [weak self] in await self?.run() }
    }
    private func run() async {
        deletionWake?.cancel(); deletionWake = nil
        repeat {
            again = false
            do {
                await notify(.working)
                try await synchronizeOnce()
                failures = 0; nextAttempt = nil; try await store.setMetadata("retryAt",value:"")
                try await store.setMetadata("retryAttempts",value:"0")
                await notify(.idle)
            } catch is CancellationError { break }
            catch {
                failures += 1
                let apiError = error as? APIError
                let retry = apiError == nil || apiError!.retryable
                let delay = max(apiError?.retryAfter ?? 0, min(300, pow(2, Double(min(failures,8)))) * Double.random(in:0.85...1.15))
                nextAttempt = retry ? Date().addingTimeInterval(delay) : nil
                pausedForAuthentication = apiError?.status == 401 || apiError?.status == 403
                try? await store.setMetadata("retryAt",value:nextAttempt.map { String($0.timeIntervalSince1970) } ?? "")
                try? await store.setMetadata("retryAttempts",value:String(failures))
                await notify(retry ? .offline(error.localizedDescription) : .failed(error.localizedDescription))
                if retry { schedule(after:delay) }
                again = false
            }
        } while again && !Task.isCancelled && !stopped
        worker = nil
        // A deletion's Undo deadline survives typing debounces and is restored
        // from durable state after launch or wake.
        if let deadline = try? await store.nextDeletionDeadline(),
           !stopped, !pausedForAuthentication, nextAttempt == nil {
            deletionWake = Task { [weak self] in
                do { try await Task.sleep(for:.seconds(max(0,deadline.timeIntervalSinceNow))); await self?.trigger() } catch { }
            }
        }
    }
    /// Public for deterministic fault-injection tests. The application invokes it only through the serialized worker.
    public func synchronizeOnce(now: Date = Date()) async throws {
        let remote = try await api.list() // Never infer deletion from an incomplete page sequence.
        let names = Set(remote.map(\.name))
        for memo in remote {
            try Task.checkCancellation()
            if let id = try await store.reconcile(memo) { await notify(.conflict(id)) }
        }
        for note in try await store.allNotes(includeDeleted:true) where note.base != nil && !note.deleted && !names.contains(note.remote.name) {
            if let memo = try await api.get(note.remoteID) {
                if let id = try await store.reconcile(memo) { await notify(.conflict(id)) }
            } else { try await store.remoteMissing(note.id) }
        }
        await notify(.changed)
        var transientError: (any Error)?
        for initial in try await store.pending(now:now) {
            if initial.error?.hasPrefix("Sync paused:") == true { continue }
            do {
                try Task.checkCancellation()
                var note = initial
                if let deadline = note.deleteAfter {
                    guard deadline <= now else { continue }
                    let latest = try await api.get(note.remoteID)
                    guard let pending = try await store.note(note.id), !pending.deleted, pending.deleteAfter == deadline else { continue }
                    if let latest {
                        if let base = note.base, !base.sameBody(as: latest) {
                            if let id = try await store.reconcile(latest) { await notify(.conflict(id)) }; continue
                        }
                        try await api.delete(note.remoteID)
                    }
                    try await store.finishDelete(note.id); continue
                }
                if note.base == nil && note.content.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && note.attachments.isEmpty { continue }
                if try await store.needsAttachmentCopy(note) {
                    try await cloneAttachments(note)
                    guard let fresh = try await store.note(note.id) else { continue }; note = fresh
                }
                // Reconcile ambiguous creates and late changes before uploading or writing anything.
                if let latest = try await api.get(note.remoteID) {
                    if let id = try await store.reconcile(latest) { await notify(.conflict(id)); again = true; continue }
                    guard let fresh = try await store.note(note.id), fresh.dirty else { continue }; note = fresh
                } else if note.base != nil {
                    try await store.remoteMissing(note.id); again = true; continue
                }
                // Cache the original before PATCH deletes it, so an uncached
                // attachment can still be restored with native Undo afterward.
                for attachment in note.base?.attachments ?? [] where !note.attachments.contains(where:{ $0.name == attachment.name }) {
                    let id = String(attachment.name.dropFirst(12))
                    if !FileManager.default.fileExists(atPath:store.cachedAttachmentURL(id).path) {
                        do { try await api.download(attachment,destination:store.attachmentURL(id)) }
                        catch let error as APIError where error.status == 404 { /* Already deleted remotely. */ }
                    }
                }
                for attachment in try await store.pendingAttachments(noteID:note.id) {
                    try await api.upload(attachment,file:store.cachedAttachmentURL(attachment.id)); try await store.uploaded(attachment.id)
                }
                guard try await store.beginSend(note) else { again = true; continue }
                let result = try await (note.base == nil ? api.create(note.remote) : api.update(note.remote))
                try await store.acknowledge(id:note.id,remote:result,revision:note.revision)
            } catch is CancellationError { throw CancellationError() }
            catch {
                if let e = error as? APIError, !e.retryable {
                    try await store.setError(initial.id,message:"Sync paused: " + e.localizedDescription)
                    await notify(.failed(e.localizedDescription))
                } else { try await store.setError(initial.id,message:error.localizedDescription); transientError = error }
            }
        }
        // Persisted removals also cover uploads that were removed before binding.
        // A normal Memos PATCH may already have deleted the file; DELETE is idempotent.
        for attachment in try await store.pendingAttachmentDeletions() {
            guard try await store.canDeleteAttachment(attachment.id),
                  try await store.note(attachment.noteID)?.error?.hasPrefix("Sync paused:") != true else { continue }
            do {
                try Task.checkCancellation()
                if !FileManager.default.fileExists(atPath:store.cachedAttachmentURL(attachment.id).path) {
                    do { try await api.download(attachment.remote,destination:store.attachmentURL(attachment.id)) }
                    catch let error as APIError where error.status == 404 { /* Already deleted remotely. */ }
                }
                try await api.deleteAttachment(attachment.id)
                try await store.finishAttachmentDeletion(attachment.id)
                if try await store.note(attachment.noteID)?.error?.hasPrefix("Attachment removal:") == true {
                    try await store.setError(attachment.noteID,message:nil)
                }
            } catch is CancellationError { throw CancellationError() }
            catch {
                if let error = error as? APIError, !error.retryable {
                    try await store.setError(attachment.noteID,message:"Sync paused: " + error.localizedDescription)
                    await notify(.failed(error.localizedDescription))
                } else {
                    try await store.setError(attachment.noteID,message:"Attachment removal: " + error.localizedDescription)
                    transientError = error
                }
            }
        }
        // Sequential disk downloads bound peak memory and avoid competing with typing.
        for note in try await store.allNotes() {
            var attachmentFailure = false
            for attachment in note.attachments {
                let id = String(attachment.name.dropFirst("attachments/".count))
                guard !FileManager.default.fileExists(atPath:store.cachedAttachmentURL(id).path) else { continue }
                do { try await api.download(attachment,destination:store.attachmentURL(id)) }
                catch {
                    attachmentFailure = true; try await store.setError(note.id,message:"Attachment not cached: " + error.localizedDescription)
                    if (error as? APIError)?.retryable != false { transientError = error }
                }
            }
            if let client = api as? MemosClient, note.content.contains("![") {
                let fingerprint = SHA256.hash(data:Data(note.content.utf8)).map { String(format:"%02x",$0) }.joined()
                let key = "imageCache-" + note.id
                if try await store.metadata(key) != fingerprint {
                    var complete = true
                    for reference in MarkdownAnalysis.parse(note.content).images {
                        guard let url = URL(string:reference.destination,relativeTo:client.server)?.absoluteURL, ["https","http"].contains(url.scheme ?? "") else { continue }
                        let destination: URL
                        if reference.destination.hasPrefix("/file/attachments/"), let id = reference.destination.dropFirst(18).split(separator:"/").first { destination = store.attachmentURL(String(id)) }
                        else { destination = store.externalImageURL(url) }
                        guard !FileManager.default.fileExists(atPath:destination.path) else { continue }
                        do { try await client.downloadFile(url,destination:destination) }
                        catch {
                            complete = false; attachmentFailure = true; try await store.setError(note.id,message:"Attachment not cached: " + error.localizedDescription)
                            if (error as? APIError)?.retryable != false { transientError = error }
                        }
                    }
                    if complete { try await store.setMetadata(key,value:fingerprint) }
                }
            }
            if !attachmentFailure, (note.error?.hasPrefix("Attachment not cached:") == true || note.error?.hasPrefix("Image not cached:") == true) { try await store.setError(note.id,message:nil) }
        }
        await notify(.changed)
        if let transientError { throw transientError }
    }
    private func cloneAttachments(_ note: Note) async throws {
        var attachments: [RemoteAttachment] = []; var local: [Attachment] = []
        for old in note.attachments {
            let oldID = String(old.name.dropFirst("attachments/".count)); let file = store.cachedAttachmentURL(oldID)
            if !FileManager.default.fileExists(atPath:file.path) { try await api.download(old,destination:file) }
            let item = Attachment(id:UUID().uuidString.lowercased(),noteID:note.id,filename:old.filename,mime:old.type)
            try FileManager.default.copyItem(at:file,to:store.attachmentURL(item.id))
            attachments.append(item.remote); local.append(item)
        }
        try await store.cloneAttachments(noteID:note.id,content:note.content,attachments:attachments,local:local)
    }
}
