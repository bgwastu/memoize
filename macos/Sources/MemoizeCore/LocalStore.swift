import Foundation
import GRDB
import CryptoKit

/// A single queue keeps transactions short and makes ordering explicit.
public final class LocalStore: Sendable {
    private let database: DatabaseQueue
    public let directory: URL
    public let attachmentDirectory: URL

    public init(directory: URL) throws {
        self.directory = directory
        // Reuse the original cache folder so existing offline files remain available.
        attachmentDirectory = directory.appending(path: "Images", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: attachmentDirectory, withIntermediateDirectories: true)
        var configuration = Configuration()
        configuration.prepareDatabase { db in
            try db.execute(sql: "PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL;")
        }
        let path = directory.appending(path:"memoize.sqlite").path
        let existing = FileManager.default.fileExists(atPath:path)
        database = try DatabaseQueue(path:path, configuration:configuration)
        var migrator = DatabaseMigrator()
        migrator.registerMigration("initial") { db in
            try db.execute(sql: """
                CREATE TABLE notes (
                    id TEXT PRIMARY KEY NOT NULL, remoteID TEXT UNIQUE NOT NULL,
                    content TEXT NOT NULL, created REAL NOT NULL, modified REAL NOT NULL,
                    state TEXT NOT NULL, visibility TEXT NOT NULL, tags TEXT NOT NULL,
                    attachments TEXT NOT NULL, base TEXT, sent TEXT, sentRevision INTEGER,
                    revision INTEGER NOT NULL, syncedRevision INTEGER NOT NULL,
                    deleteAfter REAL, deleted BOOLEAN NOT NULL, conflict BOOLEAN NOT NULL,
                    error TEXT, cursor INTEGER NOT NULL, selectionLength INTEGER NOT NULL, scroll DOUBLE NOT NULL
                );
                CREATE INDEX note_order ON notes(deleted, created DESC, id);
                CREATE TABLE tags (noteID TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE, tag TEXT NOT NULL, PRIMARY KEY(noteID, tag));
                CREATE INDEX tag_lookup ON tags(tag, noteID);
                CREATE VIRTUAL TABLE search USING fts5(content, content='notes', content_rowid='rowid', tokenize='trigram');
                CREATE TRIGGER note_insert AFTER INSERT ON notes BEGIN INSERT INTO search(rowid, content) VALUES(new.rowid, new.content); END;
                CREATE TRIGGER note_delete AFTER DELETE ON notes BEGIN INSERT INTO search(search,rowid,content) VALUES('delete',old.rowid,old.content); END;
                CREATE TRIGGER note_update AFTER UPDATE OF content ON notes WHEN old.content != new.content BEGIN
                    INSERT INTO search(search,rowid,content) VALUES('delete',old.rowid,old.content);
                    INSERT INTO search(rowid,content) VALUES(new.rowid,new.content);
                END;
                CREATE TABLE attachments (id TEXT PRIMARY KEY NOT NULL, noteID TEXT NOT NULL, filename TEXT NOT NULL, mime TEXT NOT NULL, uploaded BOOLEAN NOT NULL);
                CREATE TABLE recovery (id INTEGER PRIMARY KEY, noteID TEXT NOT NULL, saved REAL NOT NULL, reason TEXT NOT NULL, snapshot BLOB NOT NULL);
                CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                """)
        }
        migrator.registerMigration("attachment-deletions") { db in
            try db.execute(sql:"CREATE TABLE attachmentDeletions (id TEXT PRIMARY KEY NOT NULL, noteID TEXT NOT NULL)")
        }
        if existing, try database.read({ try !migrator.hasCompletedMigrations($0) }) {
            let backup = try DatabaseQueue(path:directory.appending(path:"before-migration-\(Int(Date().timeIntervalSince1970)).sqlite").path)
            try database.backup(to:backup)
        }
        try migrator.migrate(database)
    }

    private static func put(_ note: Note, in db: Database) throws {
        try note.save(db)
        try db.execute(sql: "DELETE FROM tags WHERE noteID = ?", arguments: [note.id])
        for tag in uniqueTags(note.tags) { try db.execute(sql: "INSERT INTO tags VALUES (?,?)", arguments: [note.id, tag]) }
    }
    private static func recover(_ note: Note, reason: String, in db: Database) throws {
        try db.execute(sql: "INSERT INTO recovery(noteID,saved,reason,snapshot) VALUES (?,?,?,?)",
                       arguments: [note.id, Date(), reason, try JSONEncoder().encode(note)])
    }
    public func note(_ id: String) async throws -> Note? {
        try await database.read { try Note.fetchOne($0, key: id) }
    }
    public func allNotes(includeDeleted: Bool = false) async throws -> [Note] {
        try await database.read { db in
            try Note.fetchAll(db, sql: "SELECT * FROM notes \(includeDeleted ? "" : "WHERE deleted = 0 AND deleteAfter IS NULL") ORDER BY created DESC, id DESC")
        }
    }
    public func search(_ query: String, tag: String? = nil) async throws -> [Note] {
        try await database.read { db in
            var sql = "SELECT n.* FROM notes n WHERE n.deleted = 0 AND n.deleteAfter IS NULL"
            var args: [DatabaseValue] = []
            if !query.isEmpty {
                if query.unicodeScalars.count >= 3 {
                    sql += " AND n.rowid IN (SELECT rowid FROM search WHERE search MATCH ?)"
                    args.append(("\"" + query.replacingOccurrences(of: "\"", with: "\"\"") + "\"").databaseValue)
                } else {
                    sql += " AND instr(lower(n.content),lower(?)) > 0"
                    args.append(query.databaseValue)
                }
            }
            if let tag {
                sql += " AND EXISTS (SELECT 1 FROM tags t WHERE t.noteID = n.id AND (t.tag = ? OR substr(t.tag,1,?) = ?))"
                args += [tag.databaseValue, (tag.unicodeScalars.count + 1).databaseValue, (tag + "/").databaseValue]
            }
            sql += " ORDER BY n.created DESC, n.id DESC"
            return try Note.fetchAll(db, sql: sql, arguments: StatementArguments(args))
        }
    }
    public func tags() async throws -> [String] {
        try await database.read { try String.fetchAll($0, sql: "SELECT DISTINCT tag FROM tags JOIN notes ON notes.id=tags.noteID WHERE deleted=0 AND deleteAfter IS NULL ORDER BY tag COLLATE NOCASE") }
    }
    public func summaries(query: String = "", tag: String? = nil) async throws -> [NoteSummary] {
        try await database.read { db in
            let indexed = query.unicodeScalars.count >= 3
            let excerpt = query.isEmpty ? "NULL" : indexed ? "snippet(search,0,'','','…',64)" : "substr(n.content,max(1,instr(lower(n.content),lower(?))-28),180)"
            let join = indexed ? " JOIN search ON search.rowid=n.rowid" : ""
            var sql = "SELECT n.id,substr(n.content,1,200) AS preview,\(excerpt) AS excerpt,n.created,n.state,n.conflict,(n.base IS NULL OR n.revision>n.syncedRevision) AS pending,n.error FROM notes n\(join) WHERE n.deleted=0 AND n.deleteAfter IS NULL"
            var args: [DatabaseValue] = !query.isEmpty && !indexed ? [query.databaseValue] : []
            if !query.isEmpty {
                if indexed {
                    sql += " AND search MATCH ?"
                    args.append(("\"" + query.replacingOccurrences(of:"\"",with:"\"\"") + "\"").databaseValue)
                } else { sql += " AND instr(lower(n.content),lower(?))>0"; args.append(query.databaseValue) }
            }
            if let tag {
                sql += " AND EXISTS (SELECT 1 FROM tags t WHERE t.noteID=n.id AND (t.tag=? OR substr(t.tag,1,?)=?))"
                args += [tag.databaseValue,(tag.unicodeScalars.count + 1).databaseValue,(tag + "/").databaseValue]
            }
            sql += " ORDER BY n.created DESC,n.id DESC"
            return try NoteSummary.fetchAll(db,sql:sql,arguments:StatementArguments(args))
        }
    }
    /// Edits patch current state, so an in-flight sync cannot reset a newer revision.
    @discardableResult public func saveEdit(_ draft: Note, content: String, tags: [String], cursor: Int, selectionLength: Int, scroll: Double) async throws -> Note {
        try await database.write { db in
            var current = try Note.fetchOne(db, key: draft.id) ?? draft
            // A pull may land during the short autosave debounce. Retain the editor's old base
            // so that this race becomes a visible conflict instead of a last-writer-wins edit.
            if let oldBase = draft.base, let newBase = current.base,
               !oldBase.sameBody(as:newBase), current.syncedRevision <= draft.syncedRevision,
               !sameSource(content,current.content) {
                try Self.recover(current,reason:"conflict during autosave",in:db)
                var original = Note(remote:newBase)
                original.id = UUID().uuidString.lowercased()
                current.remoteID = UUID().uuidString.lowercased(); current.base = nil
                current.sent = nil; current.sentRevision = nil; current.syncedRevision = 0
                current.visibility = "PRIVATE"; current.conflict = true; current.attachments = draft.attachments
                try Self.put(current,in:db); try Self.put(original,in:db)
            }
            if !sameSource(current.content,content) || current.base == nil {
                current.content = content; current.tags = tags; current.modified = Date(); current.revision += 1
                current.error = current.conflict && current.base == nil && !current.attachments.isEmpty ? "Conflict attachments need copying before upload." : nil
            }
            current.cursor = cursor; current.selectionLength = selectionLength; current.scroll = scroll
            try Self.put(current, in: db)
            return current
        }
    }
    public func savePosition(id: String, cursor: Int, length: Int, scroll: Double) async throws {
        try await database.write { try $0.execute(sql: "UPDATE notes SET cursor=?,selectionLength=?,scroll=? WHERE id=?", arguments: [cursor,length,scroll,id]) }
    }
    public func markDelete(_ id: String, now: Date = Date(), undoInterval: TimeInterval = 5) async throws {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, key: id) else { return }
            try Self.recover(note, reason: "delete", in: db)
            note.deleteAfter = now.addingTimeInterval(max(0,undoInterval)); try Self.put(note, in: db)
        }
    }
    @discardableResult public func undoDelete(_ id: String, now: Date = Date()) async throws -> Bool {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, key: id), let deadline = note.deleteAfter, deadline > now else { return false }
            note.deleteAfter = nil; try Self.put(note, in: db); return true
        }
    }
    public func pending(now: Date = Date()) async throws -> [Note] {
        try await database.read { db in
            try Note.fetchAll(db, sql: "SELECT * FROM notes WHERE deleted=0 AND (deleteAfter <= ? OR (deleteAfter IS NULL AND (base IS NULL OR revision > syncedRevision))) ORDER BY created", arguments: [now])
        }
    }
    public func nextDeletionDeadline() async throws -> Date? {
        try await database.read { db in
            try Date.fetchOne(db,sql:"SELECT MIN(deleteAfter) FROM notes WHERE deleted=0 AND deleteAfter IS NOT NULL AND (error IS NULL OR error NOT LIKE 'Sync paused:%')")
        }
    }
    public func hasPendingSync() async throws -> Bool {
        try await database.read { db in
            try Bool.fetchOne(db,sql:"SELECT EXISTS(SELECT 1 FROM notes WHERE deleted=0 AND (deleteAfter IS NOT NULL OR base IS NULL OR revision > syncedRevision)) OR EXISTS(SELECT 1 FROM attachmentDeletions)") ?? false
        }
    }
    @discardableResult public func beginSend(_ snapshot: Note) async throws -> Bool {
        try await database.write { db in
            // Recheck inside the transaction: typing or discarding may race with network preparation.
            guard let current = try Note.fetchOne(db,key:snapshot.id), !current.deleted,
                  current.deleteAfter == nil, current.revision == snapshot.revision,
                  current.remoteID == snapshot.remoteID else { return false }
            try Self.recover(snapshot, reason: "before upload", in: db)
            // Keep one ordinary upload recovery record; unresolved conflicts and deletions are never pruned.
            try db.execute(sql: "DELETE FROM recovery WHERE noteID=? AND reason='before upload' AND id NOT IN (SELECT max(id) FROM recovery WHERE noteID=? AND reason='before upload')", arguments: [snapshot.id, snapshot.id])
            try db.execute(sql: "UPDATE notes SET sent=?,sentRevision=? WHERE id=?", arguments: [try JSONEncoder().encode(snapshot.remote),snapshot.revision,snapshot.id])
            return true
        }
    }
    public func acknowledge(id: String, remote: RemoteMemo, revision: Int) async throws {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, key: id) else { return }
            note.base = remote; note.sent = nil; note.sentRevision = nil; note.syncedRevision = revision; note.error = nil
            if note.revision == revision {
                note.content = remote.content; note.tags = remote.tags; note.attachments = remote.attachments
            }
            note.state = remote.state; note.visibility = remote.visibility
            try Self.put(note, in: db)
        }
    }
    /// Returns the local ID of a preserved conflict, if there was one. The editor stays on that ID.
    @discardableResult public func reconcile(_ remote: RemoteMemo) async throws -> String? {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, sql: "SELECT * FROM notes WHERE remoteID=?", arguments: [String(remote.name.dropFirst(6))]) else {
                try Self.put(Note(remote: remote), in: db); return nil
            }
            if let sent = note.sent, sent.sameBody(as: remote), let revision = note.sentRevision {
                note.base = remote; note.syncedRevision = revision; note.sent = nil; note.sentRevision = nil
                note.error = nil
                if note.revision == revision { note.tags = remote.tags; note.attachments = remote.attachments }
                try Self.put(note,in:db)
            }
            if note.deleted { return nil }
            if note.dirty {
                if note.remote.sameBody(as: remote) {
                    note.base = remote; note.syncedRevision = note.revision
                    note.state = remote.state; note.visibility = remote.visibility; note.tags = remote.tags
                    try Self.put(note, in: db); return nil
                }
                if let base = note.base, base.sameBody(as: remote) {
                    note.base = remote; note.state = remote.state; note.visibility = remote.visibility
                    try Self.put(note, in: db); return nil
                }
                try Self.recover(note, reason: "conflict", in: db)
                // Cancel a delete if the other client changed the note.
                if note.deleteAfter != nil {
                    note.deleteAfter = nil; note.error = "Deletion cancelled: this note changed on the server."
                    note.base = remote; note.content = remote.content; note.tags = remote.tags
                    note.attachments = remote.attachments; note.syncedRevision = note.revision
                    try Self.put(note, in: db); return note.id
                }
                note.remoteID = UUID().uuidString.lowercased(); note.base = nil; note.sent = nil; note.sentRevision = nil
                note.visibility = "PRIVATE"; note.state = "NORMAL"; note.conflict = true; note.syncedRevision = 0
                // Attachments must be cloned before uploading a fork; never transfer ownership away from the original.
                note.error = note.attachments.isEmpty ? nil : "Conflict attachments need copying before upload."
                try Self.put(note, in: db)
                var original = Note(remote: remote)
                if original.id == note.id { original.id = UUID().uuidString.lowercased() }
                try Self.put(original, in: db)
                return note.id
            }
            if note.base != remote || !note.remote.sameBody(as:remote) {
                if !note.remote.sameBody(as: remote) { try Self.recover(note, reason: "remote replacement", in: db) }
                note.content = remote.content; note.modified = remote.updateTime; note.base = remote
                note.tags = remote.tags; note.attachments = remote.attachments; note.state = remote.state; note.visibility = remote.visibility
                try Self.put(note, in: db)
            }
            return nil
        }
    }
    public func remoteMissing(_ id: String) async throws {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, key: id), note.base != nil else { return }
            try Self.recover(note, reason: "remote deletion", in: db)
            if note.revision > note.syncedRevision && note.deleteAfter == nil {
                note.remoteID = UUID().uuidString.lowercased(); note.base = nil; note.sent = nil; note.sentRevision = nil
                note.conflict = true; note.visibility = "PRIVATE"; note.syncedRevision = 0
                if !note.attachments.isEmpty { note.error = "Conflict attachments need copying before upload." }
            } else { note.deleted = true; note.deleteAfter = nil }
            try Self.put(note, in: db)
        }
    }
    public func finishDelete(_ id: String) async throws {
        try await database.write { try $0.execute(sql: "UPDATE notes SET deleted=1,deleteAfter=NULL WHERE id=?", arguments: [id]) }
    }
    public func setError(_ id: String, message: String?) async throws {
        try await database.write { try $0.execute(sql: "UPDATE notes SET error=? WHERE id=?", arguments: [message,id]) }
    }
    public func retryFailures() async throws {
        try await database.write { try $0.execute(sql:"UPDATE notes SET error=NULL WHERE error LIKE 'Sync paused:%'") }
    }
    public func metadata(_ key: String) async throws -> String? {
        try await database.read { try String.fetchOne($0, sql: "SELECT value FROM metadata WHERE key=?", arguments: [key]) }
    }
    public func setMetadata(_ key: String, value: String) async throws {
        try await database.write { try $0.execute(sql: "INSERT INTO metadata VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", arguments: [key,value]) }
    }
    public func addAttachment(_ attachment: Attachment, to draft: Note) async throws {
        try await database.write { db in
            var note = try Note.fetchOne(db, key: draft.id) ?? draft
            note.attachments.append(attachment.remote); note.revision += 1
            try attachment.save(db); try Self.put(note, in: db)
        }
    }
    public func pendingAttachments(noteID: String) async throws -> [Attachment] {
        try await database.read { db in
            let active = Set((try Note.fetchOne(db,key:noteID))?.attachments.map(\.name) ?? [])
            return try Attachment.fetchAll(db,sql:"SELECT * FROM attachments WHERE noteID=? AND uploaded=0",arguments:[noteID]).filter { active.contains("attachments/" + $0.id) }
        }
    }
    public func setAttachmentAssociation(_ image: Attachment, associated: Bool) async throws {
        try await database.write { db in
            guard var note = try Note.fetchOne(db,key:image.noteID), !note.deleted, note.deleteAfter == nil else { return }
            if try Attachment.fetchOne(db,key:image.id) == nil { try image.save(db) }
            // Memos deletes detached files. Undo must check/re-upload their bytes,
            // including when it races an in-flight removal request.
            try db.execute(sql:"UPDATE attachments SET uploaded=0 WHERE id=?",arguments:[image.id])
            if associated {
                try db.execute(sql:"DELETE FROM attachmentDeletions WHERE id=?",arguments:[image.id])
            } else {
                try db.execute(sql:"INSERT INTO attachmentDeletions VALUES (?,?) ON CONFLICT(id) DO UPDATE SET noteID=excluded.noteID",arguments:[image.id,image.noteID])
            }
            let before = note.attachments
            note.attachments.removeAll { $0.name == image.remote.name }
            if associated { note.attachments.append(image.remote) }
            if before != note.attachments {
                note.revision += 1; note.modified = Date()
                try Self.put(note,in:db)
            }
        }
    }
    public func pendingAttachmentDeletions() async throws -> [Attachment] {
        try await database.read { db in
            try Attachment.fetchAll(db,sql:"SELECT a.id,d.noteID,a.filename,a.mime,a.uploaded FROM attachments a JOIN attachmentDeletions d ON a.id=d.id")
        }
    }
    /// Wait for detachment to sync. Do not issue an additional DELETE while a
    /// local note, conflict, or ambiguous write still references the file.
    public func canDeleteAttachment(_ id: String) async throws -> Bool {
        try await database.read { db in
            guard try Bool.fetchOne(db,sql:"SELECT EXISTS(SELECT 1 FROM attachmentDeletions WHERE id=?)",arguments:[id]) == true else { return false }
            return try !Self.attachmentIsReferenced(id,in:db)
        }
    }
    private static func attachmentIsReferenced(_ id: String, in db: Database) throws -> Bool {
        let name = "attachments/" + id
        func references(_ memo: RemoteMemo) -> Bool {
            memo.attachments.contains { $0.name == name } || memo.content.contains("/file/" + name)
        }
        for note in try Note.fetchAll(db) where !note.deleted {
            if references(note.remote) || note.base.map(references) == true || note.sent.map(references) == true { return true }
        }
        return false
    }
    public func finishAttachmentDeletion(_ id: String) async throws {
        try await database.write { db in
            // Reset this even if Undo cancelled the queue during the network request.
            try db.execute(sql:"UPDATE attachments SET uploaded=0 WHERE id=?",arguments:[id])
            guard try Bool.fetchOne(db,sql:"SELECT EXISTS(SELECT 1 FROM attachmentDeletions WHERE id=?)",arguments:[id]) == true,
                  try !Self.attachmentIsReferenced(id,in:db) else { return }
            let active = self.attachmentURL(id)
            let recovery = self.removedAttachmentURL(id)
            if FileManager.default.fileExists(atPath:active.path) {
                try FileManager.default.createDirectory(at:recovery.deletingLastPathComponent(),withIntermediateDirectories:true)
                if !FileManager.default.fileExists(atPath:recovery.path) { try FileManager.default.moveItem(at:active,to:recovery) }
                else { try FileManager.default.removeItem(at:active) }
            }
            let key = SHA256.hash(data:Data(("attachments/" + id).utf8)).map { String(format:"%02x",$0) }.joined()
            let preview = self.directory.appending(path:"Previews/" + key,directoryHint:.isDirectory)
            if FileManager.default.fileExists(atPath:preview.path) { try FileManager.default.removeItem(at:preview) }
            try db.execute(sql:"DELETE FROM attachmentDeletions WHERE id=?",arguments:[id])
        }
    }
    /// New identities (conflict copies or restored captures) must own their files.
    /// Derive this from durable ownership, so a failed copy remains retryable.
    public func needsAttachmentCopy(_ note: Note) async throws -> Bool {
        guard note.base == nil, !note.attachments.isEmpty else { return false }
        return try await database.read { db in
            for image in note.attachments {
                let id = String(image.name.dropFirst("attachments/".count))
                if try Attachment.fetchOne(db,key:id)?.noteID != note.id { return true }
            }
            return false
        }
    }
    public func uploaded(_ id: String) async throws {
        try await database.write { try $0.execute(sql: "UPDATE attachments SET uploaded=1 WHERE id=?", arguments: [id]) }
    }
    public func cloneAttachments(noteID: String, content: String, attachments: [RemoteAttachment], local: [Attachment]) async throws {
        try await database.write { db in
            guard var note = try Note.fetchOne(db, key: noteID) else { return }
            // Fork preparation can race with typing; replace only the stable reference IDs.
            for (old, new) in zip(note.attachments, attachments) {
                note.content = note.content.replacingOccurrences(of: "/file/\(old.name)", with: "/file/\(new.name)")
            }
            note.attachments = attachments; note.revision += 1; note.error = nil
            for item in local { try item.save(db) }; try Self.put(note, in: db)
        }
    }
    public func attachmentURL(_ id: String) -> URL {
        let safe = !id.isEmpty && id.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 }
        let name = safe ? id : SHA256.hash(data:Data(id.utf8)).map { String(format:"%02x",$0) }.joined()
        return attachmentDirectory.appending(path:name)
    }
    private func removedAttachmentURL(_ id: String) -> URL {
        directory.appending(path:"Attachment Recovery",directoryHint:.isDirectory).appending(path:attachmentURL(id).lastPathComponent)
    }
    /// Removed files stay recoverable by native Undo and conflict snapshots.
    public func cachedAttachmentURL(_ id: String) -> URL {
        let active = attachmentURL(id)
        let removed = removedAttachmentURL(id)
        return !FileManager.default.fileExists(atPath:active.path) && FileManager.default.fileExists(atPath:removed.path) ? removed : active
    }
    public func externalImageURL(_ url: URL) -> URL {
        attachmentURL("external-" + SHA256.hash(data:Data(url.absoluteString.utf8)).map { String(format:"%02x",$0) }.joined())
    }
}
