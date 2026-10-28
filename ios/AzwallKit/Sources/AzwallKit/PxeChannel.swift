import Foundation

/// A notification from the PXE host: `log`, `ready`, `proved`…
public struct PxeEvent: Equatable, Sendable {
    public let name: String
    public let data: JSONValue

    public init(name: String, data: JSONValue) {
        self.name = name
        self.data = data
    }
}

public struct PxeError: Error, Equatable, LocalizedError {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
}

/// Methods the PXE host may call on the app (today: `prove`).
public typealias NativeHandler = @Sendable (JSONValue) async throws -> JSONValue

/// The app side of the PXE host bridge (pxe/shared/src/bridge.ts): one JSON message per string, in both
/// directions (`call`, `reply`, `event`). `send` delivers a message to the page; the page's messages come in
/// through `receive`. Transport-agnostic, so it is tested without a web view.
public actor PxeChannel {
    private let send: @Sendable (String) -> Void
    private let handlers: [String: NativeHandler]
    private var nextId = 1
    private var pending: [Int: (method: String, continuation: CheckedContinuation<JSONValue, Error>)] = [:]
    private var closedReason: String?
    private var listeners: [UUID: AsyncStream<PxeEvent>.Continuation] = [:]
    private let timeoutOf: @Sendable (String) -> Duration?

    /// The event `close` emits, with its reason.
    public static let closed = "closed"

    /// Methods that prove, wait for a block or open a network: minutes on a phone.
    private static let long: Set<String> = ["open", "transfer", "deployAccount", "deployToken", "mint", "claimFeeJuice", "waitForTx", "close", "flush"]

    /// How long a call may go unanswered: reads are quick, proving and waiting for a block are not.
    public static func defaultTimeout(_ method: String) -> Duration? {
        long.contains(method) ? .seconds(20 * 60) : .seconds(2 * 60)
    }

    /// `timeoutOf` gives how long a call may go unanswered (nil: for good), so a lost reply or a stuck page fails
    /// the call rather than leaving the screen waiting.
    public init(
        send: @escaping @Sendable (String) -> Void, handlers: [String: NativeHandler],
        timeoutOf: @escaping @Sendable (String) -> Duration? = { PxeChannel.defaultTimeout($0) }
    ) {
        self.send = send
        self.handlers = handlers
        self.timeoutOf = timeoutOf
    }

    /// Calls a PXE host method; throws `PxeError` with the host's error.
    public func call(_ method: String, _ params: JSONValue = .object([:])) async throws -> JSONValue {
        if let reason = closedReason { throw PxeError("\(method): \(reason)") }
        let id = nextId
        nextId += 1
        if let limit = timeoutOf(method) {
            Task { [weak self] in
                // Cancelled only with the task itself: expiring a call already answered does nothing.
                try? await Task.sleep(for: limit)
                await self?.expire(id, after: limit)
            }
        }
        return try await withCheckedThrowingContinuation { continuation in
            pending[id] = (method, continuation)
            send(Self.message(["kind": .string("call"), "id": .number(Double(id)), "method": .string(method), "params": params]))
        }
    }

    /// Fails call `id` if the host has not answered it by now.
    private func expire(_ id: Int, after limit: Duration) {
        guard let entry = pending.removeValue(forKey: id) else { return }
        entry.continuation.resume(throwing: PxeError("\(entry.method): no answer from the wallet engine after \(limit.components.seconds / 60) min"))
    }

    /// A stream of the host's events, from now on.
    public func events() -> AsyncStream<PxeEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<PxeEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        listeners[id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.removeListener(id) } }
        return stream
    }

    /// Handles one message from the page. Unreadable messages are reported as `log` events.
    public func receive(_ json: String) {
        guard let msg = try? JSONValue.parse(json), case .object = msg else {
            emit(PxeEvent(name: "log", data: .string("unreadable message from the PXE host")))
            return
        }
        switch msg["kind"]?.stringValue {
        case "reply": settle(msg)
        case "call": serve(msg)
        case "event": emit(PxeEvent(name: msg["name"]?.stringValue ?? "", data: msg["data"] ?? .null))
        default: break
        }
    }

    /// Fails every pending and future call, e.g. when the web view is torn down.
    public func close(_ reason: String) {
        guard closedReason == nil else { return }
        closedReason = reason
        for (_, entry) in pending { entry.continuation.resume(throwing: PxeError("\(entry.method): \(reason)")) }
        pending.removeAll()
        // Said before the streams end, so a session can tell a stopped host from a closed one.
        emit(PxeEvent(name: Self.closed, data: .string(reason)))
        listeners.values.forEach { $0.finish() }
        listeners.removeAll()
    }

    private func settle(_ msg: JSONValue) {
        guard let id = msg["id"]?.intValue, let entry = pending.removeValue(forKey: id) else { return }
        if msg["ok"]?.boolValue == true {
            entry.continuation.resume(returning: msg["result"] ?? .null)
        } else {
            entry.continuation.resume(throwing: PxeError("\(entry.method): \(msg["error"]?.stringValue ?? "")"))
        }
    }

    private func serve(_ msg: JSONValue) {
        guard let id = msg["id"]?.intValue else { return }
        let method = msg["method"]?.stringValue ?? ""
        let params = msg["params"] ?? .object([:])
        let handler = handlers[method]
        let send = self.send
        Task {
            var reply: [String: JSONValue] = ["kind": .string("reply"), "id": .number(Double(id))]
            do {
                guard let handler else { throw PxeError("unknown native method: \(method)") }
                reply["ok"] = .bool(true)
                reply["result"] = try await handler(params)
            } catch {
                reply["ok"] = .bool(false)
                reply["error"] = .string((error as? PxeError)?.message ?? error.localizedDescription)
            }
            send(Self.message(reply))
        }
    }

    private func emit(_ event: PxeEvent) {
        listeners.values.forEach { $0.yield(event) }
    }

    private func removeListener(_ id: UUID) {
        listeners.removeValue(forKey: id)
    }

    private static func message(_ fields: [String: JSONValue]) -> String {
        JSONValue.object(fields).jsonString()
    }
}
