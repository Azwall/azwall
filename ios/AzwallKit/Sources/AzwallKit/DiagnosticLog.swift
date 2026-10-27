import Foundation

/// What went wrong lately, kept on the device and shared only when the user exports it (Settings): no crash or
/// analytics service sees a private wallet's addresses, amounts or habits. Warnings and errors only, the last
/// `capacity` lines, with what could identify the user cut down (`redact`). The same format on Android.
public final class DiagnosticLog: @unchecked Sendable {
    public enum Level: String, Sendable { case warn = "WARN", error = "ERROR" }

    /// The app's log; `attach(file:)` makes it last across launches.
    public static let shared = DiagnosticLog()
    public static let capacity = 500

    private let lock = NSLock()
    private var lines: [String] = []
    private var file: URL?
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

    public func record(_ level: Level, _ scope: String, _ message: String) {
        let stamp = ISO8601DateFormatter().string(from: now())
        let line = "\(stamp) \(level.rawValue) [\(scope)] \(Self.redact(message))"
        let (text, file) = lock.withLock { () -> (String, URL?) in
            lines.append(line)
            if lines.count > Self.capacity { lines.removeFirst(lines.count - Self.capacity) }
            return (lines.joined(separator: "\n"), self.file)
        }
        // Losing a diagnostic line must never fail what was being done: the line stays in memory.
        if let file { try? text.write(to: file, atomically: true, encoding: .utf8) }
    }

    /// An error caught where the app carries on without it: kept for the export.
    public func report(_ scope: String, _ error: Error) {
        record(.warn, scope, error.localizedDescription)
    }

    public var entries: [String] { lock.withLock { lines } }

    /// The text the user shares: what app and device it comes from, then the lines, oldest first.
    public func export(app: String, system: String) -> String {
        let header = ["Azwall diagnostic log", "App: \(app)", "System: \(system)", "Exported: \(ISO8601DateFormatter().string(from: now()))", ""]
        return (header + entries).joined(separator: "\n") + "\n"
    }

    public func clear() {
        let file = lock.withLock { () -> URL? in lines = []; return self.file }
        if let file { try? FileManager.default.removeItem(at: file) } // already gone is as good
    }

    /// Hex values (addresses, hashes, keys) cut to their ends, URLs to their host (an RPC URL may carry an API key).
    public static func redact(_ message: String) -> String {
        var text = message.replacingOccurrences(of: "\n", with: " ")
        text = text.replacingOccurrences(of: #"(https?|wss?)://([^/\s"'?#]+)[^\s"']*"#, with: "$1://$2", options: .regularExpression)
        text = text.replacingOccurrences(of: #"0x([0-9a-fA-F]{6})[0-9a-fA-F]{28,}([0-9a-fA-F]{4})"#, with: "0x$1…$2", options: .regularExpression)
        return text
    }
}
