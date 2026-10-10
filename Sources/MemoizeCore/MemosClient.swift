import Foundation

public struct APIError: LocalizedError, Sendable {
    public let status: Int
    public let message: String
    public let retryAfter: TimeInterval?
    public var errorDescription: String? { message }
    public var retryable: Bool { status == 429 || status >= 500 }
}

public protocol MemosAPI: Sendable {
    func list() async throws -> [RemoteMemo]
    func get(_ id: String) async throws -> RemoteMemo?
    func create(_ note: RemoteMemo) async throws -> RemoteMemo
    func update(_ note: RemoteMemo) async throws -> RemoteMemo
    func delete(_ id: String) async throws
    func deleteAttachment(_ id: String) async throws
    func upload(_ attachment: Attachment, file: URL) async throws
    func download(_ attachment: RemoteAttachment, destination: URL) async throws
}

/// Redirects are deliberately refused for authenticated requests.
private final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public final class MemosClient: MemosAPI, Sendable {
    public let server: URL
    public let user: String
    private let token: String
    private let session: URLSession
    private let eventSession: URLSession
    private let delegate = NoRedirects()
    public init(server: URL, token: String, user: String = "", session: URLSession? = nil) {
        self.server = server; self.token = token; self.user = user
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        configuration.httpMaximumConnectionsPerHost = 2
        configuration.urlCache = nil
        self.session = session ?? URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        configuration.timeoutIntervalForResource = 86_400
        eventSession = URLSession(configuration:configuration,delegate:delegate,delegateQueue:nil)
    }
    public static func normalizedServer(_ value: String) throws -> URL {
        guard var c = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == "https" || (c.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(c.host ?? "")),
              c.host != nil, c.user == nil, c.password == nil else {
            throw MemoizeError.message("Enter an HTTPS Memos server URL.")
        }
        c.query = nil; c.fragment = nil
        c.path = c.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        c.path = c.path.isEmpty ? "" : "/" + c.path
        guard let url = c.url else { throw MemoizeError.message("Invalid server URL.") }; return url
    }
    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { d in
            let c = try d.singleValueContainer(); let value = try c.decode(String.self)
            if let date = try? Date(value, strategy: .iso8601) { return date }
            if let date = try? Date(value, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds:true)) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Invalid Memos timestamp")
        }
        return decoder
    }
    private func request(_ path: String, method: String = "GET", query: [URLQueryItem] = [], body: Data? = nil) -> URLRequest {
        var c = URLComponents(url: server.appending(path: "api/v1/" + path), resolvingAgainstBaseURL: false)!
        c.queryItems = query.isEmpty ? nil : query
        var request = URLRequest(url: c.url!)
        request.httpMethod = method; request.httpBody = body
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return request
    }
    private func checked(_ response: URLResponse, data: Data = Data()) throws {
        guard let http = response as? HTTPURLResponse else { throw MemoizeError.message("Invalid server response.") }
        guard (200..<300).contains(http.statusCode) else {
            let messages: [Int: String] = [401:"Token expired or invalid. Update it in Settings.",403:"The server denied this operation.",404:"This item no longer exists.",413:"The attachment exceeds the server limit.",429:"The server is busy. Memoize will retry."]
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let detail = (json?["message"] as? String).map { String($0.prefix(300)) }
            throw APIError(status: http.statusCode, message: messages[http.statusCode] ?? detail ?? "Memos returned HTTP \(http.statusCode).", retryAfter: http.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init))
        }
    }
    private func data(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request); try checked(response, data: data); return data
    }
    public func authenticate() async throws -> String {
        struct Response: Decodable { struct User: Decodable { let name: String }; let user: User }
        let data = try await data(request("auth/me"))
        return try JSONDecoder().decode(Response.self, from: data).user.name
    }
    public func list() async throws -> [RemoteMemo] {
        struct Page: Decodable { let memos: [RemoteMemo]?; let nextPageToken: String? }
        var notes: [String: RemoteMemo] = [:]
        for state in ["NORMAL", "ARCHIVED"] {
            var pageToken = ""; var seenTokens = Set<String>()
            repeat {
                let filter = "creator == \"" + user.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
                let data = try await data(request("memos", query: [.init(name:"filter",value:filter), .init(name:"state",value:state), .init(name:"pageSize",value:"500"), .init(name:"orderBy",value:"create_time desc"), .init(name:"pageToken",value:pageToken)]))
                let page = try Self.decoder().decode(Page.self, from: data)
                for note in page.memos ?? [] { notes[note.name] = note }
                pageToken = page.nextPageToken ?? ""
                if !pageToken.isEmpty, !seenTokens.insert(pageToken).inserted { throw MemoizeError.message("The server repeated a page. Sync will retry safely.") }
            } while !pageToken.isEmpty
        }
        return Array(notes.values)
    }
    public func get(_ id: String) async throws -> RemoteMemo? {
        do { return try Self.decoder().decode(RemoteMemo.self, from: await data(request("memos/" + id))) }
        catch let error as APIError where error.status == 404 { return nil }
    }
    private func memoBody(_ note: RemoteMemo, creating: Bool) throws -> Data {
        var body: [String: Any] = ["content":note.content, "attachments":note.attachments.map { ["name":$0.name] }]
        if creating { body["visibility"] = "PRIVATE"; body["createTime"] = note.createTime.ISO8601Format() }
        // Omitting updateTime with updateTime in the mask asks Memos for its own current time.
        return try JSONSerialization.data(withJSONObject: body)
    }
    public func create(_ note: RemoteMemo) async throws -> RemoteMemo {
        try Self.decoder().decode(RemoteMemo.self, from: await data(request("memos", method:"POST", query:[.init(name:"memoId",value:String(note.name.dropFirst(6)))], body: try memoBody(note, creating:true))))
    }
    public func update(_ note: RemoteMemo) async throws -> RemoteMemo {
        try Self.decoder().decode(RemoteMemo.self, from: await data(request(note.name, method:"PATCH", query:[.init(name:"updateMask",value:"content,update_time,attachments")], body: try memoBody(note, creating:false))))
    }
    public func delete(_ id: String) async throws {
        do { _ = try await data(request("memos/" + id, method:"DELETE")) }
        catch let error as APIError where error.status == 404 { return }
    }
    public func deleteAttachment(_ id: String) async throws {
        do { _ = try await data(request("attachments/" + id,method:"DELETE")) }
        catch let error as APIError where error.status == 404 { return }
    }
    public func upload(_ attachment: Attachment, file: URL) async throws {
        do { _ = try await data(request(attachment.remote.name)); return }
        catch let error as APIError where error.status == 404 { }
        // Bounded chunks keep large files from producing full-size base64 copies.
        struct Upload: Decodable { let uploadId: String?; let maxChunkSize: Int?; let attachment: RemoteAttachment? }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        var spec: [String: Any] = ["spec":["attachment":["filename":attachment.filename,"type":attachment.mime],"attachmentId":attachment.id,"totalSize":String(size)]]
        if size == 0 { spec["finishWrite"] = true }
        let initial = try Self.decoder().decode(Upload.self, from: await data(request("attachments:upload", method:"POST", body:JSONSerialization.data(withJSONObject:spec))))
        if initial.attachment != nil { return }
        guard let uploadID = initial.uploadId else { throw MemoizeError.message("Server did not start the file upload.") }
        let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
        var offset = 0
        while offset < size {
            try Task.checkCancellation()
            let chunk = try handle.read(upToCount: min(initial.maxChunkSize ?? 1_048_576, 1_048_576)) ?? Data()
            guard !chunk.isEmpty else { throw MemoizeError.message("Attachment file ended unexpectedly.") }
            let body: [String: Any] = ["uploadId":uploadID,"writeOffset":String(offset),"data":chunk.base64EncodedString(),"finishWrite":offset + chunk.count == size]
            _ = try await data(request("attachments:upload", method:"POST", body:JSONSerialization.data(withJSONObject:body)))
            offset += chunk.count
        }
    }
    public func download(_ attachment: RemoteAttachment, destination: URL) async throws {
        let url = attachment.externalLink.flatMap(URL.init(string:)) ?? server.appending(path:"file/" + attachment.name)
        try await downloadFile(url, destination: destination)
    }
    public func downloadFile(_ url: URL, destination: URL) async throws {
        guard ["https", "http"].contains(url.scheme ?? "") else { throw MemoizeError.message("Unsupported attachment URL.") }
        var request = URLRequest(url:url)
        if url.scheme == server.scheme && url.host == server.host && url.port == server.port {
            request.setValue("Bearer " + token, forHTTPHeaderField:"Authorization")
        }
        let (temporary, response) = try await session.download(for:request)
        defer { try? FileManager.default.removeItem(at:temporary) }
        try checked(response)
        // URLSession downloads to disk; move into our cache only after a complete response.
        if !FileManager.default.fileExists(atPath:destination.path) { try FileManager.default.moveItem(at:temporary,to:destination) }
    }
    public func events() -> AsyncThrowingStream<Void, any Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var r = request("sse"); r.timeoutInterval = 90
                    r.setValue("text/event-stream", forHTTPHeaderField:"Accept")
                    let (bytes,response) = try await eventSession.bytes(for:r); try checked(response)
                    continuation.yield(()) // Reconcile on every connection, including reconnects.
                    for try await line in bytes.lines {
                        try Task.checkCancellation()
                        if line.hasPrefix("data:") { continuation.yield(()) }
                    }
                    continuation.finish()
                } catch { continuation.finish(throwing:error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
