import AzwallKit
import Foundation
import LocalAuthentication
import Security

/// The vault as one Keychain item that only Face ID, Touch ID or the device passcode can read, never synced
/// to iCloud and never restored to another device (`WhenPasscodeSetThisDeviceOnly`).
public struct KeychainVaultStorage: VaultStorage {
    private let service: String
    private let reason: String

    public init(service: String = "dev.azwall.vault", reason: String = "Unlock your wallet") {
        self.service = service
        self.reason = reason
    }

    private var base: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "vault"]
    }

    public func exists() -> Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        var query = base
        query[kSecUseAuthenticationContext as String] = context
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        // A protected item answers "interaction not allowed" instead of its data when no prompt may be shown.
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    public func read() throws -> Data {
        let context = LAContext()
        context.localizedReason = reason
        var query = base
        query[kSecReturnData as String] = true
        query[kSecUseAuthenticationContext as String] = context
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { throw Self.error(status, "read the vault") }
        return data
    }

    public func write(_ data: Data) throws {
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, &error) else {
            throw VaultError("could not protect the vault: \(error?.takeRetainedValue().localizedDescription ?? "")")
        }
        SecItemDelete(base as CFDictionary)
        var item = base
        item[kSecValueData as String] = data
        item[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw Self.error(status, "save the vault") }
    }

    public func delete() throws {
        let status = SecItemDelete(base as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Self.error(status, "delete the vault") }
    }

    private static func error(_ status: OSStatus, _ what: String) -> VaultError {
        if status == errSecUserCanceled || status == errSecAuthFailed { return VaultError("authentication was cancelled") }
        let message = SecCopyErrorMessageString(status, nil) as String? ?? "status \(status)"
        return VaultError("could not \(what): \(message)")
    }
}

/// Face ID, Touch ID or the passcode, for actions that must be confirmed even while unlocked.
public enum DeviceOwner {
    public static func authenticate(reason: String) async -> Bool {
        await withCheckedContinuation { cont in
            LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, _ in cont.resume(returning: ok) }
        }
    }
}
