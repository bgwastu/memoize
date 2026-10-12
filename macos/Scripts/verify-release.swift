import Foundation
import CryptoKit

struct VerificationError: Error, CustomStringConvertible {
    let description: String
}
func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw VerificationError(description:message) }
}
func value(_ dictionary: [String:Any], _ key: String) throws -> String {
    guard let string = dictionary[key] as? String else { throw VerificationError(description:"Missing \(key)") }
    return string
}

// Independent verification against the public key shipped in the app. This also
// rejects generate_appcast's warning-only case when CI uses the wrong signing key.
func verify() throws {
    try require(CommandLine.arguments.count == 3,"Usage: verify-release.swift Memoize.app release-directory")
    let app = URL(fileURLWithPath:CommandLine.arguments[1])
    let directory = URL(fileURLWithPath:CommandLine.arguments[2])
    let infoData = try Data(contentsOf:app.appending(path:"Contents/Info.plist"))
    guard let info = try PropertyListSerialization.propertyList(from:infoData,format:nil) as? [String:Any],
          let publicData = Data(base64Encoded:try value(info,"SUPublicEDKey")) else {
        throw VerificationError(description:"Missing update public key")
    }
    let key = try Curve25519.Signing.PublicKey(rawRepresentation:publicData)
    let feed = try Data(contentsOf:directory.appending(path:"appcast.xml"))
    // Sparkle's signed-feed format: content followed by a signatures XML comment.
    // See sparkle-project/Sparkle, SPUExtractSignedFeed.m (MIT; notices included).
    guard let marker = feed.range(of:Data("<!-- sparkle-signatures:\n".utf8),options:.backwards),
          let suffix = feed.range(of:Data("-->".utf8),in:marker.upperBound..<feed.endIndex) else {
        throw VerificationError(description:"The update feed is unsigned")
    }
    let content = Data(feed[..<marker.lowerBound])
    let fields = String(decoding:feed[marker.upperBound..<suffix.lowerBound],as:UTF8.self)
        .split(separator:"\n").reduce(into:[String:String]()) { result,line in
            let pair = line.split(separator:":",maxSplits:1)
            if pair.count == 2 { result[String(pair[0])] = pair[1].trimmingCharacters(in:.whitespaces) }
        }
    guard let signature = fields["edSignature"].flatMap({ Data(base64Encoded:$0) }) else {
        throw VerificationError(description:"Missing feed signature")
    }
    try require(Int(fields["length"] ?? "") == content.count,"Feed length mismatch")
    try require(key.isValidSignature(signature,for:content),"Feed signature does not match the app's public key")
    let document = try XMLDocument(data:content)
    let items = try document.nodes(forXPath:"/rss/channel/item")
    try require(items.count == 1,"Expected one release in the feed")
    guard let item = items.first as? XMLElement,
          let enclosure = item.elements(forName:"enclosure").first,
          let address = enclosure.attribute(forName:"url")?.stringValue,
          let url = URL(string:address),
          let archiveSignature = enclosure.attribute(forName:"sparkle:edSignature")?.stringValue.flatMap({ Data(base64Encoded:$0) }) else {
        throw VerificationError(description:"Missing signed update archive")
    }
    let version = try value(info,"CFBundleVersion")
    try require(item.elements(forName:"sparkle:version").first?.stringValue == version,"Feed and bundle versions differ")
    try require(address == "https://github.com/bgwastu/memoize/releases/download/v\(version)/Memoize-\(version).zip","Unexpected update URL")
    let archive = try Data(contentsOf:directory.appending(path:url.lastPathComponent),options:.mappedIfSafe)
    try require(Int(enclosure.attribute(forName:"length")?.stringValue ?? "") == archive.count,"Archive length mismatch")
    try require(key.isValidSignature(archiveSignature,for:archive),"Archive signature does not match the app's public key")
    print("Verified signed feed and archive for Memoize \(version).")
}
do { try verify() }
catch { FileHandle.standardError.write(Data("Release verification failed: \(error)\n".utf8)); exit(1) }
