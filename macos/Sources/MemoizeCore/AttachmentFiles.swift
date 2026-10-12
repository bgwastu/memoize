import Foundation

/// Import file-provider URLs off the main thread, without loading the file into memory.
public enum AttachmentFiles {
    /// Absolute links must belong to this instance before resolving them in its cache.
    public static func referencedName(_ destination: String, server: URL?) -> String? {
        var path = destination
        if !path.hasPrefix("/file/attachments/") {
            guard let server, let url = URL(string:destination,relativeTo:server)?.absoluteURL,
                  url.scheme?.lowercased() == server.scheme?.lowercased(), url.host?.lowercased() == server.host?.lowercased(),
                  url.port == server.port else { return nil }
            path = url.path
        }
        guard path.hasPrefix("/file/attachments/"), let id = path.dropFirst(18).split(separator:"/").first, !id.isEmpty else { return nil }
        return "attachments/" + id
    }

    public static func copy(_ source: URL, to destination: URL) throws {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let staged = destination.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".tmp")
        defer { try? FileManager.default.removeItem(at:staged) }
        var coordinationError: NSError?
        var copyError: (any Error)?
        NSFileCoordinator().coordinate(readingItemAt:source,options:[],error:&coordinationError) { readable in
            do {
                let values = try readable.resourceValues(forKeys:[.isRegularFileKey,.isPackageKey])
                guard values.isRegularFile == true, values.isPackage != true else { throw MemoizeError.message("Attach individual files instead of folders or apps.") }
                try FileManager.default.copyItem(at:readable,to:staged)
            } catch { copyError = error }
        }
        if let error = coordinationError { throw error }
        if let error = copyError { throw error }
        try FileManager.default.moveItem(at:staged,to:destination)
    }

    /// The Save panel confirms replacement; stage the copy before touching an existing file.
    public static func export(_ source: URL, to destination: URL) throws {
        guard source.standardizedFileURL != destination.standardizedFileURL else { return }
        let scoped = destination.startAccessingSecurityScopedResource()
        defer { if scoped { destination.stopAccessingSecurityScopedResource() } }
        var coordinationError: NSError?
        var copyError: (any Error)?
        NSFileCoordinator().coordinate(writingItemAt:destination,options:.forReplacing,error:&coordinationError) { writable in
            let staged = writable.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".tmp")
            defer { try? FileManager.default.removeItem(at:staged) }
            do {
                try copy(source,to:staged)
                if FileManager.default.fileExists(atPath:writable.path) {
                    _ = try FileManager.default.replaceItemAt(writable,withItemAt:staged)
                } else { try FileManager.default.moveItem(at:staged,to:writable) }
            } catch { copyError = error }
        }
        if let error = coordinationError { throw error }
        if let error = copyError { throw error }
    }

    /// Quick Look needs a filename extension; the durable cache itself uses opaque IDs.
    public static func previewURL(_ source: URL, filename: String, directory: URL) throws -> URL {
        let name = (filename as NSString).lastPathComponent
        let destination = directory.appendingPathComponent(name.isEmpty || name == "." || name == ".." ? "Attachment" : name)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        if !FileManager.default.fileExists(atPath:destination.path) { try copy(source,to:destination) }
        return destination
    }

    public static func reference(id: String, filename: String, mime: String) -> String {
        let escaped = filename.replacingOccurrences(of:"\\",with:"\\\\")
            .replacingOccurrences(of:"[",with:"\\[").replacingOccurrences(of:"]",with:"\\]")
            .replacingOccurrences(of:"\n",with:" ").replacingOccurrences(of:"\r",with:" ")
        return (mime.hasPrefix("image/") ? "!" : "") + "[\(escaped)](/file/attachments/\(id))"
    }
}
