import Foundation

/// What went wrong lately, kept on the device and shared only when the user exports it (Settings): no crash or
/// analytics service sees a private wallet's addresses, amounts or habits. Warnings and errors only, the last
/// `capacity` lines, with what could identify the user cut down (`redact`). A line said again within `repeatWindow`
/// (a node down fails every refresh) is counted, not written: it would push out the lines that tell what happened.
/// The same format on Android.
public final class DiagnosticLog: @unchecked Sendable {
    public enum Level: String, Sendable { case warn = "WARN", error = "ERROR" }

    /// The app's log; `attach(file:)` makes it last across launches.
    public static let shared = DiagnosticLog()
    public static let capacity = 500
    /// How long a line said again is only counted.
    public static let repeatWindow: TimeInterval = 10 * 60

    private let lock = NSLock()
    private var lines: [String] = []
    private var file: URL?
    /// Each line recently written (without its time): when, and how often it was said again since.
    private var recent: [String: (at: Date, again: Int)] = [:]
    private let now: @Sendable () -> Date

    public init(now: @escaping @Sendable () -> Date = Date.init) {
        self.now = now
    }

    /// Keeps the log in `file` and reads what an earlier launch left there.
    public func attach(file: URL) {
        let previous = (try? String(contentsOf: file, encoding: .utf8)) ?? "" // none yet on a first launch
        lock.withLock {
            self.file = file
            lines = Array(previous.split(separator: "\n").map(String.init).suffix(Self.capacity))
        }
    }

    /// Written under the lock: two lines recorded at once must not land on disk in the wrong order.
    public func record(_ level: Level, _ scope: String, _ message: String) {
        let at = now()
        let said = "\(level.rawValue) [\(scope)] \(Self.redact(message))"
        lock.withLock {
            let seen = recent[said]
            if let seen, at.timeIntervalSince(seen.at) < Self.repeatWindow {
                recent[said] = (seen.at, seen.again + 1)
                return
            }
            if recent.count >= Self.capacity { recent = [:] }
            recent[said] = (at, 0)
            let again = seen.map { $0.again }.flatMap { $0 > 0 ? " (also \($0) more times before)" : nil } ?? ""
            lines.append("\(ISO8601DateFormatter().string(from: at)) \(said)\(again)")
            if lines.count > Self.capacity { lines.removeFirst(lines.count - Self.capacity) }
            // Losing a diagnostic line must never fail what was being done: the line stays in memory.
            if let file { try? lines.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8) }
        }
    }

    /// An error caught where the app carries on without it: kept for the export.
    public func report(_ scope: String, _ error: Error) {
        record(.warn, scope, error.localizedDescription)
    }

    /// What `body` returns, or nil with its failure kept for the export: for reads the app carries on without
    /// (a balance, a deposit, the sync status). A cancellation is not logged: whoever asked has moved on.
    public func attempt<T>(
        _ scope: String, isolation: isolated (any Actor)? = #isolation, _ body: () async throws -> T
    ) async -> T? {
        do {
            return try await body()
        } catch is CancellationError {
            return nil
        } catch {
            report(scope, error)
            return nil
        }
    }

    public var entries: [String] { lock.withLock { lines } }

    /// The text the user shares: what app and device it comes from, then the lines, oldest first.
    public func export(app: String, system: String) -> String {
        let header = ["Azwall diagnostic log", "App: \(app)", "System: \(system)", "Exported: \(ISO8601DateFormatter().string(from: now()))", ""]
        return (header + entries).joined(separator: "\n") + "\n"
    }

    public func clear() {
        let file = lock.withLock { () -> URL? in lines = []; recent = [:]; return self.file }
        if let file { try? FileManager.default.removeItem(at: file) } // already gone is as good
    }

    /// Hex values (addresses, hashes, keys) cut to their ends, URLs to their host (an RPC URL may carry an API key).
    public static func redact(_ message: String) -> String {
        var text = message.replacingOccurrences(of: "\n", with: " ")
        text = text.replacingOccurrences(of: #"(https?|wss?)://([^/\s"'?#]+)[^\s"']*"#, with: "$1://$2", options: .regularExpression)
        text = text.replacingOccurrences(of: #"0x([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})"#, with: "0x$1…$2", options: .regularExpression)
        // A key or hash written without 0x, and long base64 (a sealed blob, a proof): never words, cut the same way.
        text = text.replacingOccurrences(of: #"\b([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})\b"#, with: "$1…$2", options: .regularExpression)
        text = text.replacingOccurrences(of: #"([A-Za-z0-9+/]{6})[A-Za-z0-9+/]{34,}={0,2}"#, with: "$1…", options: .regularExpression)
        return text
    }
}
