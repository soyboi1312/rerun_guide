import Foundation
import Security

/// Just enough Keychain to hold a few strings (the auth tokens, the device ID).
enum Keychain {
    private static let service = "com.rerunguide.RerunGuide"

    #if targetEnvironment(simulator)
    // Unsigned simulator builds (xcodebuild with CODE_SIGNING_ALLOWED=NO) have no application-identifier entitlement, so
    // SecItemAdd fails and the token silently vanishes between launches. Simulator only; devices use the real Keychain.
    static func set(_ value: String, for key: String) { UserDefaults.standard.set(value, forKey: "keychain.\(key)") }
    static func get(_ key: String) -> String? { UserDefaults.standard.string(forKey: "keychain.\(key)") }
    static func delete(_ key: String) { UserDefaults.standard.removeObject(forKey: "keychain.\(key)") }
    #else
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    static func set(_ value: String, for key: String) {
        delete(key)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        assert(status == errSecSuccess, "Keychain write failed: \(status)")
    }

    static func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    static func delete(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
    #endif
}
