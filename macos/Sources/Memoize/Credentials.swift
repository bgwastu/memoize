import Foundation
import Security
import CryptoKit
import MemoizeCore

enum Credentials {
    private static let service = "net.wastu.memoize.memos-token"
    static func namespace(server: URL, user: String) -> String {
        SHA256.hash(data:Data((server.absoluteString + "\n" + user).utf8)).prefix(16).map { String(format:"%02x",$0) }.joined()
    }
    static func read(account: String) throws -> String? {
        let query: [String: Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:account,kSecReturnData as String:true,kSecMatchLimit as String:kSecMatchLimitOne]
        var item: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary,&item)
        if result == errSecItemNotFound { return nil }
        if result == errSecInteractionNotAllowed || result == errSecInDarkWake { throw MemoizeError.message("Unlock your Mac to resume sync.") }
        if result == errSecAuthFailed { throw MemoizeError.message("Allow Memoize to use its saved token in Keychain.") }
        guard result == errSecSuccess, let data = item as? Data else { throw MemoizeError.message("Unable to read the token from Keychain (\(result)).") }
        return String(data:data,encoding:.utf8)
    }
    static func save(_ token: String, account: String) throws {
        let query: [String: Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:account]
        let attributes: [String: Any] = [kSecValueData as String:Data(token.utf8),kSecAttrAccessible as String:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,kSecAttrLabel as String:"Memoize access token"]
        var result = SecItemUpdate(query as CFDictionary,attributes as CFDictionary)
        if result == errSecItemNotFound { result = SecItemAdd(query.merging(attributes,uniquingKeysWith: { _, new in new }) as CFDictionary,nil) }
        guard result == errSecSuccess else { throw MemoizeError.message("Unable to save the token to Keychain (\(result)).") }
    }
}
