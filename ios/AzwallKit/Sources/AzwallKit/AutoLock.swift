import Foundation

/// When the wallet locks by itself: after `timeout` in the background, checked when the app comes back. Locking drops
/// the keys from memory, so a phone picked up unlocked does not open a ready-to-sign wallet.
public struct AutoLock: Sendable {
    public static let defaultTimeout: TimeInterval = 5 * 60
    private let timeout: TimeInterval
    private let now: @Sendable () -> Date
    private var since: Date?

    public init(timeout: TimeInterval = Self.defaultTimeout, now: @escaping @Sendable () -> Date = Date.init) {
        self.timeout = timeout
        self.now = now
    }

    public mutating func backgrounded() { since = now() }

    /// True if the app was away long enough that the wallet should lock now.
    public mutating func foregrounded() -> Bool {
        guard let left = since else { return false }
        since = nil
        return now().timeIntervalSince(left) >= timeout
    }
}
