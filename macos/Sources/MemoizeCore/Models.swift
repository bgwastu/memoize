import Foundation
import GRDB

/// Swift String equality normalizes Unicode. Markdown and Memos tags retain their exact spelling.
public func sameSource(_ lhs: String, _ rhs: String) -> Bool { lhs.utf8.elementsEqual(rhs.utf8) }

public struct RemoteAttachment: Codable, Sendable, Equatable {
    public var name: String
    public var filename: String
    public var type: String
    public var externalLink: String?
    public init(name: String, filename: String = "Image", type: String = "image/png", externalLink: String? = nil) {
        self.name = name; self.filename = filename; self.type = type; self.externalLink = externalLink
    }
}

public struct RemoteMemo: Codable, Sendable, Equatable {
    public var name: String
    public var content: String
    public var createTime: Date
    public var updateTime: Date
    public var visibility: String
    public var state: String
    public var tags: [String]
    public var attachments: [RemoteAttachment]
    public init(name: String, content: String, createTime: Date = Date(), updateTime: Date = Date(), visibility: String = "PRIVATE", state: String = "NORMAL", tags: [String] = [], attachments: [RemoteAttachment] = []) {
        self.name = name; self.content = content; self.createTime = createTime; self.updateTime = updateTime
        self.visibility = visibility; self.state = state; self.tags = tags; self.attachments = attachments
    }
    enum CodingKeys: String, CodingKey { case name, content, createTime, updateTime, visibility, state, tags, attachments }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        content = try c.decodeIfPresent(String.self, forKey: .content) ?? ""
        createTime = try c.decode(Date.self, forKey: .createTime)
        updateTime = try c.decodeIfPresent(Date.self, forKey: .updateTime) ?? createTime
        visibility = try c.decodeIfPresent(String.self, forKey: .visibility) ?? "PRIVATE"
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? "NORMAL"
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        attachments = try c.decodeIfPresent([RemoteAttachment].self, forKey: .attachments) ?? []
    }
    /// Compare editable data, never timestamps: Memos timestamps have second precision.
    public func sameBody(as other: RemoteMemo) -> Bool {
        sameSource(content,other.content) && Set(attachments.map(\.name)) == Set(other.attachments.map(\.name))
    }
}

public struct Note: Codable, FetchableRecord, PersistableRecord, Sendable, Identifiable, Equatable {
    public static let databaseTableName = "notes"
    public var id: String
    public var remoteID: String
    public var content: String
    public var created: Date
    public var modified: Date
    public var state: String
    public var visibility: String
    public var tags: [String]
    public var attachments: [RemoteAttachment]
    public var base: RemoteMemo?
    public var sent: RemoteMemo?
    public var sentRevision: Int?
    public var revision: Int
    public var syncedRevision: Int
    public var deleteAfter: Date?
    public var deleted: Bool
    public var conflict: Bool
    public var error: String?
    public var cursor: Int
    public var selectionLength: Int
    public var scroll: Double
    public var dirty: Bool { base == nil || revision > syncedRevision || deleteAfter != nil }
    public var remote: RemoteMemo {
        RemoteMemo(name: "memos/\(remoteID)", content: content, createTime: created, updateTime: modified, visibility: visibility, state: state, tags: tags, attachments: attachments)
    }
    public var preview: String {
        let lines = content.split(whereSeparator: \.isNewline).prefix(3).joined(separator: " ")
        return lines.isEmpty ? "Empty note" : String(lines.prefix(240))
    }
    public init(id: String = UUID().uuidString.lowercased(), content: String = "", created: Date = Date()) {
        self.id = id; remoteID = id; self.content = content; self.created = created; modified = created
        state = "NORMAL"; visibility = "PRIVATE"; tags = []; attachments = []; revision = 1; syncedRevision = 0
        deleted = false; conflict = false; cursor = 0; selectionLength = 0; scroll = 0
    }
    public init(remote: RemoteMemo) {
        self.init(id: String(remote.name.dropFirst("memos/".count)), content: remote.content, created: remote.createTime)
        base = remote; modified = remote.updateTime; state = remote.state; visibility = remote.visibility
        tags = remote.tags; attachments = remote.attachments; revision = 0; syncedRevision = 0
    }
}

public struct Attachment: Codable, FetchableRecord, PersistableRecord, Sendable, Identifiable {
    public static let databaseTableName = "attachments"
    public var id: String
    public var noteID: String
    public var filename: String
    public var mime: String
    public var uploaded: Bool
    public init(id: String, noteID: String, filename: String, mime: String, uploaded: Bool = false) {
        self.id = id; self.noteID = noteID; self.filename = filename; self.mime = mime; self.uploaded = uploaded
    }
    public var remote: RemoteAttachment { .init(name: "attachments/\(id)", filename: filename, type: mime) }
}

public enum SyncEvent: Sendable {
    case idle, working, offline(String), changed, conflict(String), failed(String)
}

public struct NoteSummary: FetchableRecord, Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let preview: String
    public let excerpt: String?
    public let created: Date
    public let state: String
    public let conflict: Bool
    public let pending: Bool
    public let error: String?
}

public enum MemoizeError: LocalizedError, Sendable {
    case message(String)
    public var errorDescription: String? { switch self { case .message(let message): message } }
}
