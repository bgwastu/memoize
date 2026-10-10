import AppKit
import KeyboardShortcuts
import CryptoKit
import Network
import UniformTypeIdentifiers
import MemoizeCore
import Sparkle

@MainActor final class AppController: NSObject, NSApplicationDelegate {
    private(set) var editor: NoteEditor!
    private(set) var library: LibraryWindow!
    private let settings = SettingsWindow()
    private var statusItem: NSStatusItem!
    private var store: LocalStore?
    private var sync: SyncEngine?
    private var client: MemosClient?
    private var current: Note?
    private var temporary = false
    private var newNoteID: String?
    private var draftAttachments: [String:RemoteAttachment] = [:]
    private var draftReturnID: String?
    private var navigationID: String?
    private var all: [NoteSummary] = []
    private var cache: [String:Note] = [:]
    private var hasUnsavedText = false
    private var editGeneration = 0
    private var openGeneration = 0
    private var refreshGeneration = 0
    private var firstUnsaved: Date?
    private var autosave: Task<Void,Never>?
    private var saveQueue: Task<Void,Never>?
    private var searchTask: Task<Void,Never>?
    private var deletionAlert: NSAlert?
    private var deleting = Set<String>()
    private struct BriefNotice {
        let id = UUID()
        let message: String
        let undo: NoticeUndo?
        let noteID: String?
    }
    private enum NoticeUndo {
        case deletion(String,LocalStore)
        case attachment(AttachmentRemoval)
    }
    private final class AttachmentRemoval {
        var noteID: String
        var wasDraft: Bool
        let file: AttachedFile
        let store: LocalStore
        var restoration: ReferenceRestoration
        init(noteID: String, wasDraft: Bool, file: AttachedFile, store: LocalStore, restoration: ReferenceRestoration) {
            self.noteID = noteID; self.wasDraft = wasDraft; self.file = file; self.store = store; self.restoration = restoration
        }
    }
    private var attachmentUndo: AttachmentRemoval? {
        if case .attachment(let removal) = briefNotice?.undo { return removal }; return nil
    }
    private var briefNotice: BriefNotice?
    private var removingAttachment = false
    private var noticeTimer: Task<Void,Never>?
    private var noticeUndoTask: Task<Void,Never>?
    private var statusText = "Saved on this Mac"
    private var syncState: SyncIndicator.State = .local
    private var hasPendingSync = false
    private var backgroundSyncError: String?
    private var observers: [any NSObjectProtocol] = []
    private var attachmentWrites: [UUID:Task<Void,Never>] = [:]
    private var attachmentCounts: [String:Int] = [:]
    private let network = NWPathMonitor()
    private var shuttingDown = false
    private var readyToQuit = false
    private var loadingCredentials = false
    private var diskError: String?
    private var unsavedRecovery: [String:Note] = [:]
    private var updater: SPUStandardUpdaterController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        UserDefaults.standard.register(defaults:["showDock":true,"showMenuBar":true,"pinned":true])
        AppAppearance.saved.apply()
        NSApp.setActivationPolicy(UserDefaults.standard.bool(forKey:"showDock") ? .regular : .accessory)
        #if !DEBUG
        if !ProcessInfo.processInfo.arguments.contains("--smoke-test"),
           !ProcessInfo.processInfo.arguments.contains("--connect-stdin") {
            updater = SPUStandardUpdaterController(startingUpdater:true,updaterDelegate:nil,userDriverDelegate:nil)
        }
        #endif
        editor = NoteEditor(); library = LibraryWindow(); installMenus(); connectUI()
        KeyboardShortcuts.onKeyUp(for:.toggleNote) { [weak self] in self?.toggleNote() }
        network.pathUpdateHandler = { [weak self] path in
            if path.status == .satisfied { Task { @MainActor in await self?.sync?.schedule(after:0.5) } }
        }
        network.start(queue:DispatchQueue(label:"net.wastu.memoize.network",qos:.utility))
        let center = NSWorkspace.shared.notificationCenter
        observers.append(center.addObserver(forName:NSWorkspace.willSleepNotification,object:nil,queue:.main) { [weak self] _ in
            Task { @MainActor in await self?.finishAttachmentWrites(); self?.flush(); await self?.saveQueue?.value; await self?.sync?.stop() }
        })
        observers.append(center.addObserver(forName:NSWorkspace.didWakeNotification,object:nil,queue:.main) { [weak self] _ in
            Task { @MainActor in
                if let sync = self?.sync { await sync.start() } else { await self?.resumeConnection() }
            }
        })
        Task { await launch() }
    }
    private var support: URL {
        if let path = ProcessInfo.processInfo.environment["MEMOIZE_DATA_DIR"] { return URL(fileURLWithPath:path,isDirectory:true) }
        return FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appending(path:"Memoize",directoryHint:.isDirectory)
    }
    private func launch() async {
        do {
            if ProcessInfo.processInfo.arguments.contains("--smoke-test") { try await smokeTest(); return }
            if ProcessInfo.processInfo.arguments.contains("--connect-stdin") { try await connectFromInput(); return }
            let namespace = UserDefaults.standard.string(forKey:"namespace") ?? "local"
            try await openStore(namespace:namespace)
            library.show()
            if let id = try await store?.metadata("selected"), all.contains(where:{ $0.id == id }) { openNote(id,show:false) }
            else if let id = all.first?.id { openNote(id,show:false) }
            else { newNote(show:false) }
            await resumeConnection()
            if UserDefaults.standard.string(forKey:"namespace") == nil { settings.show() }
        } catch {
            showError(error)
            if ProcessInfo.processInfo.arguments.contains("--smoke-test") || ProcessInfo.processInfo.arguments.contains("--connect-stdin") {
                FileHandle.standardError.write(Data(("Smoke test failed: " + error.localizedDescription + "\n").utf8)); exit(1)
            }
        }
    }
    private func openStore(namespace: String) async throws {
        let directory = support.appending(path:namespace,directoryHint:.isDirectory)
        let newStore = try await Task.detached(priority:.userInitiated) { try LocalStore(directory:directory) }.value
        clearNotice(); syncState = .local; statusText = "Saved on this Mac"
        store = newStore; cache = [:]; current = nil; navigationID = nil; temporary = false; newNoteID = nil; draftReturnID = nil; draftAttachments = [:]
        try await refresh()
    }
    private func connectUI() {
        editor.onEdit = { [weak self] text in self?.edited(text) }
        editor.onTextReplacement = { [weak self] id,range,length in
            guard let removal = self?.attachmentUndo, removal.noteID == id else { return }
            removal.restoration.applyEdit(range,replacementLength:length)
        }
        editor.toast.onUndo = { [weak self] in self?.undoNotice() }
        editor.onPosition = { [weak self] in self?.rememberPosition() }
        editor.onHide = { [weak self] in self?.hideApp() }
        editor.onNew = { [weak self] in self?.newNote() }
        editor.onLibrary = { [weak self] in self?.toggleLibrary() }
        editor.onDelete = { [weak self] in self?.deleteNote() }
        editor.textView.onEmptyBackspace = { [weak self] in self?.deleteNote() }
        editor.onPin = { [weak self] in self?.setPinnedWindows($0) }
        setPinnedWindows(editor.panel.level == .floating)
        editor.onTag = { [weak self] in self?.library.filter($0) }
        editor.onReferences = { [weak self] in self?.updateAttachments($0) }
        editor.textView.navigate = { [weak self] in self?.navigate($0) }
        editor.textView.globalSearch = { [weak self] in self?.library.show(searching:true) }
        editor.textView.onImage = { [weak self] data,name,mime in self?.insertAttachment(data,name:name,mime:mime) }
        editor.textView.onFile = { [weak self] url in
            self?.insertAttachment(file:url,name:url.lastPathComponent,mime:UTType(filenameExtension:url.pathExtension)?.preferredMIMEType ?? "application/octet-stream")
        }
        editor.attachmentStrip.onPreview = { [weak self] file,anchor in
            guard let self, let store, let noteID = current?.id else { return }
            let key = SHA256.hash(data:Data(file.id.utf8)).map { String(format:"%02x",$0) }.joined()
            Task {
                do {
                    let url = try await Task.detached(priority:.userInitiated) {
                        try AttachmentFiles.previewURL(file.url,filename:file.name,directory:store.directory.appending(path:"Previews/" + key,directoryHint:.isDirectory))
                    }.value
                    guard current?.id == noteID else { return }
                    editor.showAttachment(at:url,anchor:anchor)
                } catch { showError(error) }
            }
        }
        editor.attachmentStrip.onRemove = { [weak self] in self?.removeAttachment($0) }
        editor.attachmentStrip.onDownload = { [weak self] in self?.downloadAttachment($0) }
        library.onSelect = { [weak self] id in
            guard let self else { return }
            openNote(id,find:library.search.stringValue.isEmpty ? nil : library.search.stringValue)
        }
        library.onNew = { [weak self] in self?.newNote() }
        library.onShow = { [weak self] in
            guard let self, !editor.panel.isVisible else { return }
            editor.show()
        }
        library.onVisibilityChange = { [weak self] in self?.editor.setLibraryVisible($0) }
        editor.setLibraryVisible(false)
        library.onSettings = { [weak self] in self?.settings.show() }
        library.onSearch = { [weak self] _,_ in self?.scheduleSearch() }
        settings.model.connect = { [weak self] server,token in try await self?.configure(server:server,token:token) }
        settings.model.onMenuBarVisibility = { [weak self] in self?.statusItem.isVisible = $0 }
    }
    private func setPinnedWindows(_ pinned: Bool) {
        library.window.level = pinned ? .floating : .normal
        settings.pinned = pinned
    }
    private func configure(server value: String, token replacement: String) async throws {
        await finishAttachmentWrites()
        flush(); await saveQueue?.value
        if let diskError { throw MemoizeError.message(diskError) }
        let server = try MemosClient.normalizedServer(value)
        let oldUser = UserDefaults.standard.string(forKey:"user") ?? ""
        let account = Credentials.namespace(server:server,user:oldUser)
        let token: String
        if replacement.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
            guard let saved = try await Task.detached(operation:{ try Credentials.read(account:account) }).value else { throw MemoizeError.message("Enter a Memos access token.") }; token = saved
        } else { token = replacement.trimmingCharacters(in:.whitespacesAndNewlines) }
        let connection = MemosClient(server:server,token:token)
        let user = try await connection.authenticate()
        let namespace = Credentials.namespace(server:server,user:user)
        try await Task.detached { try Credentials.save(token,account:namespace) }.value
        await sync?.stop()
        try await openStore(namespace:namespace)
        UserDefaults.standard.set(server.absoluteString,forKey:"server"); UserDefaults.standard.set(user,forKey:"user"); UserDefaults.standard.set(namespace,forKey:"namespace")
        await startSync(server:server,user:user,token:token)
        if let id = all.first?.id { openNote(id) } else { newNote() }
    }
    private func startSync(server: URL, user: String, token: String) async {
        guard let store else { return }
        let client = MemosClient(server:server,token:token,user:user); self.client = client
        let worker = SyncEngine(store:store,api:client) { [weak self] event in await self?.syncEvent(event) }
        sync = worker; await worker.start()
    }
    private func resumeConnection() async {
        let arguments = ProcessInfo.processInfo.arguments
        guard !arguments.contains("--smoke-test"), !arguments.contains("--connect-stdin"),
              sync == nil, !loadingCredentials, let currentStore = store,
              let raw = UserDefaults.standard.string(forKey:"server"),
              let user = UserDefaults.standard.string(forKey:"user"),
              let server = try? MemosClient.normalizedServer(raw) else { return }
        loadingCredentials = true; defer { loadingCredentials = false }
        do {
            let token = try await Task.detached { try Credentials.read(account:Credentials.namespace(server:server,user:user)) }.value
            guard store === currentStore, sync == nil else { return }
            if let token { await startSync(server:server,user:user,token:token) }
            else { showError(MemoizeError.message("Reconnect in Settings to resume sync.")) }
        } catch {
            guard store === currentStore else { return }
            showError(error)
            FileHandle.standardError.write(Data(("Memoize credential access: " + error.localizedDescription + "\n").utf8))
        }
    }
    private func syncEvent(_ event: SyncEvent) async {
        switch event {
        case .working: statusText = "Syncing…"; syncState = .working
        case .idle: statusText = "All changes synced"; syncState = .synced
        case .offline(let error): statusText = "Offline: " + error; syncState = .offline
        case .failed(let error): statusText = error; syncState = .failed
        case .conflict: statusText = "Both versions kept"; syncState = .conflict
        case .changed: break
        }
        if case .changed = event {
            do {
                await saveQueue?.value
                let generation = editGeneration
                cache = cache.filter { $0.key == current?.id || unsavedRecovery[$0.key] != nil }
                try await refresh()
                if let current, let fresh = try await store?.note(current.id), generation == editGeneration, !hasUnsavedText, !editor.textView.hasMarkedText() {
                    if fresh.deleted || fresh.deleteAfter != nil { if let id = all.first?.id { openNote(id,show:false) } else { newNote(show:false) } }
                    else {
                        self.current = fresh; cache[fresh.id] = fresh
                        if !sameSource(fresh.content,editor.textView.string) {
                            var positioned = fresh; positioned.cursor = editor.textView.selectedRange().location; positioned.selectionLength = editor.textView.selectedRange().length; positioned.scroll = editor.scrollView.contentView.bounds.origin.y
                            editor.display(positioned)
                        }
                        let content = fresh.content
                        let references = await Task.detached { MarkdownAnalysis.parse(content) }.value
                        if self.current?.id == fresh.id, sameSource(editor.textView.string,content) { updateAttachments(references) }
                    }
                }
            } catch { showError(error) }
        }
        showStatus()
    }
    private func showStatus() {
        if let diskError { editor.syncIndicator.set(.failed,detail:"Save failed: " + diskError) }
        else if let error = current?.error ?? backgroundSyncError { editor.syncIndicator.set(.failed,detail:error) }
        else if syncState == .offline || syncState == .failed || syncState == .working || syncState == .conflict { editor.syncIndicator.set(syncState,detail:statusText) }
        else if hasUnsavedText { editor.syncIndicator.set(.pending,detail:"Saving on this Mac…") }
        else if hasPendingSync || (!temporary && current?.dirty == true) { editor.syncIndicator.set(.pending,detail:"Saved on this Mac. Waiting to sync.") }
        else { editor.syncIndicator.set(syncState,detail:statusText) }
        library.status.stringValue = ["Saved on this Mac","All changes synced","Syncing…"].contains(statusText) ? "" : statusText
    }
    private func edited(_ text: String) {
        guard var note = current else { return }
        if let removal = attachmentUndo, removal.noteID == note.id { removal.restoration.source = text }
        let empty = text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty
        if empty, !temporary, newNoteID == note.id { discardEmptyNewNote(note,content:text); return }
        if temporary, !empty {
            note.created = Date(); note.modified = note.created; temporary = false
            // Native Undo can restore an attachment after a cleared capture received
            // a fresh identity. Its cached bytes must follow the restored source.
            note.attachments += draftAttachments.values.filter { image in
                text.contains("/file/" + image.name) && !note.attachments.contains(where:{ $0.name == image.name })
            }
        }
        note.content = text; current = note; cache[note.id] = note
        editGeneration += 1
        guard !temporary else { hasUnsavedText = false; showStatus(); return }
        hasUnsavedText = true
        if firstUnsaved == nil { firstUnsaved = Date() }
        autosave?.cancel()
        let delay = min(0.15,max(0,0.5 - Date().timeIntervalSince(firstUnsaved!)))
        autosave = Task { [weak self] in
            do { try await Task.sleep(for:.seconds(delay)) } catch { return }
            self?.flush()
        }
        Task { await sync?.schedule(after:1.2) }; showStatus()
    }
    private func discardEmptyNewNote(_ discarded: Note, content: String) {
        let notify = !removingAttachment
        for image in discarded.attachments { draftAttachments[image.name] = image }
        autosave?.cancel(); autosave = nil; positionTask?.cancel()
        editGeneration += 1; openGeneration += 1; hasUnsavedText = false; firstUnsaved = nil
        var blank = Note(); blank.content = content
        if let removal = attachmentUndo, removal.noteID == discarded.id { removal.noteID = blank.id; removal.wasDraft = true }
        current = blank; navigationID = blank.id; newNoteID = blank.id; temporary = true
        cache.removeValue(forKey:discarded.id); all.removeAll { $0.id == discarded.id }
        editor.continueDraft(as:blank.id)
        showStatus()
        guard let store else { return }
        let previous = saveQueue
        saveQueue = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            do {
                // Queue behind earlier autosaves so they cannot resurrect the discarded note.
                if try await store.note(discarded.id) != nil {
                    try await store.markDelete(discarded.id,undoInterval:0)
                    showDeletionNotice(notify:notify)
                } else {
                    if notify { showBriefNotice("Note deleted") }
                }
                unsavedRecovery.removeValue(forKey:discarded.id)
                if unsavedRecovery.isEmpty { diskError = nil }
                try await refresh()
            } catch {
                unsavedRecovery[discarded.id] = discarded; diskError = error.localizedDescription; showStatus()
            }
        }
    }
    /// Enqueue an immutable snapshot; switching notes never waits on disk or parsing.
    private func flush() {
        autosave?.cancel(); autosave = nil
        guard let store, var note = current else { return }
        // The visible source is authoritative, including native undo/redo at an event boundary.
        if !sameSource(note.content,editor.textView.string) { edited(editor.textView.string); flush(); return }
        guard !temporary else { return }
        guard hasUnsavedText else { return }
        let selection = editor.textView.selectedRange(); note.cursor = selection.location; note.selectionLength = selection.length; note.scroll = editor.scrollView.contentView.bounds.origin.y
        current = note; cache[note.id] = note
        let snapshot = note; let generation = editGeneration; let previous = saveQueue
        hasUnsavedText = false; firstUnsaved = nil
        saveQueue = Task { [weak self] in
            await previous?.value
            do {
                let tags = await Task.detached(priority:.userInitiated) { MarkdownAnalysis.parse(snapshot.content).tagValues }.value
                let saved = try await store.saveEdit(snapshot,content:snapshot.content,tags:tags,cursor:snapshot.cursor,selectionLength:snapshot.selectionLength,scroll:snapshot.scroll)
                guard let self else { return }
                unsavedRecovery.removeValue(forKey:snapshot.id)
                if unsavedRecovery.isEmpty { diskError = nil }
                if editGeneration == generation && current?.id == snapshot.id { current = saved; cache[snapshot.id] = saved }
                try await refresh()
                await sync?.schedule(after:1)
                showStatus()
            } catch {
                guard let self else { return }
                diskError = error.localizedDescription
                // Retain the exact buffer and offer an export instead of claiming it was saved.
                cache[snapshot.id] = snapshot
                unsavedRecovery[snapshot.id] = snapshot
                if current?.id == snapshot.id { hasUnsavedText = true }
                showStatus()
            }
        }
    }
    private func rememberPosition(immediate: Bool = false) {
        guard let store, var note = current else { return }
        let selection = editor.textView.selectedRange(); note.cursor = selection.location; note.selectionLength = selection.length; note.scroll = editor.scrollView.contentView.bounds.origin.y
        current = note; cache[note.id] = note
        guard !temporary else { return }
        // Positions are cheap metadata; debounce selection movement independently of typing.
        positionTask?.cancel()
        positionTask = Task {
            do {
                if !immediate { try await Task.sleep(for:.milliseconds(250)) }
                try await store.savePosition(id:note.id,cursor:note.cursor,length:note.selectionLength,scroll:note.scroll)
            } catch { }
        }
    }
    private var positionTask: Task<Void,Never>?
    private func openNote(_ id: String, show: Bool = true, find query: String? = nil, direction: Int = 0) {
        if current?.id == id {
            openGeneration += 1; navigationID = id
            if show { editor.show() }; if let query { editor.find(query:query) }; return
        }
        rememberPosition(immediate:true); flush(); openGeneration += 1; let generation = openGeneration
        navigationID = id
        if let note = unsavedRecovery[id] ?? cache[id] {
            temporary = false; newNoteID = nil; draftAttachments = [:]
            install(note,show:show,direction:direction); if let query { editor.find(query:query) }
        }
        else {
            Task {
                do {
                    guard let note = try await store?.note(id), generation == openGeneration else { return }
                    // The old editor stays usable during a cache miss. Capture
                    // input that arrived while the database read was suspended.
                    rememberPosition(immediate:true); flush()
                    guard generation == openGeneration else { return }
                    temporary = false; newNoteID = nil; draftAttachments = [:]
                    install(note,show:show,direction:direction)
                    if let query { editor.find(query:query) }
                } catch { showError(error) }
            }
        }
    }
    private func install(_ note: Note, show: Bool, direction: Int = 0) {
        openGeneration += 1; navigationID = note.id
        if briefNotice?.message == "New note", briefNotice?.noteID != note.id { clearNotice() }
        current = note; cache[note.id] = note; hasUnsavedText = false; firstUnsaved = nil
        editor.display(note,direction:direction); if show { editor.show() }; showStatus()
        Task { try? await store?.setMetadata("selected",value:note.id) }
        if let index = all.firstIndex(where:{ $0.id == note.id }) {
            let ids = all[max(0,index - 2)...min(all.count - 1,index + 2)].map(\.id)
            Task {
                for id in ids where cache[id] == nil { if let item = try? await store?.note(id) { cache[id] = item } }
                if cache.count > 12 { cache = cache.filter { ids.contains($0.key) || $0.key == current?.id } }
            }
        }
    }
    private func newNote(show: Bool = true, notify: Bool = true, direction: Int = 0) {
        if temporary { if show { editor.show() }; if show && notify { showNewNoteNotice() }; return }
        rememberPosition(immediate:true); flush(); openGeneration += 1
        draftReturnID = current?.id
        draftAttachments = [:]
        let note = Note(); navigationID = note.id; newNoteID = note.id; temporary = true; install(note,show:show,direction:direction)
        if show && notify { showNewNoteNotice() }
    }
    private func showNewNoteNotice() {
        showBriefNotice("New note")
    }
    private func showBriefNotice(_ text: String, undo: NoticeUndo? = nil, until deadline: Date? = nil) {
        noticeTimer?.cancel()
        let notice = BriefNotice(message:text,undo:undo,noteID:current?.id); briefNotice = notice
        editor.showToast(text,canUndo:undo != nil)
        let duration = deadline?.timeIntervalSinceNow ?? (undo == nil ? 1.6 : 5)
        noticeTimer = Task { [weak self] in
            do { try await Task.sleep(for:.seconds(max(0,duration))) } catch { return }
            guard self?.briefNotice?.id == notice.id else { return }; self?.clearNotice()
        }
    }
    private func clearNotice() {
        noticeTimer?.cancel(); noticeTimer = nil; briefNotice = nil; editor?.showToast(nil)
    }
    private func undoNotice() {
        guard let undo = briefNotice?.undo else { return }
        clearNotice()
        let previous = noticeUndoTask
        noticeUndoTask = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            do {
                switch undo {
                case .deletion(let id,let originalStore):
                    guard store === originalStore else { return }
                    guard try await originalStore.undoDelete(id) else { throw MemoizeError.message("The Undo period has ended.") }
                    guard let restored = try await originalStore.note(id), store === originalStore else { return }
                    cache[id] = restored; try await refresh()
                    guard store === originalStore else { return }; openNote(id)
                    showBriefNotice("Note restored")
                    await sync?.schedule(after:0)
                case .attachment(let removal):
                    try await restoreAttachment(removal)
                    guard store === removal.store else { return }
                    showBriefNotice("Attachment restored")
                }
            } catch { showError(error); showBriefNotice("Couldn’t restore the item") }
        }
    }
    private func navigate(_ direction: Int) {
        // Read the actual editor before choosing a destination. A final delete/undo can
        // share an AppKit event boundary with the navigation shortcut.
        flush()
        if temporary {
            let previous = draftReturnID.flatMap { id in all.contains(where:{ $0.id == id }) ? id : nil } ?? all.first?.id
            if direction > 0, let previous { openNote(previous,direction:direction) }
            return
        }
        guard let id = navigationID, let index = all.firstIndex(where:{ $0.id == id }) else {
            if direction > 0, let first = all.first?.id { openNote(first,direction:direction) } else if direction < 0 { newNote(direction:direction) }; return
        }
        let target = index + direction
        if target < 0 { newNote(direction:direction) }
        else if all.indices.contains(target) { openNote(all[target].id,direction:direction) }
    }
    private func refresh() async throws {
        guard let store else { return }
        refreshGeneration += 1; let generation = refreshGeneration
        let query = library.search.stringValue; let tag = library.selectedTag
        async let summaries = store.summaries()
        async let tags = store.tags()
        async let results = store.summaries(query:query,tag:tag)
        async let pending = store.hasPendingSync()
        let (allNotes,tagValues,filtered,pendingSync) = try await (summaries,tags,results,pending)
        guard generation == refreshGeneration else { return }
        all = allNotes; hasPendingSync = pendingSync; backgroundSyncError = allNotes.first(where:{ $0.error != nil })?.error
        library.update(filtered,tagValues:tagValues,selected:current?.id,query:query)
        showStatus()
    }
    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task {
            do { try await Task.sleep(for:.milliseconds(80)); try await refresh() } catch is CancellationError { } catch { showError(error) }
        }
    }
    private func deleteNote() {
        guard store != nil, deletionAlert == nil else { return }
        flush()
        guard let note = current, !deleting.contains(note.id) else { return }
        if editor.textView.string.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, note.attachments.isEmpty {
            if temporary { leaveDeletedNote(note.id); showDeletionNotice() }
            else { commitDeletion(note.id) }
            return
        }
        let alert = NSAlert()
        alert.messageText = "Delete this note?"
        alert.informativeText = "It will also be deleted from Memos when synced."
        alert.addButton(withTitle:"Cancel")
        alert.addButton(withTitle:"Delete").hasDestructiveAction = true
        deletionAlert = alert
        editor.show()
        alert.beginSheetModal(for:editor.panel) { [weak self] response in
            guard let self else { return }
            deletionAlert = nil
            if response == .alertSecondButtonReturn { commitDeletion(note.id) }
        }
    }
    private func commitDeletion(_ id: String) {
        guard let store, deleting.insert(id).inserted else { return }
        let previous = saveQueue
        saveQueue = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            defer { deleting.remove(id) }
            do {
                let now = Date(); let deadline = now.addingTimeInterval(5)
                try await store.markDelete(id,now:now,undoInterval:5)
                cache.removeValue(forKey:id); unsavedRecovery.removeValue(forKey:id)
                leaveDeletedNote(id)
                try await refresh()
                showBriefNotice("Note deleted",undo:.deletion(id,store),until:deadline)
                await sync?.schedule(after:0)
            } catch { showError(error) }
        }
    }
    private func leaveDeletedNote(_ id: String) {
        let index = all.firstIndex(where:{ $0.id == id })
        let adjacent = index.flatMap { i in all.indices.contains(i + 1) ? all[i + 1].id : (i > 0 ? all[i - 1].id : nil) }
        all.removeAll { $0.id == id }; cache.removeValue(forKey:id)
        guard current?.id == id else { return }
        let destination = adjacent ?? draftReturnID.flatMap { id in all.contains(where:{ $0.id == id }) ? id : nil } ?? all.first?.id
        hasUnsavedText = false; autosave?.cancel(); positionTask?.cancel()
        if let destination { openNote(destination,direction:1) }
        else {
            temporary = false; current = nil
            newNote(notify:false)
        }
    }
    private func showDeletionNotice(notify: Bool = true) {
        if notify { showBriefNotice("Note deleted") }
        Task { await sync?.schedule(after:0) }
    }
    private func insertAttachment(_ data: Data? = nil, file: URL? = nil, name: String, mime: String, target: Note? = nil) {
        guard let store, var note = target ?? current else { return }
        if let selected = current, selected.id == note.id {
            note = selected
            if temporary { note.created = Date(); note.modified = note.created; current = note }
            temporary = false; hasUnsavedText = true; flush()
        } else if note.base == nil, note.content.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
            note.created = Date(); note.modified = note.created
        }
        let attachment = Attachment(id:UUID().uuidString.lowercased(),noteID:note.id,filename:name,mime:mime)
        let position = editor.textView.selectedRange()
        let job = UUID()
        attachmentCounts[note.id,default:0] += 1
        attachmentWrites[job] = Task {
            defer {
                attachmentWrites.removeValue(forKey:job)
                let remaining = (attachmentCounts[note.id] ?? 1) - 1
                attachmentCounts[note.id] = remaining > 0 ? remaining : nil
            }
            do {
                try await Task.detached(priority:.userInitiated) {
                    if let file { try AttachmentFiles.copy(file,to:store.attachmentURL(attachment.id)) }
                    else if let data { try data.write(to:store.attachmentURL(attachment.id),options:.atomic) }
                    else { throw MemoizeError.message("Missing attachment file.") }
                }.value
                await saveQueue?.value
                // A deletion that completed during import must never be resurrected.
                if let saved = try await store.note(note.id), saved.deleted || saved.deleteAfter != nil { return }
                try await store.addAttachment(attachment,to:note)
                let reference = AttachmentFiles.reference(id:attachment.id,filename:name,mime:mime)
                if current?.id == note.id {
                    // Expose the persisted attachment immediately; highlighting may
                    // finish before the subsequent text autosave reads it back.
                    current?.attachments.append(attachment.remote)
                    // Use the current selection if typing has continued, preserving native insertion/undo.
                    let selection = editor.textView.selectedRange() == position ? position : editor.textView.selectedRange()
                    let source = editor.textView.string as NSString
                    let before = selection.location > 0 && source.character(at:selection.location - 1) != 10 ? "\n" : ""
                    let after = NSMaxRange(selection) < source.length && source.character(at:NSMaxRange(selection)) != 10 ? "\n" : ""
                    editor.textView.breakUndoCoalescing()
                    let undo = editor.textView.ownUndoManager
                    // File I/O completes outside a keyboard event. With automatic
                    // grouping, the next keystroke can join this paste's open group.
                    let isolated = undo.groupingLevel == 0 && undo.groupsByEvent
                    if isolated { undo.groupsByEvent = false }
                    undo.beginUndoGrouping()
                    undo.registerUndo(withTarget:self) { controller in
                        controller.changeAttachmentAssociation(attachment,associated:false)
                    }
                    editor.textView.insertText(before + reference + after,replacementRange:selection)
                    undo.setActionName("Attach File")
                    undo.endUndoGrouping()
                    if isolated { undo.groupsByEvent = true }
                    editor.textView.breakUndoCoalescing()
                    flush(); updateAttachments(MarkdownAnalysis.parse(editor.textView.string))
                } else if let saved = try await store.note(note.id) {
                    _ = try await store.saveEdit(saved,content:saved.content + "\n" + reference,tags:saved.tags,cursor:saved.cursor,selectionLength:saved.selectionLength,scroll:saved.scroll)
                    cache.removeValue(forKey:note.id); try await refresh(); await sync?.schedule()
                }
            } catch {
                // A failed first attachment must not leave an empty captured note.
                if attachmentCounts[note.id] == 1, let selected = current, selected.id == note.id,
                   newNoteID == note.id, selected.attachments.isEmpty,
                   selected.content.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
                    discardEmptyNewNote(selected,content:selected.content)
                    await saveQueue?.value
                }
                showError(error); showBriefNotice("Couldn’t attach " + name)
            }
        }
    }
    private func finishAttachmentWrites() async {
        while !attachmentWrites.isEmpty {
            let tasks = Array(attachmentWrites.values)
            for task in tasks { await task.value }
        }
    }
    private func changeAttachmentAssociation(_ image: Attachment, associated: Bool) {
        // AppKit may undo the attachment before reporting the text change. Keep
        // its metadata available if that text change discards the new capture.
        if !associated, newNoteID != nil { draftAttachments[image.remote.name] = image.remote }
        editor.textView.ownUndoManager.registerUndo(withTarget:self) { controller in
            controller.changeAttachmentAssociation(image,associated:!associated)
        }
        guard let store else { return }
        if current?.id == image.noteID {
            // Attachment-only changes invalidate older autosave snapshots too.
            editGeneration += 1
            current?.attachments.removeAll { $0.name == image.remote.name }
            if associated { current?.attachments.append(image.remote) }
            if let current { cache[current.id] = current }
        }
        let previous = saveQueue
        saveQueue = Task { [weak self] in
            await previous?.value
            do {
                try await store.setAttachmentAssociation(image,associated:associated)
                try await self?.refresh()
                await self?.sync?.schedule(after:1)
            } catch { self?.showError(error) }
        }
        flush()
    }
    private func updateAttachments(_ references: MarkdownAnalysis) {
        guard let store, let note = current else { return }
        var files: [AttachedFile] = note.attachments.map { attachment in
            let id = String(attachment.name.dropFirst("attachments/".count))
            let url = store.cachedAttachmentURL(id)
            return AttachedFile(id:attachment.name,name:attachment.filename,mime:attachment.type,url:url,available:FileManager.default.fileExists(atPath:url.path))
        }
        let server = client?.server ?? UserDefaults.standard.string(forKey:"server").flatMap { try? MemosClient.normalizedServer($0) }
        var seen = Set(note.attachments.map(\.name))
        for reference in references.images + references.links {
            let name = AttachmentFiles.referencedName(reference.destination,server:server)
            guard seen.insert(name ?? reference.destination).inserted else { continue }
            if let name {
                let url = store.cachedAttachmentURL(String(name.dropFirst(12)))
                files.append(AttachedFile(id:name,name:"Attachment",mime:"application/octet-stream",url:url,available:FileManager.default.fileExists(atPath:url.path)))
            } else if references.images.contains(reference), let url = URL(string:reference.destination,relativeTo:client?.server)?.absoluteURL, ["https","http"].contains(url.scheme ?? "") {
                let file = store.externalImageURL(url)
                files.append(AttachedFile(id:reference.destination,name:url.lastPathComponent.isEmpty ? "Image" : url.lastPathComponent,mime:UTType(filenameExtension:url.pathExtension)?.preferredMIMEType ?? "image/png",url:file,available:FileManager.default.fileExists(atPath:file.path)))
            }
        }
        editor.attachmentStrip.update(files)
    }
    private func removeAttachment(_ file: AttachedFile) {
        guard let note = current, let store else { return }
        removingAttachment = true; defer { removingAttachment = false }
        let wasDraft = temporary || newNoteID == note.id
        editor.dismissAttachmentPreview()
        let references = MarkdownAnalysis.parse(editor.textView.string)
        let server = client?.server ?? UserDefaults.standard.string(forKey:"server").flatMap { try? MemosClient.normalizedServer($0) }
        let ranges = (references.images + references.links).filter { (AttachmentFiles.referencedName($0.destination,server:server) ?? $0.destination) == file.id }.map(\.range).sorted { $0.location > $1.location }
        var restoration = ReferenceRestoration(source:editor.textView.string,ranges:ranges)
        editor.textView.withUndoGroup("Remove Attachment") {
            if let remote = note.attachments.first(where:{ $0.name == file.id }) {
                changeAttachmentAssociation(Attachment(id:String(remote.name.dropFirst(12)),noteID:note.id,filename:remote.filename,mime:remote.type),associated:false)
            } else if file.id.hasPrefix("attachments/") {
                changeAttachmentAssociation(Attachment(id:String(file.id.dropFirst(12)),noteID:note.id,filename:file.name,mime:file.mime),associated:false)
            }
            for range in restoration.ranges.reversed() { editor.textView.insertText("",replacementRange:range) }
        }
        flush()
        updateAttachments(MarkdownAnalysis.parse(editor.textView.string))
        restoration.source = editor.textView.string
        showBriefNotice("Attachment removed",undo:.attachment(AttachmentRemoval(noteID:current?.id ?? note.id,wasDraft:wasDraft,file:file,store:store,restoration:restoration)))
    }
    private func restoreAttachment(_ removal: AttachmentRemoval) async throws {
        guard store === removal.store else { return }
        flush(); await saveQueue?.value
        guard store === removal.store else { return }
        if current?.id != removal.noteID {
            let saved = try await removal.store.note(removal.noteID)
            guard store === removal.store else { return }
            if let saved, !saved.deleted, saved.deleteAfter == nil {
                cache[saved.id] = saved; openNote(saved.id)
            } else if removal.wasDraft {
                newNote(notify:false)
            } else { throw MemoizeError.message("The original note is no longer available.") }
        }
        guard store === removal.store, let note = current else { return }
        let server = client?.server ?? UserDefaults.standard.string(forKey:"server").flatMap { try? MemosClient.normalizedServer($0) }
        let references = MarkdownAnalysis.parse(editor.textView.string)
        let alreadyReferenced = (references.images + references.links).contains { (AttachmentFiles.referencedName($0.destination,server:server) ?? $0.destination) == removal.file.id }
        let alreadyAssociated = note.attachments.contains { $0.name == removal.file.id }
        guard !alreadyReferenced || !alreadyAssociated else { return }
        let selection = editor.textView.selectedRange()
        var cursor = selection.location
        editor.textView.withUndoGroup("Restore Attachment") {
            if removal.file.id.hasPrefix("attachments/"), !alreadyAssociated {
                let image = Attachment(id:String(removal.file.id.dropFirst(12)),noteID:note.id,filename:removal.file.name,mime:removal.file.mime)
                changeAttachmentAssociation(image,associated:true)
            }
            if !alreadyReferenced {
                for insertion in removal.restoration.insertions(into:editor.textView.string).reversed() {
                    editor.textView.insertText(insertion.text,replacementRange:NSRange(location:insertion.location,length:0))
                    if insertion.location <= selection.location { cursor += (insertion.text as NSString).length }
                }
            }
        }
        editor.textView.setSelectedRange(NSRange(location:min(cursor,editor.textView.string.utf16.count),length:0))
        editor.panel.makeFirstResponder(editor.textView)
        flush(); await saveQueue?.value
        updateAttachments(MarkdownAnalysis.parse(editor.textView.string))
    }
    private func downloadAttachment(_ file: AttachedFile) {
        guard file.available else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = (file.name as NSString).lastPathComponent
        panel.directoryURL = FileManager.default.urls(for:.downloadsDirectory,in:.userDomainMask).first
        panel.canCreateDirectories = true
        panel.beginSheetModal(for:editor.panel) { [weak self] response in
            guard let self, response == .OK, let destination = panel.url else { return }
            panel.orderOut(nil)
            Task {
                do {
                    try await Task.detached(priority:.userInitiated) { try AttachmentFiles.export(file.url,to:destination) }.value
                    showBriefNotice("Attachment downloaded")
                } catch { showError(error) }
            }
        }
    }
    @objc private func toggleNote() {
        if NSApp.isHidden || !NSApp.windows.contains(where:{ $0.isVisible && !$0.isMiniaturized }) { showApp() }
        else { hideApp() }
    }
    private func showApp() {
        NSApp.unhide(nil)
        if current == nil { newNote(show:false) }
        editor.show()
    }
    @objc private func hideApp() {
        NSApp.hide(nil)
    }
    func applicationWillHide(_ notification: Notification) {
        flush(); rememberPosition(immediate:true)
    }
    @objc private func newAction() { newNote() }
    private func toggleLibrary() {
        if library.window.isVisible, !library.window.isMiniaturized, !NSApp.isHidden { library.hide(); editor.show() }
        else { library.show() }
    }
    @objc private func libraryAction() { toggleLibrary() }
    @objc private func cycleWindow() {
        if NSApp.keyWindow === editor.panel { library.show() }
        else { editor.show() }
    }
    @objc private func globalSearchAction() { library.show(searching:true) }
    @objc private func findAction() { editor.find() }
    @objc private func settingsAction() { settings.show() }
    @objc private func olderAction() { navigate(1) }
    @objc private func newerAction() { navigate(-1) }
    @objc private func deleteAction() { deleteNote() }
    @objc private func syncAction() {
        flush()
        Task {
            await saveQueue?.value; try? await store?.retryFailures()
            if let sync { await sync.trigger(force:true) } else { await resumeConnection() }
        }
    }
    @objc private func exportNote() {
        guard let note = current else { return }
        let panel = NSSavePanel(); panel.nameFieldStringValue = "Note.md"; panel.allowedContentTypes = [UTType(filenameExtension:"md") ?? .plainText]
        panel.begin { result in
            guard result == .OK, let url = panel.url else { return }
            do { try note.content.write(to:url,atomically:true,encoding:.utf8) } catch { self.showError(error) }
        }
    }
    private func installMenus() {
        func item(_ title: String,_ action: Selector?,_ key: String = "",_ modifiers: NSEvent.ModifierFlags = .command) -> NSMenuItem {
            let item = NSMenuItem(title:title,action:action,keyEquivalent:key); item.target = self; item.keyEquivalentModifierMask = modifiers; return item
        }
        let menu = NSMenu()
        let app = NSMenu(); let about = item("About Memoize",#selector(NSApplication.orderFrontStandardAboutPanel(_:))); about.target = NSApp; app.addItem(about)
        let updates = NSMenuItem(title:"Check for Updates…",action:#selector(SPUStandardUpdaterController.checkForUpdates(_:)),keyEquivalent:"")
        updates.target = updater; updates.isEnabled = updater != nil; app.addItem(updates)
        app.addItem(.separator()); app.addItem(item("Settings…",#selector(settingsAction),",")); app.addItem(.separator())
        app.addItem(item("Hide Memoize",#selector(hideApp),"h"))
        let quit = item("Quit Memoize",#selector(NSApplication.terminate(_:)),"q"); quit.target = NSApp; app.addItem(quit)
        let appItem = NSMenuItem(); appItem.submenu = app; menu.addItem(appItem)
        let file = NSMenu(title:"File"); file.addItem(item("New Note",#selector(newAction),"n")); file.addItem(item("Delete Note",#selector(deleteAction),"",[])); file.addItem(item("Export Current Note…",#selector(exportNote))); file.addItem(item("Sync Now",#selector(syncAction),"r"))
        let fileItem = NSMenuItem(title:"File",action:nil,keyEquivalent:""); fileItem.submenu = file; menu.addItem(fileItem)
        file.addItem(.separator()); file.addItem(NSMenuItem(title:"Close Window",action:#selector(NSWindow.performClose(_:)),keyEquivalent:"w"))
        let edit = NSMenu(title:"Edit")
        for (title,selector,key) in [("Undo",Selector(("undo:")),"z"),("Redo",Selector(("redo:")),"Z"),("Cut",#selector(NSText.cut(_:)),"x"),("Copy",#selector(NSText.copy(_:)),"c"),("Paste",#selector(NSText.paste(_:)),"v"),("Select All",#selector(NSText.selectAll(_:)),"a")] {
            let action = NSMenuItem(title:title,action:selector,keyEquivalent:key); edit.addItem(action)
        }
        edit.addItem(.separator()); edit.addItem(item("Find in Note…",#selector(findAction),"f"))
        for (title,action,key) in [("Find Next",NSTextFinder.Action.nextMatch,"g"),("Find Previous",.previousMatch,"G")] {
            let find = NSMenuItem(title:title,action:#selector(NSResponder.performTextFinderAction(_:)),keyEquivalent:key); find.tag = action.rawValue; find.target = editor.textView; edit.addItem(find)
        }
        edit.addItem(item("Search All Notes…",#selector(globalSearchAction),"f",[.command,.shift]))
        let editItem = NSMenuItem(title:"Edit",action:nil,keyEquivalent:""); editItem.submenu = edit; menu.addItem(editItem)
        let window = NSMenu(title:"Window"); window.addItem(item("All Notes",#selector(libraryAction),"0"))
        window.addItem(item("Older Note",#selector(olderAction),"[")); window.addItem(item("Newer Note",#selector(newerAction),"]"))
        window.addItem(.separator())
        window.addItem(item("Cycle Through Windows",#selector(cycleWindow),"`"))
        let reverseCycle = item("Cycle Through Windows",#selector(cycleWindow),"`",[.command,.shift])
        reverseCycle.isAlternate = true; window.addItem(reverseCycle)
        let windowItem = NSMenuItem(title:"Window",action:nil,keyEquivalent:""); windowItem.submenu = window; menu.addItem(windowItem); NSApp.windowsMenu = window; NSApp.mainMenu = menu
        let status = NSMenu()
        let toggle = item("Show / Hide Memoize",#selector(toggleNote)); toggle.setShortcut(for:.toggleNote); status.addItem(toggle)
        status.addItem(item("New Note",#selector(newAction))); status.addItem(item("All Notes",#selector(libraryAction))); status.addItem(.separator())
        status.addItem(item("Sync Now",#selector(syncAction))); status.addItem(item("Settings…",#selector(settingsAction))); status.addItem(.separator())
        if let statusUpdates = updates.copy() as? NSMenuItem { status.addItem(statusUpdates) }
        let statusQuit = item("Quit Memoize",#selector(NSApplication.terminate(_:))); statusQuit.target = NSApp; status.addItem(statusQuit)
        statusItem = NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength); statusItem.button?.image = NSImage(systemSymbolName:"note.text",accessibilityDescription:"Memoize"); statusItem.menu = status
        statusItem.isVisible = UserDefaults.standard.bool(forKey:"showMenuBar")
    }
    private func showError(_ error: any Error) { statusText = error.localizedDescription; syncState = .failed; showStatus(); library?.status.stringValue = statusText }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { showApp(); return true }
    func applicationDidBecomeActive(_ notification: Notification) {
        Task { if let sync { await sync.schedule(after:0.5) } else { await resumeConnection() } }
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if readyToQuit { return .terminateNow }
        if shuttingDown { return .terminateCancel }
        shuttingDown = true; rememberPosition(immediate:true); flush()
        Task {
            await noticeUndoTask?.value
            await finishAttachmentWrites(); flush()
            await saveQueue?.value; await positionTask?.value
            if let diskError {
                let alert = NSAlert(); alert.messageText = "Your latest changes could not be saved."; alert.informativeText = diskError; alert.addButton(withTitle:"Keep Memoize Open"); alert.addButton(withTitle:"Export Note…")
                if alert.runModal() == .alertSecondButtonReturn { exportUnsaved() }
                shuttingDown = false
            } else { await sync?.stop(); readyToQuit = true; NSApp.terminate(nil) }
        }
        // Keep the normal run loop alive while Swift tasks flush their snapshots.
        // AppKit's terminateLater modal loop does not service the main actor reliably.
        return .terminateCancel
    }
    private func exportUnsaved() {
        let snapshots = Array(unsavedRecovery.values)
        let picker = NSOpenPanel(); picker.canChooseDirectories = true; picker.canChooseFiles = false; picker.canCreateDirectories = true
        picker.prompt = "Export Unsaved Notes"
        picker.begin { response in
            guard response == .OK, let directory = picker.url else { return }
            do {
                for note in snapshots { try note.content.write(to:directory.appending(path:"Memoize-\(note.id).md"),atomically:true,encoding:.utf8) }
                self.showBriefNotice("Unsaved notes exported")
            } catch { self.showError(error) }
        }
    }
    /// A setup helper accepts the token over stdin, never in command-line arguments or files.
    /// Import is strictly read-only on the configured server.
    private func connectFromInput() async throws {
        guard let token = readLine(), !token.isEmpty else { throw MemoizeError.message("Missing token on stdin.") }
        guard let address = UserDefaults.standard.string(forKey:"server"), !address.isEmpty else { throw MemoizeError.message("Configure your Memos server in Settings first.") }
        let server = try MemosClient.normalizedServer(address)
        let unauthenticated = MemosClient(server:server,token:token)
        let user = try await unauthenticated.authenticate()
        let namespace = Credentials.namespace(server:server,user:user)
        try await Task.detached { try Credentials.save(token,account:namespace) }.value
        let api = MemosClient(server:server,token:token,user:user)
        let notes = try await api.list()
        try await openStore(namespace:namespace)
        for note in notes { try await store?.reconcile(note) }
        UserDefaults.standard.set(server.absoluteString,forKey:"server"); UserDefaults.standard.set(user,forKey:"user"); UserDefaults.standard.set(namespace,forKey:"namespace")
        FileHandle.standardOutput.write(Data("Connected. Token stored in Keychain; imported \(notes.count) notes without server writes.\n".utf8))
        readyToQuit = true; DispatchQueue.main.async { NSApp.terminate(nil) }
    }
    private func smokeTest() async throws {
        try await openStore(namespace:"smoke-" + UUID().uuidString)
        if ProcessInfo.processInfo.environment["MEMOIZE_SMOKE_EDITOR_ONLY"] == "1" {
            try await smokeMenuAndDeletion()
            try await smokeFooterAndUndo()
            try await smokeListEditing(throughKeyboard:false)
            try await smokeAttachmentUndo()
            try await smokeAttachmentPresentation()
            try await smokeRapidTyping()
            DispatchQueue.main.async { NSApp.terminate(nil) }
            return
        }
        newNote()
        guard editor.toast.message.stringValue == "New note" else { throw MemoizeError.message("New-note toast did not appear.") }
        editor.textView.insertText("# Memoize\nNative source editing. Find native words. #work/macOS\n\n```swift\nlet note = \"Fast and local\"\n```",replacementRange:NSRange(location:0,length:0))
        flush(); await saveQueue?.value
        try await refresh(); library.show()
        guard all.count == 1, editor.textView.textLayoutManager != nil else { throw MemoizeError.message("UI smoke test failed") }
        FileHandle.standardOutput.write(Data("BENCH fresh-store launch, create, commit, and list: \(appStart.duration(to:.now))\n".utf8))
        try await Task.sleep(for:.milliseconds(250))
        try smokeVisibleEditorText()
        let source = editor.textView.string
        let findPasteboard = NSPasteboard(name:.find)
        let previousFindItems = findPasteboard.pasteboardItems?.map { item -> NSPasteboardItem in
            let copy = NSPasteboardItem()
            for type in item.types { if let data = item.data(forType:type) { copy.setData(data,forType:type) } }
            return copy
        } ?? []
        defer { findPasteboard.clearContents(); findPasteboard.writeObjects(previousFindItems) }
        editor.find(query:"native")
        try await Task.sleep(for:.milliseconds(300))
        let match = (source as NSString).range(of:"Native")
        try captureSmokeWindow(editor.panel,name:"find-editor")
        guard editor.textView.selectedRange() == match,
              editor.textView.noteFinder.finder.incrementalMatchRanges.contains(NSValue(range:match)),
              editor.textView.noteFinder.finder.incrementalMatchRanges.count == 2,
              let rect = editor.textView.noteFinder.rects(forCharacterRange:match)?.first?.rectValue,
              rect.width > 0, rect.width < editor.textView.bounds.width / 2 else { throw MemoizeError.message("Find did not highlight the specific matching word: selected \(editor.textView.selectedRange()), matches \(editor.textView.noteFinder.finder.incrementalMatchRanges), rects \(editor.textView.noteFinder.rects(forCharacterRange:match) ?? []), container \(editor.textView.noteFinder.finder.findBarContainer != nil).") }
        editor.textView.noteFinder.finder.performAction(.nextMatch)
        try await Task.sleep(for:.milliseconds(100))
        guard editor.textView.selectedRange().location > match.location else { throw MemoizeError.message("Find Next did not move to the next word: \(editor.textView.selectedRange()).") }
        editor.textView.noteFinder.finder.performAction(.previousMatch)
        try await Task.sleep(for:.milliseconds(100))
        guard editor.textView.selectedRange() == match else { throw MemoizeError.message("Find Previous did not return to the first word.") }
        editor.textView.noteFinder.finder.performAction(.hideFindInterface)
        editor.show()
        editor.textView.breakUndoCoalescing()
        editor.textView.insertText("\nUndo check",replacementRange:NSRange(location:(source as NSString).length,length:0))
        editor.textView.breakUndoCoalescing(); editor.textView.undoManager?.undo()
        guard editor.textView.string == source else { throw MemoizeError.message("Native undo changed Markdown source.") }
        try await Task.sleep(for:.milliseconds(250)); await saveQueue?.value
        guard let saved = try await store?.note(try requireSmoke(current?.id)), saved.content == source else { throw MemoizeError.message("Native undo was not autosaved.") }
        editor.textView.undoManager?.redo()
        try await Task.sleep(for:.milliseconds(250)); await saveQueue?.value
        guard let redone = try await store?.note(saved.id), redone.content == source + "\nUndo check" else { throw MemoizeError.message("Native redo was not autosaved.") }
        editor.textView.undoManager?.undo(); flush(); await saveQueue?.value
        let original = try requireSmoke(current?.id)
        editor.textView.setSelectedRange(NSRange(location:7,length:3))
        newNote()
        guard temporary else { throw MemoizeError.message("Newest navigation did not produce a draft.") }
        navigate(1)
        guard current?.id == original, editor.textView.selectedRange() == NSRange(location:7,length:3) else { throw MemoizeError.message("Draft navigation did not restore the cursor.") }
        var switches: [Double] = []
        for _ in 0..<30 {
            let start = ContinuousClock.now; newNote(show:false); navigate(1)
            let d = start.duration(to:.now).components; switches.append(Double(d.seconds)*1000 + Double(d.attoseconds)/1e15)
        }
        FileHandle.standardOutput.write(Data("BENCH draft/back navigation p95 milliseconds: \(switches.sorted()[28])\n".utf8))
        editor.hide(); try await Task.sleep(for:.milliseconds(100))
        let reveal = ContinuousClock.now; editor.show()
        FileHandle.standardOutput.write(Data("BENCH warm reveal: \(reveal.duration(to:.now))\n".utf8))
        guard editor.textView.string == source else { throw MemoizeError.message("Navigation restored stale source.") }
        let wasPinned = editor.panel.level == .floating
        editor.setPinned(true); library.show()
        guard library.window.level == editor.panel.level,
              NSApp.orderedWindows.first(where:{ $0 === library.window || $0 === editor.panel }) === library.window else { throw MemoizeError.message("Pinned library did not appear above the editor.") }
        editor.setPinned(false)
        guard library.window.level == .normal else { throw MemoizeError.message("Unpin did not restore the library level.") }
        editor.setPinned(wasPinned)
        let menuBarWasVisible = settings.model.menuBar
        settings.model.menuBar = false
        guard !statusItem.isVisible else { throw MemoizeError.message("Menu-bar setting did not hide the icon.") }
        settings.model.menuBar = menuBarWasVisible
        try await smokeEdit { self.settings.show() }
        try await Task.sleep(for:.milliseconds(500))
        try await smokeEdit { self.toggleNote() }
        guard NSApp.isHidden else { throw MemoizeError.message("The global shortcut did not hide the entire app.") }
        try await Task.sleep(for:.milliseconds(500))
        try await smokeEdit { self.toggleNote() }; try await Task.sleep(for:.milliseconds(500))
        guard !NSApp.isHidden, library.window.isVisible, editor.panel.isVisible,
              settings.loadedWindow?.isVisible == true else { throw MemoizeError.message("Restoring the app lost an open window: hidden=\(NSApp.isHidden), active=\(NSApp.isActive), library=\(library.window.isVisible), editor=\(editor.panel.isVisible), settings=\(settings.loadedWindow?.isVisible == true).") }
        settings.loadedWindow?.performClose(nil)
        try await smokeEdit { self.toggleNote() }; try await Task.sleep(for:.milliseconds(500))
        try await smokeEdit { self.toggleNote() }; try await Task.sleep(for:.milliseconds(500))
        guard settings.loadedWindow?.isVisible == false else { throw MemoizeError.message("Restoring the app reopened closed Settings.") }
        for _ in 0..<80 {
            if NSApp.isActive { break }
            try await Task.sleep(for:.milliseconds(25))
        }
        guard NSApp.isActive else { throw MemoizeError.message("macOS did not grant the test app foreground activation after restoring it. Keyboard-focus checks require an interactive foreground session.") }
        let insertion = editor.textView.selectedRange()
        try await smokeKey("`",code:50,modifiers:.command)
        guard library.window.isKeyWindow else { throw MemoizeError.message("Cmd+` did not focus the library: active=\(NSApp.isActive), editorKey=\(editor.panel.isKeyWindow), key=\(String(describing:NSApp.keyWindow)).") }
        try await smokeKey("`",code:50,modifiers:.command)
        guard editor.panel.isKeyWindow, editor.panel.firstResponder === editor.textView,
              editor.textView.selectedRange() == insertion else { throw MemoizeError.message("Cmd+` did not return to the editor insertion point.") }
        try await smokeEdit { self.library.window.performClose(nil) }
        guard !NSApp.isHidden, !library.window.isVisible, editor.panel.isVisible, editor.libraryToggle.state == .off else { throw MemoizeError.message("Closing the library affected the app or left its toggle active.") }
        try await Task.sleep(for:.milliseconds(100))
        try await smokeEdit { self.library.show() }; try await Task.sleep(for:.milliseconds(100))
        guard editor.panel.isVisible else { throw MemoizeError.message("Opening the library did not restore the note.") }
        editor.show()
        for (index,content) in ["# A quieter workspace\nKeep the useful things close. Leave room to think. #ideas", "# Weekend\nWalk by the coast. Find a little place for coffee. #personal", "# Reading list\n- The Creative Act\n- A Philosophy of Software Design #books", "# Small details\nGood defaults. Familiar controls. Space to write. #design"].enumerated() {
            try await store?.reconcile(RemoteMemo(name:"memos/" + UUID().uuidString.lowercased(),content:content,createTime:Date().addingTimeInterval(-Double(index + 1)*86_400)))
        }
        try await refresh()
        library.search.stringValue = "quiet"
        try await refresh()
        guard library.notes.count == 1, library.notes.first?.excerpt?.contains("quieter") == true else { throw MemoizeError.message("Global search omitted the matching excerpt.") }
        try captureSmokeWindow(library.window,name:"find-library")
        library.search.stringValue = ""; try await refresh()
        editor.textView.setSelectedRange(NSRange(location:source.utf16.count,length:0))
        try await Task.sleep(for:.milliseconds(250))
        for (name,window) in [("editor",editor.panel as NSWindow),("library",library.window)] {
            try captureSmokeWindow(window,name:name)
        }
        let bitmap = try requireSmoke(NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:640,pixelsHigh:280,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0))
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep:bitmap)
        NSColor.systemTeal.setFill(); NSRect(x:0,y:0,width:640,height:280).fill()
        NSColor.systemIndigo.setFill(); NSRect(x:360,y:0,width:280,height:280).fill()
        ("Attachment" as NSString).draw(at:NSPoint(x:32,y:116),withAttributes:[.font:NSFont.systemFont(ofSize:32,weight:.medium),.foregroundColor:NSColor.white])
        NSGraphicsContext.restoreGraphicsState()
        let png = try requireSmoke(bitmap.representation(using:.png,properties:[:]))
        insertAttachment(png,name:"Screenshot.png",mime:"image/png")
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        let imageNote = try requireSmoke(try await store?.note(original))
        guard let image = imageNote.attachments.first, imageNote.content.contains("![Screenshot.png](/file/" + image.name + ")"),
              try Data(contentsOf:try requireSmoke(store?.attachmentURL(String(image.name.dropFirst(12))))) == png else { throw MemoizeError.message("Offline image paste did not persist the reference and original bytes.") }
        try await smokeKey("z",code:6,modifiers:.command); flush(); await saveQueue?.value
        guard editor.textView.string == source,
              try await store?.note(original)?.attachments.isEmpty == true,
              try await store?.pendingAttachments(noteID:original).isEmpty == true else { throw MemoizeError.message("Undo image paste left its source, attachment, or upload behind: source=\(String(reflecting:editor.textView.string)), expected=\(String(reflecting:source)), attachments=\(try await store?.note(original)?.attachments.count ?? -1), pending=\(try await store?.pendingAttachments(noteID:original).count ?? -1).") }
        try await smokeKey("Z",code:6,modifiers:[.command,.shift]); flush(); await saveQueue?.value
        guard editor.textView.string == imageNote.content,
              try await store?.note(original)?.attachments == imageNote.attachments else { throw MemoizeError.message("Redo image paste did not restore source and attachment together.") }
        try await Task.sleep(for:.milliseconds(250))
        guard !editor.attachmentStrip.files.isEmpty else { throw MemoizeError.message("Cached attachment did not appear.") }
        editor.attachmentStrip.buttons.first?.performClick(nil)
        try await Task.sleep(for:.milliseconds(250))
        guard editor.isShowingAttachmentPreview else { throw MemoizeError.message("Attachment preview did not appear.") }
        try await smokeEdit { self.toggleNote() }
        guard NSApp.isHidden else { throw MemoizeError.message("Hide with an image preview did not hide the app.") }
        try await Task.sleep(for:.milliseconds(100))
        try await smokeEdit { self.toggleNote() }; try await Task.sleep(for:.milliseconds(100))
        try await smokeUndoShortcuts()
        try await smokeListEditing()
        try await smokeNewNoteLifecycle()
        try await smokeRapidTyping()
        openNote(original)
        FileHandle.standardOutput.write(Data("Memoize UI smoke passed: app-wide hide/restore including Settings, Cmd+` focus/cursor restoration, directional slides, TextKit 2, exact-word find, search excerpts, pin ordering, menu-bar visibility, manual hide, hover/toast-only toolbar, first-input timestamps, new-note discard and Undo/Redo, immediate Clear → Back toast, reopened empty-note preservation, offline attachments and attachment Undo/Redo, editor → SQLite → library.\n".utf8))
        if let seconds = ProcessInfo.processInfo.environment["MEMOIZE_SMOKE_IDLE_SECONDS"].flatMap(Double.init) { try await Task.sleep(for:.seconds(seconds)) }
        // Leave the Swift task before AppKit enters its deferred-termination event loop.
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }
    private func smokeNewNoteLifecycle() async throws {
        guard let store else { throw MemoizeError.message("Missing smoke store.") }
        func replace(_ text: String) async throws {
            try await smokeKey("a",code:0,modifiers:.command)
            editor.textView.breakUndoCoalescing()
            try await smokeEdit { [self] in editor.textView.insertText(text,replacementRange:editor.textView.selectedRange()) }
            editor.textView.breakUndoCoalescing()
            guard sameSource(editor.textView.string,text) else { throw MemoizeError.message("Native fixture input did not reach the editor: expected \(String(reflecting:text)), actual \(String(reflecting:editor.textView.string)).") }
        }
        let pointer = NSEvent.mouseLocation
        let screenTop = try requireSmoke(NSScreen.screens.first?.frame.maxY)
        defer { CGWarpMouseCursorPosition(CGPoint(x:pointer.x,y:screenTop - pointer.y)) }
        func hoverToolbar(_ hovered: Bool) throws {
            let point = hovered ? editor.panel.convertPoint(toScreen:editor.toolbar.convert(NSPoint(x:editor.toolbar.bounds.midX,y:editor.toolbar.bounds.midY),to:nil)) : NSPoint(x:1,y:screenTop - 1)
            guard CGWarpMouseCursorPosition(CGPoint(x:point.x,y:screenTop - point.y)) == .success else { throw MemoizeError.message("Could not position pointer for the native hover test.") }
            editor.toolbar.refreshHover()
        }
        newNote()
        try hoverToolbar(false)
        guard editor.toolbar.isRevealed else { throw MemoizeError.message("New-note toast did not reveal the toolbar.") }
        for event in [SyncEvent.working,.idle,.offline("test offline")] {
            await syncEvent(event)
            guard editor.toolbar.isRevealed else { throw MemoizeError.message("Sync status dismissed a toast.") }
        }
        try await Task.sleep(for:.seconds(1.7))
        try hoverToolbar(false)
        guard !editor.toolbar.isRevealed else { throw MemoizeError.message("Toolbar stayed visible after toast expired: toast=\(editor.toolbar.hasToast), briefNotice=\(briefNotice?.message ?? "none"), pointer=\(editor.toolbar.convert(editor.panel.mouseLocationOutsideOfEventStream,from:nil)), bounds=\(editor.toolbar.bounds).") }
        let editorHeight = editor.scrollView.frame.height
        try hoverToolbar(true); editor.panel.contentView?.layoutSubtreeIfNeeded()
        guard editor.toolbar.isRevealed, editor.scrollView.frame.height == editorHeight else { throw MemoizeError.message("Hover did not reveal the toolbar without moving the editor.") }
        try await Task.sleep(for:.milliseconds(100))
        try hoverToolbar(true)
        try captureSmokeWindow(editor.panel,name:"toolbar-hover")
        try hoverToolbar(false)
        for event in [SyncEvent.working,.idle,.offline("test offline"),.failed("test failure")] {
            await syncEvent(event)
            guard !editor.toolbar.isRevealed else { throw MemoizeError.message("Sync status revealed the toolbar.") }
        }
        try await Task.sleep(for:.milliseconds(100))
        try hoverToolbar(false)
        editor.panel.displayIfNeeded()
        try captureSmokeWindow(editor.panel,name:"toolbar-hidden")
        await syncEvent(.idle)

        current?.created = Date().addingTimeInterval(-7_200)
        let blankID = try requireSmoke(current?.id)
        try await replace(" \n\t"); flush(); await saveQueue?.value
        guard temporary, try await store.note(blankID) == nil else { throw MemoizeError.message("Whitespace created a note.") }
        let firstInput = Date(); try await replace("First capture"); flush(); await saveQueue?.value
        let first = try requireSmoke(try await store.note(blankID))
        guard first.created >= firstInput.addingTimeInterval(-0.001), first.created <= Date() else { throw MemoizeError.message("Creation time used the blank window's age.") }
        // AppKit groups Undo by event. Let this edit finish before simulating the next input.
        try await Task.sleep(for:.milliseconds(75))
        editor.hide(); try await Task.sleep(for:.milliseconds(100)); editor.show(); try await Task.sleep(for:.milliseconds(100))
        guard newNoteID == first.id else { throw MemoizeError.message("Hiding ended the new-note session.") }
        try await replace(""); await saveQueue?.value
        guard temporary, current?.id != first.id,
              try await store.note(first.id)?.deleteAfter != nil,
              try await store.note(first.id)?.content == "First capture",
              !all.contains(where:{ $0.id == first.id }) else { throw MemoizeError.message("Cleared new note was retained or saved as empty.") }
        guard editor.toolbar.isRevealed, editor.toast.message.stringValue == "Note deleted" else { throw MemoizeError.message("Discard did not show the deletion toast.") }
        try await Task.sleep(for:.milliseconds(75))
        let restoredAfter = Date()
        try await smokeKey("z",code:6,modifiers:.command); flush(); await saveQueue?.value
        let restored = try requireSmoke(current)
        let restoredOnDisk = try await store.note(restored.id)
        guard restored.content == "First capture", restored.id != first.id, restored.created >= restoredAfter,
              restoredOnDisk?.content == "First capture" else { throw MemoizeError.message("Undo across an empty draft failed: source=\(String(reflecting:restored.content)), visible=\(String(reflecting:editor.textView.string)), disk=\(String(reflecting:restoredOnDisk?.content)), freshID=\(restored.id != first.id), timeDelta=\(restored.created.timeIntervalSince(restoredAfter)), canRedo=\(editor.textView.ownUndoManager.canRedo).") }
        try await Task.sleep(for:.milliseconds(75))
        try await smokeKey("Z",code:6,modifiers:[.command,.shift]); await saveQueue?.value
        guard temporary, editor.textView.string.isEmpty,
              try await store.note(restored.id)?.deleteAfter != nil else { throw MemoizeError.message("Redo did not discard the restored new note.") }

        let unsavedID = try requireSmoke(current?.id)
        try await replace("Before autosave"); try await replace(""); navigate(1); await saveQueue?.value
        guard try await store.note(unsavedID) == nil,
              editor.toolbar.isRevealed, editor.toast.message.stringValue == "Note deleted" else { throw MemoizeError.message("Clear then immediate Back retained a draft or lost its deletion toast.") }
        if !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
            navigate(-1)
            guard let transition = editor.scrollView.layer?.animation(forKey:kCATransition) as? CATransition,
                  transition.subtype == .fromRight else { throw MemoizeError.message("Newer navigation did not animate: layer=\(editor.scrollView.layer != nil), keys=\(editor.scrollView.layer?.animationKeys() ?? []), visible=\(editor.panel.isVisible), temporary=\(temporary), loaded=\(current?.id == navigationID).") }
            navigate(1)
            guard let transition = editor.scrollView.layer?.animation(forKey:kCATransition) as? CATransition,
                  transition.subtype == .fromLeft else { throw MemoizeError.message("Older navigation did not animate to the left.") }
        }
        newNote()
        try await replace("Keep after returning"); flush(); await saveQueue?.value
        let kept = try requireSmoke(current)
        newNote(); navigate(1)
        guard current?.id == kept.id, newNoteID == nil else { throw MemoizeError.message("Leaving did not end the new-note session.") }
        try await replace(""); flush(); await saveQueue?.value
        let existing = try requireSmoke(try await store.note(kept.id))
        guard existing.content.isEmpty, abs(existing.created.timeIntervalSince(kept.created)) < 0.001, existing.deleteAfter == nil, !existing.deleted else { throw MemoizeError.message("An existing note was auto-deleted or redated.") }

        newNote(); current?.created = Date().addingTimeInterval(-7_200)
        let imageStarted = Date()
        let png = Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
        insertAttachment(png,name:"First image.png",mime:"image/png")
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        let imageNote = try requireSmoke(current)
        guard imageNote.created >= imageStarted.addingTimeInterval(-0.001), imageNote.attachments.count == 1, !imageNote.content.isEmpty else { throw MemoizeError.message("Image-first note did not preserve its content and first-input date: delta=\(imageNote.created.timeIntervalSince(imageStarted)), attachments=\(imageNote.attachments.count).") }
        try await smokeKey("\u{7f}",code:51)
        guard editor.textView.string == String(imageNote.content.dropLast()) else { throw MemoizeError.message("Backspace after an image paste changed the wrong source.") }
        try await smokeKey("z",code:6,modifiers:.command)
        guard editor.textView.string == imageNote.content else { throw MemoizeError.message("Undo after image paste also undid the image insertion.") }
        try await replace(""); await saveQueue?.value
        guard temporary, try await store.note(imageNote.id)?.deleteAfter != nil else { throw MemoizeError.message("Cleared image-first note was not discarded.") }
        try await smokeKey("z",code:6,modifiers:.command); flush(); await saveQueue?.value
        let restoredImage = try requireSmoke(current)
        guard restoredImage.content == imageNote.content,
              restoredImage.attachments == imageNote.attachments,
              try await store.needsAttachmentCopy(restoredImage) else { throw MemoizeError.message("Undo did not restore a cleared image capture's attachment metadata: content=\(String(reflecting:restoredImage.content)), expected=\(String(reflecting:imageNote.content)), attachments=\(restoredImage.attachments.count)/\(imageNote.attachments.count), temporary=\(temporary), historyImages=\(draftAttachments.count).") }
        FileHandle.standardOutput.write(Data("New-note lifecycle passed: two-hour blank, whitespace, first text/image, clear before/after save, deletion toast, native Undo/Redo, hide/reveal, leave/reopen.\n".utf8))
    }
    private func smokeRapidTyping() async throws {
        let passage = "# A note\nPlain text stays visible as each character arrives.\n```swift\nlet value = 42\n```\n\n"
        for (name,source) in [("short","# "),("long",String(repeating:passage,count:1_100))] {
            FileHandle.standardOutput.write(Data("Typing check starting: \(name)\n".utf8))
            newNote()
            editor.textView.insertText(source,replacementRange:NSRange(location:0,length:editor.textView.string.utf16.count))
            try await Task.sleep(for:.milliseconds(250))
            let suffix = "Typing continuously without waiting for a pause."
            var expected = source
            var colorUpdates = 0
            var previousHighlight = editor.highlightedGeneration
            var rendering: [Double] = []
            var inputGaps: [Double] = []
            var previousInput = ContinuousClock.now
            for character in suffix {
                let start = ContinuousClock.now
                let gap = previousInput.duration(to:start).components
                inputGaps.append(Double(gap.seconds) * 1_000 + Double(gap.attoseconds) / 1e15)
                previousInput = start
                expected.append(character)
                editor.textView.insertText(String(character),replacementRange:editor.textView.selectedRange())
                editor.panel.displayIfNeeded()
                let duration = start.duration(to:.now).components
                rendering.append(Double(duration.seconds) * 1_000 + Double(duration.attoseconds) / 1e15)
                guard editor.textView.string == expected,
                      editor.scrollView.layer?.animation(forKey:kCATransition) == nil else { throw MemoizeError.message("Rapid typing lost text or remained behind a navigation animation.") }
                try await Task.sleep(for:.milliseconds(20))
                if editor.highlightedGeneration != previousHighlight {
                    colorUpdates += 1; previousHighlight = editor.highlightedGeneration
                }
            }
            // Small notes must update colors during continuous typing, not only on a pause.
            if name == "short", colorUpdates < 5 { throw MemoizeError.message("Highlighting waited for typing to stop.") }
            flush(); await saveQueue?.value
            guard try await store?.note(try requireSmoke(current?.id))?.content == expected else { throw MemoizeError.message("Rapid input did not persist exactly.") }
            try captureSmokeWindow(editor.panel,name:"rapid-typing-" + name)
            let percentile = Int(Double(rendering.count - 1) * 0.95)
            FileHandle.standardOutput.write(Data("BENCH \(name) rapid typing: native edit/draw p95 \(rendering.sorted()[percentile]) ms, input interval p95 \(inputGaps.sorted()[percentile]) ms (20 ms scheduled), \(colorUpdates) highlight updates while typing.\n".utf8))
        }
    }
    private func smokeFooterAndUndo() async throws {
        guard let store else { throw MemoizeError.message("Missing footer test store.") }
        let original = RemoteMemo(name:"memos/footer-undo",content:"Keep this exact note 🐈",createTime:Date(timeIntervalSince1970:1_700_000_000))
        try await store.reconcile(original); try await refresh()
        cache["footer-undo"] = try await store.note("footer-undo"); openNote("footer-undo")
        clearNotice(); editor.panel.contentView?.layoutSubtreeIfNeeded()
        let editorFrame = editor.scrollView.frame
        let exit = try requireSmoke(NSEvent.enterExitEvent(with:.mouseExited,location:.zero,modifierFlags:[],timestamp:0,windowNumber:editor.panel.windowNumber,context:nil,eventNumber:0,trackingNumber:0,userData:nil))
        editor.toolbar.mouseExited(with:exit)
        for (event,state) in [(SyncEvent.working,SyncIndicator.State.working),(.offline("No connection"),.offline),(.failed("Test failure"),.failed)] {
            await syncEvent(event)
            guard !editor.toolbar.isRevealed, !editor.syncIndicator.isHiddenOrHasHiddenAncestor,
                  editor.syncIndicator.state == state, editor.scrollView.frame == editorFrame else { throw MemoizeError.message("Sync hid its indicator, revealed the menu, or resized the editor.") }
        }
        try captureSmokeWindow(editor.panel,name:"sync-error-hidden")
        await syncEvent(.idle)
        try captureSmokeWindow(editor.panel,name:"sync-idle-hidden")
        let enter = try requireSmoke(NSEvent.enterExitEvent(with:.mouseEntered,location:.zero,modifierFlags:[],timestamp:0,windowNumber:editor.panel.windowNumber,context:nil,eventNumber:0,trackingNumber:0,userData:nil))
        editor.toolbar.mouseEntered(with:enter)
        guard editor.toolbar.isRevealed,
              (editor.toolbar.glass.contentView as? NSStackView)?.arrangedSubviews.contains(where:{ $0 is NSTextField }) == false else { throw MemoizeError.message("The menu is hidden on hover or contains status text.") }
        editor.toolbar.mouseExited(with:exit)
        showBriefNotice("New note"); await syncEvent(.working)
        guard editor.toolbar.isRevealed, !editor.toast.isHidden, editor.toast.undoButton.isHidden,
              editor.toast.message.stringValue == "New note", editor.syncIndicator.state == .working else { throw MemoizeError.message("Toast replaced sync state or offered a meaningless Undo.") }
        editor.panel.contentView?.layoutSubtreeIfNeeded()
        guard editor.toast.frame.width > 70, editor.toast.frame.width <= editor.panel.contentView!.bounds.width - 28,
              editor.scrollView.frame == editorFrame else { throw MemoizeError.message("The bottom toast is clipped or resized the editor.") }
        let appearance = editor.panel.appearance
        for (name,value) in [("footer-light-toast",NSAppearance.Name.aqua),("footer-dark-toast",.darkAqua)] {
            editor.panel.appearance = NSAppearance(named:value); try captureSmokeWindow(editor.panel,name:name)
        }
        editor.panel.appearance = appearance
        try await Task.sleep(for:.seconds(1.85))
        // AppKit can finish a fade on a later display pass. Real pointer hover
        // must not affect this synthetic mouse-exit check.
        let fadeDeadline = ContinuousClock.now.advanced(by:.milliseconds(500))
        while !editor.toast.isHidden, ContinuousClock.now < fadeDeadline { try await Task.sleep(for:.milliseconds(20)) }
        editor.toolbar.mouseExited(with:exit)
        guard !editor.toolbar.isRevealed, editor.toast.isHidden,
              !editor.syncIndicator.isHiddenOrHasHiddenAncestor else { throw MemoizeError.message("The toast didn't expire independently of sync: notice=\(briefNotice?.message ?? "none"), hidden=\(editor.toast.isHidden), menu=\(editor.toolbar.isRevealed).") }
        await syncEvent(.idle)
        deleteNote()
        let alert = try requireSmoke(deletionAlert)
        editor.panel.endSheet(alert.window,returnCode:.alertSecondButtonReturn)
        try await Task.sleep(for:.milliseconds(150)); await saveQueue?.value
        guard briefNotice?.undo != nil, !editor.toast.undoButton.isHidden,
              try await store.pending().contains(where:{ $0.id == "footer-undo" }) == false else { throw MemoizeError.message("Deletion didn't defer sync while Undo was available.") }
        try captureSmokeWindow(editor.panel,name:"footer-deletion-undo")
        let adjacent = try requireSmoke(current?.id)
        editor.textView.insertText("\nTyped after deletion 🐈",replacementRange:NSRange(location:editor.textView.string.utf16.count,length:0))
        let adjacentSource = editor.textView.string
        editor.toast.undoButton.performClick(nil); await noticeUndoTask?.value; await saveQueue?.value
        guard current?.id == "footer-undo", editor.textView.string == original.content,
              current?.created == original.createTime,
              try await store.note("footer-undo")?.deleteAfter == nil,
              try await store.note(adjacent)?.content == adjacentSource else { throw MemoizeError.message("Deletion Undo lost the original date or later typing in another note.") }
        cache.removeValue(forKey:adjacent)
        openNote(adjacent,show:false); openNote("footer-undo",show:false)
        try await Task.sleep(for:.milliseconds(100))
        guard current?.id == "footer-undo", navigationID == "footer-undo", editor.textView.string == original.content else { throw MemoizeError.message("A stale load overrode a later selection of the current note.") }
        clearNotice(); showBriefNotice("Attachment downloaded")
        try await Task.sleep(for:.milliseconds(600)); showBriefNotice("Attachment restored")
        let newerNoticeStart = ContinuousClock.now
        try await Task.sleep(for:.seconds(1.2))
        guard briefNotice?.message == "Attachment restored", !editor.toast.isHidden else { throw MemoizeError.message("A newer toast vanished: message=\(briefNotice?.message ?? "none"), hidden=\(editor.toast.isHidden), elapsed=\(newerNoticeStart.duration(to:.now)).") }
        clearNotice(); editor.toolbar.mouseExited(with:exit)
        FileHandle.standardOutput.write(Data("Footer/Undo passed: persistent sync, hover-only icon controls, independent toast/status, light/dark, stable layout, confirmation plus deferred deletion, action-specific restoration, later typing, original creation date, toast timer replacement.\n".utf8))
    }
    private func smokeMenuAndDeletion() async throws {
        guard let store else { throw MemoizeError.message("Missing menu test store.") }
        let kept = try await store.saveEdit(Note(),content:"Keep this note",tags:[],cursor:4,selectionLength:0,scroll:0)
        temporary = false; newNoteID = nil; install(kept,show:true); try await refresh()
        library.show()
        guard editor.libraryToggle.state == .on, library.window.level == editor.panel.level else { throw MemoizeError.message("The menu did not float with the note or activate its toggle.") }
        editor.toolbar.hasToast = true
        try captureSmokeWindow(editor.panel,name:"menu-toggle-active")
        showStatus()
        library.window.performClose(nil)
        guard !NSApp.isHidden, !library.window.isVisible, editor.panel.isVisible,
              editor.libraryToggle.state == .off else { throw MemoizeError.message("Closing the menu hid the app.") }
        editor.libraryToggle.performClick(nil)
        guard library.window.isVisible, editor.libraryToggle.state == .on else { throw MemoizeError.message("The menu toggle did not open the menu.") }
        editor.libraryToggle.performClick(nil)
        guard !library.window.isVisible, editor.libraryToggle.state == .off else { throw MemoizeError.message("The menu toggle did not close the menu.") }
        editor.show()
        guard !library.window.isVisible else { throw MemoizeError.message("Focusing the note reopened the closed menu.") }
        guard editor.panel.allowsToolTipsWhenApplicationIsInactive,
              editor.libraryToggle.toolTip == "Show notes (⌘0)" else { throw MemoizeError.message("Inactive note controls lost their tooltips.") }
        newNote()
        let draft = try requireSmoke(current?.id)
        editor.textView.deleteBackward(nil)
        guard current?.id == kept.id, !temporary, deletionAlert == nil,
              editor.toast.message.stringValue == "Note deleted", try await store.note(draft) == nil else { throw MemoizeError.message("Backspace did not discard an empty draft and restore its previous note.") }
        newNote()
        editor.textView.insertText("New capture",replacementRange:editor.textView.selectedRange())
        flush(); await saveQueue?.value
        let cleared = try requireSmoke(current?.id)
        editor.textView.insertText("",replacementRange:NSRange(location:0,length:editor.textView.string.utf16.count))
        editor.textView.deleteBackward(nil); await saveQueue?.value
        guard current?.id == kept.id, deletionAlert == nil,
              try await store.note(cleared)?.deleteAfter != nil, editor.toast.message.stringValue == "Note deleted" else { throw MemoizeError.message("Clear then Backspace failed to discard a new capture and return to the previous note.") }
        // A normal Backspace must still edit text, never delete a nonempty note.
        editor.textView.setSelectedRange(NSRange(location:editor.textView.string.utf16.count,length:0))
        editor.textView.deleteBackward(nil)
        guard current?.id == kept.id, editor.textView.string == "Keep this not", deletionAlert == nil else { throw MemoizeError.message("Nonempty Backspace changed the wrong state: selected=\(current?.id ?? "none"), expected=\(kept.id), text=\(String(reflecting:editor.textView.string)), range=\(editor.textView.selectedRange()), confirmation=\(deletionAlert != nil).") }
        deleteNote()
        let cancelled = try requireSmoke(deletionAlert)
        guard editor.panel.attachedSheet === cancelled.window else { throw MemoizeError.message("Nonempty deletion did not present a confirmation sheet.") }
        try captureSmokeWindow(cancelled.window,name:"delete-confirmation")
        editor.panel.endSheet(cancelled.window,returnCode:.alertFirstButtonReturn)
        try await Task.sleep(for:.milliseconds(200)); await saveQueue?.value
        guard try await store.note(kept.id)?.deleteAfter == nil, current?.id == kept.id else { throw MemoizeError.message("Cancelling deletion removed the note.") }
        deleteNote()
        let confirmed = try requireSmoke(deletionAlert)
        editor.panel.endSheet(confirmed.window,returnCode:.alertSecondButtonReturn)
        try await Task.sleep(for:.milliseconds(200)); await saveQueue?.value
        guard try await store.note(kept.id)?.deleteAfter != nil,
              try await store.pending().contains(where:{ $0.id == kept.id }) == false,
              current?.id != kept.id, !editor.toast.undoButton.isHidden, editor.toast.message.stringValue == "Note deleted" else { throw MemoizeError.message("Confirmed deletion did not offer Undo before syncing.") }
        let empty = RemoteMemo(name:"memos/empty-backspace",content:"")
        try await store.reconcile(empty); try await refresh(); openNote("empty-backspace")
        try await Task.sleep(for:.milliseconds(100))
        editor.textView.deleteBackward(nil); await saveQueue?.value
        guard deletionAlert == nil, try await store.note("empty-backspace")?.deleteAfter != nil else { throw MemoizeError.message("Backspace on a saved empty note required confirmation or did not delete it.") }
        let photo = RemoteMemo(name:"memos/photo-only",content:"",attachments:[RemoteAttachment(name:"attachments/fixture-photo")])
        try await store.reconcile(photo); try await refresh(); openNote("photo-only")
        try await Task.sleep(for:.milliseconds(100))
        editor.textView.deleteBackward(nil)
        let photoAlert = try requireSmoke(deletionAlert)
        editor.panel.endSheet(photoAlert.window,returnCode:.alertFirstButtonReturn)
        try await Task.sleep(for:.milliseconds(200))
        guard try await store.note("photo-only")?.deleteAfter == nil else { throw MemoizeError.message("An image-only note was mistaken for an empty note.") }
        FileHandle.standardOutput.write(Data("Menu/deletion passed: independent close, active toggle, shared pinning, background tooltips, empty-draft Backspace, Clear then Backspace, normal Backspace, Cancel, confirmed deletion with Undo, empty saved note, image-only protection.\n".utf8))
    }
    private func smokeAttachmentPresentation() async throws {
        guard let store else { throw MemoizeError.message("Missing attachment test store.") }
        let directory = support.appending(path:"DragFixture",directoryHint:.isDirectory)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        newNote()
        let failedID = try requireSmoke(current?.id)
        insertAttachment(file:directory,name:"Folder",mime:"application/octet-stream")
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        guard temporary, current?.id != failedID, !all.contains(where:{ $0.id == failedID }) else { throw MemoizeError.message("A rejected import left an empty captured note behind.") }
        let note = try await store.saveEdit(Note(),content:"A quiet workspace\n\n",tags:[],cursor:19,selectionLength:0,scroll:0)
        temporary = false; newNoteID = nil; install(note,show:true)
        let image = directory.appendingPathComponent("Preview 🐈.png")
        let text = directory.appendingPathComponent("Notes [draft].txt")
        let png = Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
        try png.write(to:image); try "File attachment 🐈".write(to:text,atomically:true,encoding:.utf8)
        let pasteboard = NSPasteboard.withUniqueName(); defer { pasteboard.releaseGlobally() }
        pasteboard.writeObjects([image as NSURL,text as NSURL])
        guard editor.textView.consume(pasteboard) else { throw MemoizeError.message("File drop/paste was not accepted.") }
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        try await Task.sleep(for:.milliseconds(250))
        let source = editor.textView.string
        let analysis = MarkdownAnalysis.parse(source)
        guard analysis.images.count == 1, analysis.links.count == 1,
              editor.attachmentStrip.files.count == 2, editor.attachmentStrip.files.allSatisfy(\.available),
              try await store.note(note.id)?.attachments.count == 2 else { throw MemoizeError.message("Image and file imports did not preserve source and attachment metadata.") }
        // Import retains a private durable copy, independent of the dropped original.
        try FileManager.default.removeItem(at:image); try FileManager.default.removeItem(at:text)
        let localImage = try requireSmoke(editor.attachmentStrip.files.first(where:{ $0.mime == "image/png" }))
        guard try Data(contentsOf:localImage.url) == png else { throw MemoizeError.message("Imported image bytes changed.") }
        let fileIndex = try requireSmoke(editor.attachmentStrip.files.firstIndex(of:localImage))
        let download = try requireSmoke(editor.attachmentStrip.buttons[fileIndex].menu?.items.first(where:{ $0.title == "Download Attachment…" }))
        guard download.isEnabled, let action = download.action else { throw MemoizeError.message("Attachment download is absent or disabled.") }
        let originalFiles = editor.attachmentStrip.files
        editor.attachmentStrip.update(originalFiles.reversed())
        NSApp.sendAction(action,to:download.target,from:download)
        try await Task.sleep(for:.milliseconds(300))
        let savePanel = try requireSmoke(editor.panel.attachedSheet as? NSSavePanel)
        guard savePanel.nameFieldStringValue == localImage.name else { throw MemoizeError.message("Download sheet did not preserve the filename.") }
        savePanel.cancel(nil)
        try await Task.sleep(for:.milliseconds(100))
        editor.attachmentStrip.update(originalFiles)
        let exported = directory.appendingPathComponent(localImage.name)
        try await Task.detached { try AttachmentFiles.export(localImage.url,to:exported) }.value
        guard try Data(contentsOf:exported) == png else { throw MemoizeError.message("Downloaded attachment bytes changed.") }
        showBriefNotice("Attachment downloaded")
        let reference = try requireSmoke(analysis.images.first)
        editor.textView.setSelectedRange(reference.range)
        guard editor.textView.writeSelection(to:pasteboard,types:[.string]), pasteboard.string(forType:.string) == (source as NSString).substring(with:reference.range) else { throw MemoizeError.message("Native Markdown copy failed.") }
        editor.textView.noteFinder.show(query:"Preview 🐈")
        guard editor.textView.selectedRange() == (source as NSString).range(of:"Preview 🐈") else { throw MemoizeError.message("Attachment references did not preserve exact Find.") }
        editor.textView.noteFinder.finder.performAction(.hideFindInterface)
        editor.textView.setSelectedRange(NSRange(location:0,length:0))
        editor.panel.contentView?.layoutSubtreeIfNeeded()
        let attachmentFrame = editor.attachmentStrip.buttons[fileIndex].convert(editor.attachmentStrip.buttons[fileIndex].bounds,to:editor.panel.contentView)
        let menuFrame = editor.libraryToggle.convert(editor.libraryToggle.bounds,to:editor.panel.contentView)
        let gap = attachmentFrame.minY - menuFrame.maxY
        guard (0...12).contains(gap) else { throw MemoizeError.message("Attachment/footer controls are separated by \(gap) points.") }
        try captureSmokeWindow(editor.panel,name:"attachments")
        // Removing a file is one native Undo operation, including its source and association.
        let undo = editor.textView.ownUndoManager
        undo.groupsByEvent = false
        removeAttachment(localImage); flush(); await saveQueue?.value
        guard MarkdownAnalysis.parse(editor.textView.string).images.isEmpty, current?.attachments.count == 1 else { throw MemoizeError.message("Remove Attachment left a reference or association behind.") }
        undo.undo(); flush(); await saveQueue?.value
        guard editor.textView.string == source, current?.attachments.count == 2 else { throw MemoizeError.message("Attachment removal Undo did not restore both versions.") }
        undo.redo(); flush(); await saveQueue?.value
        guard current?.attachments.count == 1 else { throw MemoizeError.message("Attachment removal Redo failed.") }
        undo.undo(); flush(); await saveQueue?.value; undo.groupsByEvent = true
        // The toast reverses this removal, even after typing and changing notes.
        removeAttachment(localImage)
        editor.textView.insertText("Later 🐈 ",replacementRange:NSRange(location:0,length:0))
        editor.textView.insertText("\nAfter removal",replacementRange:NSRange(location:editor.textView.string.utf16.count,length:0))
        let editedWithoutImage = editor.textView.string
        let restoredSource = "Later 🐈 " + source + "\nAfter removal"
        flush(); await saveQueue?.value
        let originalCreation = try requireSmoke(try await store.note(note.id)).created
        let other = try await store.saveEdit(Note(),content:"Other note stays intact",tags:[],cursor:0,selectionLength:0,scroll:0)
        cache[other.id] = other; openNote(other.id)
        editor.textView.insertText(" 🐈",replacementRange:NSRange(location:editor.textView.string.utf16.count,length:0))
        let otherSource = editor.textView.string
        guard !editor.toast.undoButton.isHidden else { throw MemoizeError.message("Attachment removal didn't offer a toast Undo.") }
        editor.toast.undoButton.performClick(nil); await noticeUndoTask?.value; await saveQueue?.value
        guard current?.id == note.id, sameSource(editor.textView.string,restoredSource), current?.attachments.count == 2,
              current?.created == originalCreation, try await store.note(other.id)?.content == otherSource else { throw MemoizeError.message("Attachment toast Undo changed state: selected=\(current?.id == note.id), source=\(sameSource(editor.textView.string,restoredSource)), attachments=\(current?.attachments.count ?? 0), date=\(current?.created == originalCreation), other=\(try await store.note(other.id)?.content == otherSource).") }
        try captureSmokeWindow(editor.panel,name:"attachment-toast-restored")
        try await Task.sleep(for:.milliseconds(50))
        for _ in 0..<4 {
            editor.textView.ownUndoManager.undo(); flush(); await saveQueue?.value
            guard editor.textView.string == editedWithoutImage, current?.attachments.count == 1,
                  editor.textView.ownUndoManager.canRedo else { throw MemoizeError.message("Native Undo of toast restoration changed state: text=\(String(reflecting:editor.textView.string)), expected=\(String(reflecting:editedWithoutImage)), attachments=\(current?.attachments.count ?? 0), undo=\(editor.textView.ownUndoManager.undoActionName), redo=\(editor.textView.ownUndoManager.redoActionName).") }
            editor.textView.ownUndoManager.redo()
            guard editor.textView.string == restoredSource else { throw MemoizeError.message("Native Redo didn't restore the source synchronously: text=\(String(reflecting:editor.textView.string)), expected=\(String(reflecting:restoredSource)), attachments=\(current?.attachments.count ?? 0), undo=\(editor.textView.ownUndoManager.undoActionName), redo=\(editor.textView.ownUndoManager.redoActionName), level=\(editor.textView.ownUndoManager.groupingLevel).") }
            flush(); await saveQueue?.value
            guard editor.textView.string == restoredSource, current?.attachments.count == 2 else { throw MemoizeError.message("Native Redo of toast restoration changed state: text=\(String(reflecting:editor.textView.string)), expected=\(String(reflecting:restoredSource)), attachments=\(current?.attachments.count ?? 0), undo=\(editor.textView.ownUndoManager.undoActionName), redo=\(editor.textView.ownUndoManager.redoActionName), level=\(editor.textView.ownUndoManager.groupingLevel).") }
        }
        try await Task.sleep(for:.milliseconds(200))
        editor.attachmentStrip.buttons.first?.performClick(nil)
        let previewDeadline = ContinuousClock.now.advanced(by:.seconds(2))
        while !editor.isShowingAttachmentPreview, ContinuousClock.now < previewDeadline { try await Task.sleep(for:.milliseconds(20)) }
        guard editor.isShowingAttachmentPreview else { throw MemoizeError.message("Native Quick Look did not open.") }
        try await smokeSettings()
        guard try await store.note(note.id)?.content == restoredSource else { throw MemoizeError.message("Attachment presentation changed persisted Markdown.") }
        newNote(notify:false); insertAttachment(png,name:"Only image.png",mime:"image/png")
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        let imageOnly = try requireSmoke(current)
        let imageFile = try requireSmoke(editor.attachmentStrip.files.first)
        removeAttachment(imageFile); await saveQueue?.value
        guard temporary, current?.id != imageOnly.id, briefNotice?.undo != nil,
              editor.toast.message.stringValue == "Attachment removed" else { throw MemoizeError.message("Removing the only attachment lost its Undo during empty-capture cleanup.") }
        let restoredAfter = Date()
        editor.toast.undoButton.performClick(nil); await noticeUndoTask?.value; await saveQueue?.value
        guard !temporary, current?.id != imageOnly.id, current?.attachments.count == 1,
              sameSource(editor.textView.string,imageOnly.content), (current?.created ?? .distantPast) >= restoredAfter else { throw MemoizeError.message("Image-only toast Undo didn't restore a fresh capture with its file and correct creation time.") }
        // A user can erase the Markdown reference before removing its file.
        // Removing only metadata must survive an earlier autosave finishing.
        let metadataNote = try await store.saveEdit(Note(),content:"Keep my text",tags:[],cursor:12,selectionLength:0,scroll:0)
        temporary = false; newNoteID = nil; install(metadataNote,show:true)
        insertAttachment(png,name:"Metadata.png",mime:"image/png")
        await finishAttachmentWrites(); flush(); await saveQueue?.value
        let metadataFile = try requireSmoke(editor.attachmentStrip.files.first)
        let metadataReference = try requireSmoke(MarkdownAnalysis.parse(editor.textView.string).images.first)
        editor.textView.insertText("",replacementRange:metadataReference.range)
        let metadataSource = editor.textView.string
        flush(); removeAttachment(metadataFile); await saveQueue?.value
        let removedMetadata = try requireSmoke(try await store.note(metadataNote.id))
        guard current?.attachments.isEmpty == true, removedMetadata.attachments.isEmpty,
              editor.textView.string == metadataSource else { throw MemoizeError.message("An earlier autosave overrode attachment removal: visible=\(current?.attachments.count ?? 0), persisted=\(removedMetadata.attachments.count).") }
        editor.toast.undoButton.performClick(nil); await noticeUndoTask?.value; await saveQueue?.value
        guard current?.attachments.count == 1, editor.textView.string == metadataSource else { throw MemoizeError.message("Metadata-only attachment Undo changed the user's source.") }
        editor.textView.ownUndoManager.undo(); flush(); await saveQueue?.value
        guard current?.attachments.isEmpty == true, editor.textView.string == metadataSource else { throw MemoizeError.message("Native metadata-only Undo changed the user's source.") }
        editor.textView.ownUndoManager.redo(); flush(); await saveQueue?.value
        guard current?.attachments.count == 1, try await store.note(metadataNote.id)?.attachments.count == 1,
              editor.textView.string == metadataSource else { throw MemoizeError.message("Native metadata-only Redo didn't restore the file association.") }
        FileHandle.standardOutput.write(Data("Attachments passed: image and ordinary file paste/drop, compact footer, native download sheet, exact exported bytes, escaped Markdown, durable offline bytes, exact copy/find, removal Undo/Redo, on-demand Quick Look.\n".utf8))
    }
    private func smokeSettings() async throws {
        let selected = UserDefaults.standard.string(forKey:"settingsPane")
        defer { UserDefaults.standard.set(selected,forKey:"settingsPane"); settings.loadedWindow?.orderOut(nil); editor.show() }
        for (index,name) in ["general","memos","shortcuts"].enumerated() {
            settings.selectPane(index)
            let window = try requireSmoke(settings.loadedWindow)
            let size = NSSize(width:540,height:[370,330,410][index])
            let resizeDeadline = ContinuousClock.now.advanced(by:.seconds(1))
            while window.contentView?.frame.size != size, ContinuousClock.now < resizeDeadline { try await Task.sleep(for:.milliseconds(20)) }
            guard window.toolbar?.selectedItemIdentifier != nil, window.toolbar?.allowsUserCustomization == false,
                  !window.styleMask.contains(.resizable), !window.styleMask.contains(.miniaturizable),
                  window.contentView?.frame.size == size,
                  window.level == editor.panel.level else { throw MemoizeError.message("Settings window did not use native preferences behavior: pane=\(name), size=\(String(describing:window.contentView?.frame.size)), toolbar=\(String(describing:window.toolbar?.selectedItemIdentifier)), level=\(window.level.rawValue), noteLevel=\(editor.panel.level.rawValue).") }
            try captureSmokeWindow(window,name:"settings-" + name)
        }
        let restored = SettingsWindow(); restored.show()
        guard (restored.loadedWindow?.contentViewController as? NSTabViewController)?.selectedTabViewItemIndex == 2 else { throw MemoizeError.message("Settings did not restore the last pane.") }
        restored.loadedWindow?.orderOut(nil)
        try await smokeAppearance()
        FileHandle.standardOutput.write(Data("Settings passed: native toolbar panes, compact preferences window, pin ordering, remembered pane.\n".utf8))
    }
    private func smokeAppearance() async throws {
        let saved = UserDefaults.standard.string(forKey:"appearance")
        let preference = settings.model.appearance, appearance = NSApp.appearance
        let source = editor.textView.string, selection = editor.textView.selectedRange()
        let undo = editor.textView.ownUndoManager
        let canUndo = undo.canUndo, canRedo = undo.canRedo
        defer {
            settings.model.appearance = preference
            UserDefaults.standard.set(saved,forKey:"appearance")
            NSApp.appearance = appearance
        }
        UserDefaults.standard.removeObject(forKey:"appearance")
        guard SettingsModel().appearance == .auto else { throw MemoizeError.message("The default theme wasn't Auto.") }
        UserDefaults.standard.set("unrecognized",forKey:"appearance")
        guard SettingsModel().appearance == .auto else { throw MemoizeError.message("An invalid saved theme didn't fall back to Auto.") }
        settings.selectPane(0)
        for (theme,name) in [(AppAppearance.light,NSAppearance.Name.aqua),(.dark,.darkAqua)] {
            settings.model.appearance = theme
            try await Task.sleep(for:.milliseconds(150))
            let reopened = SettingsWindow(); reopened.show()
            defer { reopened.loadedWindow?.orderOut(nil) }
            let windows = [editor.panel,library.window,try requireSmoke(settings.loadedWindow),try requireSmoke(reopened.loadedWindow)]
            guard AppAppearance.saved == theme, reopened.model.appearance == theme,
                  windows.allSatisfy({ $0.effectiveAppearance.bestMatch(from:[.aqua,.darkAqua]) == name }),
                  editor.textView.effectiveAppearance.bestMatch(from:[.aqua,.darkAqua]) == name else { throw MemoizeError.message("The saved theme didn't reach existing and newly opened windows.") }
            var background: NSColor?, foreground: NSColor?
            editor.textView.effectiveAppearance.performAsCurrentDrawingAppearance {
                background = editor.textView.backgroundColor.usingColorSpace(.sRGB)
                foreground = editor.textView.textColor?.usingColorSpace(.sRGB)
            }
            guard let background, let foreground,
                  theme == .dark ? background.redComponent < 0.25 && foreground.redComponent > 0.8 : background.redComponent > 0.85 && foreground.redComponent < 0.2 else { throw MemoizeError.message("The editor's text and background didn't adapt to its theme.") }
            try captureSmokeWindow(editor.panel,name:"appearance-" + theme.rawValue + "-editor")
            try captureSmokeWindow(library.window,name:"appearance-" + theme.rawValue + "-library")
            try captureSmokeWindow(reopened.loadedWindow!,name:"appearance-" + theme.rawValue + "-settings")
        }
        settings.model.appearance = .auto
        try await Task.sleep(for:.milliseconds(100))
        guard NSApp.appearance == nil, AppAppearance.saved == .auto,
              editor.panel.effectiveAppearance.bestMatch(from:[.aqua,.darkAqua]) == NSApp.effectiveAppearance.bestMatch(from:[.aqua,.darkAqua]),
              editor.textView.string == source, editor.textView.selectedRange() == selection,
              editor.textView.ownUndoManager === undo, undo.canUndo == canUndo, undo.canRedo == canRedo else { throw MemoizeError.message("Auto didn't follow macOS, or switching themes changed editor state.") }
        FileHandle.standardOutput.write(Data("Appearance passed: Auto default and fallback, live Light/Dark across existing and reopened windows, readable editor colors, saved preference, system inheritance, unchanged text/cursor/Undo.\n".utf8))
    }
    private func smokeAttachmentUndo() async throws {
        guard let store else { throw MemoizeError.message("Missing attachment test store.") }
        let png = Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
        for (filename,mime,bytes) in [("Undo.png","image/png",png),("Undo.txt","text/plain",Data("File Undo".utf8))] {
            let existing = try await store.saveEdit(Note(),content:"An existing note",tags:[],cursor:16,selectionLength:0,scroll:0)
            temporary = false; newNoteID = nil; install(existing,show:true)
            for fresh in [false,true] {
                if fresh { newNote() }
                let before = editor.textView.string
                insertAttachment(bytes,name:filename,mime:mime)
                await finishAttachmentWrites(); flush(); await saveQueue?.value
                try await Task.sleep(for:.milliseconds(100))
                let pasted = try requireSmoke(current)
                editor.textView.ownUndoManager.undo(); flush(); await saveQueue?.value
                guard editor.textView.string == before else { throw MemoizeError.message("Attachment Undo did not restore the previous source: fresh=\(fresh), before=\(String(reflecting:before)), after=\(String(reflecting:editor.textView.string)), group=\(editor.textView.ownUndoManager.groupingLevel).") }
                if !fresh {
                    guard try await store.note(pasted.id)?.attachments.isEmpty == true,
                          try await store.pendingAttachments(noteID:pasted.id).isEmpty else { throw MemoizeError.message("Attachment Undo retained an attachment or upload.") }
                }
                try await Task.sleep(for:.milliseconds(100))
                editor.textView.ownUndoManager.redo(); flush(); await saveQueue?.value
                let restored = try requireSmoke(current)
                guard editor.textView.string == pasted.content, restored.attachments == pasted.attachments else { throw MemoizeError.message("Attachment Redo did not restore source and attachment together: fresh=\(fresh), source=\(String(reflecting:editor.textView.string)), attachments=\(restored.attachments.count)/\(pasted.attachments.count), historyAttachments=\(draftAttachments.count).") }
                if fresh {
                    guard restored.id != pasted.id, try await store.needsAttachmentCopy(restored) else { throw MemoizeError.message("Restored file capture did not receive independent ownership.") }
                }
            }
        }
        FileHandle.standardOutput.write(Data("Attachment Undo/Redo passed: images and ordinary files, existing note, new capture, source, attachment association, pending uploads, copied ownership.\n".utf8))
    }
    private func smokeUndoShortcuts() async throws {
        func shortcut(redo: Bool = false) throws {
            let event = try requireSmoke(NSEvent.keyEvent(with:.keyDown,location:.zero,modifierFlags:redo ? [.command,.shift] : .command,timestamp:0,windowNumber:editor.panel.windowNumber,context:nil,characters:redo ? "Z" : "z",charactersIgnoringModifiers:redo ? "Z" : "z",isARepeat:false,keyCode:6))
            guard NSApp.mainMenu?.performKeyEquivalent(with:event) == true else { throw MemoizeError.message("Undo/Redo keyboard shortcut was not handled.") }
        }
        newNote()
        editor.textView.insertText("First memo",replacementRange:editor.textView.selectedRange())
        try await Task.sleep(for:.milliseconds(200)); flush(); await saveQueue?.value
        let first = try requireSmoke(current?.id)
        newNote()
        editor.textView.insertText("Second memo",replacementRange:editor.textView.selectedRange())
        try await Task.sleep(for:.milliseconds(200)); flush(); await saveQueue?.value
        let second = try requireSmoke(current?.id)
        openNote(first)
        try shortcut()
        guard editor.textView.string.isEmpty else { throw MemoizeError.message("Cmd+Z after navigation did not undo the first note: \(String(reflecting:editor.textView.string)).") }
        try await Task.sleep(for:.milliseconds(75))
        try shortcut(redo:true)
        guard editor.textView.string == "First memo" else { throw MemoizeError.message("Shift+Cmd+Z did not restore the first note.") }
        openNote(second)
        try shortcut()
        guard editor.textView.string.isEmpty else { throw MemoizeError.message("Undo leaked across notes.") }
        try await Task.sleep(for:.milliseconds(75))
        try shortcut(redo:true)
        guard editor.textView.string == "Second memo" else { throw MemoizeError.message("Redo leaked across notes.") }
        flush(); await saveQueue?.value
        guard try await store?.note(first)?.content == "First memo",
              try await store?.note(second)?.content == "Second memo" else { throw MemoizeError.message("Shortcut Undo/Redo did not persist exactly.") }
        FileHandle.standardOutput.write(Data("Undo shortcuts passed: Cmd+Z and Shift+Cmd+Z, separate note histories after navigation, exact autosave.\n".utf8))
    }
    private func smokeListEditing(throughKeyboard: Bool = true) async throws {
        newNote()
        let view = editor.textView
        let undo = view.ownUndoManager
        // Direct command checks have no NSEvent boundaries. Supply our own
        // groups without closing or altering AppKit's automatic event groups.
        if !throughKeyboard { undo.groupsByEvent = false }
        defer { if !throughKeyboard { undo.groupsByEvent = true } }
        func edit(_ body: () -> Void) {
            if !throughKeyboard { undo.beginUndoGrouping() }
            body()
            if !throughKeyboard { undo.endUndoGrouping() }
        }
        func command(_ characters: String, code: UInt16, modifiers: NSEvent.ModifierFlags = []) async throws {
            if throughKeyboard { try await smokeKey(characters,code:code,modifiers:modifiers); return }
            switch characters {
            case "\r": edit { view.insertNewline(nil) }
            case "\t": edit { view.insertTab(nil) }
            case "\u{19}": edit { view.insertBacktab(nil) }
            case "z": view.ownUndoManager.undo()
            case "Z": view.ownUndoManager.redo()
            default: throw MemoizeError.message("Unknown list test command.")
            }
            try await Task.sleep(for:.milliseconds(75))
        }
        edit { view.insertText("- [x] First task",replacementRange:view.selectedRange()) }
        try await command("\r",code:36)
        guard view.string == "- [x] First task\n- [ ] " else { throw MemoizeError.message("Return did not continue a checklist.") }
        try await command("\t",code:48)
        guard view.string == "- [x] First task\n  - [ ] " else { throw MemoizeError.message("Tab did not create a sublist.") }
        try await command("z",code:6,modifiers:.command)
        guard view.string == "- [x] First task\n- [ ] " else { throw MemoizeError.message("List indent was not one undoable edit: \(String(reflecting:view.string)).") }
        try await command("Z",code:6,modifiers:[.command,.shift])
        guard view.string == "- [x] First task\n  - [ ] " else { throw MemoizeError.message("Redo did not restore the sublist.") }
        try await command("\u{19}",code:48,modifiers:.shift)
        guard view.string == "- [x] First task\n- [ ] " else { throw MemoizeError.message("Shift-Tab did not outdent the task: \(String(reflecting:view.string)), \(view.selectedRange()).") }
        try await command("\r",code:36)
        guard view.string == "- [x] First task\n" else { throw MemoizeError.message("An empty task did not exit the list: \(String(reflecting:view.string)), \(view.selectedRange()).") }
        flush(); await saveQueue?.value
        guard try await store?.note(try requireSmoke(current?.id))?.content == view.string else { throw MemoizeError.message("List edits did not autosave exactly.") }
        FileHandle.standardOutput.write(Data("Native list editing passed: Return, unchecked continuation, Tab/Shift-Tab, empty-item exit, Undo/Redo, autosave.\n".utf8))
    }
    private func smokeKey(_ characters: String, code: UInt16, modifiers: NSEvent.ModifierFlags = []) async throws {
        let event = try requireSmoke(NSEvent.keyEvent(with:.keyDown,location:.zero,modifierFlags:modifiers,timestamp:ProcessInfo.processInfo.systemUptime,windowNumber:editor.panel.windowNumber,context:nil,characters:characters,charactersIgnoringModifiers:characters,isARepeat:false,keyCode:code))
        NSApp.postEvent(event,atStart:false)
        try await Task.sleep(for:.milliseconds(75))
    }
    /// Run a test action at an AppKit event boundary, without changing the user's
    /// clipboard or manually closing native Undo groups.
    private func smokeEdit(_ edit: @escaping @MainActor () -> Void) async throws {
        let target = SmokeEditAction(edit)
        let menu = try requireSmoke(NSApp.mainMenu?.item(withTitle:"Edit")?.submenu)
        let item = NSMenuItem(title:"Test action",action:#selector(SmokeEditAction.insertFixture(_:)),keyEquivalent:"\u{F717}")
        item.keyEquivalentModifierMask = [.command,.option]; item.target = target
        menu.addItem(item)
        defer { menu.removeItem(item) }
        try await smokeKey("\u{F717}",code:90,modifiers:[.command,.option])
        withExtendedLifetime(target) { }
    }
    private func captureSmokeWindow(_ window: NSWindow, name: String) throws {
        if ProcessInfo.processInfo.environment["MEMOIZE_SMOKE_WINDOW_CAPTURE"] == "1" {
            let capture = Process(); capture.executableURL = URL(fileURLWithPath:"/usr/sbin/screencapture")
            capture.arguments = ["-x","-l",String(window.windowNumber),support.appending(path:"\(name)-window.png").path]
            try capture.run(); capture.waitUntilExit()
        }
        guard let view = window.contentView, let bitmap = view.bitmapImageRepForCachingDisplay(in:view.bounds) else { return }
        view.effectiveAppearance.performAsCurrentDrawingAppearance { view.cacheDisplay(in:view.bounds,to:bitmap) }
        try bitmap.representation(using:.png,properties:[:])?.write(to:support.appending(path:"\(name).png"))
    }
    /// Check the composited view, not only NSTextView.string: sibling drawing can obscure text.
    private func smokeVisibleEditorText() throws {
        guard let view = editor.panel.contentView,
              let bitmap = view.bitmapImageRepForCachingDisplay(in:view.bounds) else { throw MemoizeError.message("Could not capture the editor for its rendering check.") }
        view.cacheDisplay(in:view.bounds,to:bitmap)
        try bitmap.representation(using:.png,properties:[:])?.write(to:support.appending(path:"visible-text.png"))
        guard let background = bitmap.colorAt(x:bitmap.pixelsWide / 2,y:bitmap.pixelsHigh / 2)?.usingColorSpace(.deviceRGB) else { throw MemoizeError.message("Missing editor background sample.") }
        let scale = Double(bitmap.pixelsHigh) / view.bounds.height
        let inset = Int(44 * scale)
        var contrastingPixels = 0
        for y in inset..<(bitmap.pixelsHigh - inset) {
            for x in inset..<(bitmap.pixelsWide - inset) {
                guard let color = bitmap.colorAt(x:x,y:y)?.usingColorSpace(.deviceRGB) else { continue }
                if max(abs(color.redComponent - background.redComponent),abs(color.greenComponent - background.greenComponent),abs(color.blueComponent - background.blueComponent)) > 0.2 {
                    contrastingPixels += 1
                    if contrastingPixels >= 120 { return }
                }
            }
        }
        throw MemoizeError.message("The note has content but its writing area rendered blank.")
    }
}

@MainActor private final class SmokeEditAction: NSObject {
    private let action: @MainActor () -> Void
    init(_ action: @escaping @MainActor () -> Void) { self.action = action }
    @objc func insertFixture(_ sender: Any?) { action() }
}

private func requireSmoke<T>(_ value: T?, file: StaticString = #fileID, line: UInt = #line) throws -> T {
    guard let value else { throw MemoizeError.message("Missing smoke-test value at \(file):\(line).") }; return value
}
