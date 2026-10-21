import AzwallKit
import Foundation
import LocalAuthentication
import Security

/// The vault in the Keychain, one item per slot (SlottedVaultStorage), that only Face ID, Touch ID or the device
/// passcode can read, never synced to iCloud and never restored to another device (`WhenPasscodeSetThisDeviceOnly`).
public struct KeychainVaultSlots: VaultSlots {
    private let service: String
    private let reason: String

    public init(service: String = "dev.azwall.vault", reason: String = "Unlock your wallet") {
        self.service = service
        self.reason = reason
    }

    private func query(_ slot: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: slot]
    }

    public func has(_ slot: String) -> Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        var q = query(slot)
        q[kSecUseAuthenticationContext as String] = context
        let status = SecItemCopyMatching(q as CFDictionary, nil)
        // A protected item answers "interaction not allowed" instead of its data when no prompt may be shown.
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    public func read(_ slot: String) throws -> Data? {
        let context = LAContext()
        context.localizedReason = reason
        var q = query(slot)
        q[kSecReturnData as String] = true
        q[kSecUseAuthenticationContext as String] = context
        var result: AnyObject?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw Self.error(status, "read the vault") }
        return data
    }

    public func add(_ slot: String, _ data: Data) throws {
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, &error) else {
            throw VaultError("could not protect the vault: \(error?.takeRetainedValue().localizedDescription ?? "")")
        }
        var item = query(slot)
        item[kSecValueData as String] = data
        item[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw Self.error(status, "save the vault") }
    }

    public func remove(_ slot: String) throws {
        let status = SecItemDelete(query(slot) as CFDictionary)
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
