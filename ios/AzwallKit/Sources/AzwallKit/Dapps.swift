import Foundation
import Observation

public enum ApprovalKind: String, Sendable {
    case connect, verify, capabilities, transaction, authwit
}

/// Something a dApp asks the user, shown in a panel docked under the page (pxe/<bundle>/src/dapp/gateway.ts).
public struct ApprovalRequest: Identifiable, Equatable, Sendable {
    public let id: Int
    public let kind: ApprovalKind
    public let origin: String
    public let title: String
    public let details: [String]
    /// Shown by the page too once the secure channel is up: the user checks they match.
    public let emojis: String?
}

/// The user's answers to dApp requests. One queue for the app; the PXE host's `approve` calls wait on it.
@MainActor
@Observable
public final class DappApprovals {
    public private(set) var pending: [ApprovalRequest] = []
    private var waiting: [Int: CheckedContinuation<Bool, Never>] = [:]
    private var nextId = 1

    /// Created by the app container, before any actor work: it touches no state.
    public nonisolated init() {}

    /// Native handler for the host's `approve` call: params in, `{ approved }` out.
    public nonisolated var handler: NativeHandler {
        { [weak self] params in
            guard let self else { return .object(["approved": .bool(false)]) }
            return try await self.approve(params)
        }
    }

    func approve(_ params: JSONValue) async throws -> JSONValue {
        guard let kind = params["kind"]?.stringValue.flatMap(ApprovalKind.init(rawValue:)) else { throw PxeError("unknown approval kind") }
        let details = params["details"]?.arrayValue?.compactMap(\.stringValue) ?? []
        let approved = await ask(kind, origin: params["origin"]?.stringValue ?? "", title: params["title"]?.stringValue ?? "",
                                 details: details, emojis: params["emojis"]?.stringValue)
        return .object(["approved": .bool(approved)])
    }

    public func ask(_ kind: ApprovalKind, origin: String, title: String, details: [String], emojis: String?) async -> Bool {
        let request = ApprovalRequest(id: nextId, kind: kind, origin: origin, title: title, details: details, emojis: emojis)
        nextId += 1
        pending.append(request)
        return await withCheckedContinuation { waiting[request.id] = $0 }
    }

    public func decide(_ id: Int, approved: Bool) {
        pending.removeAll { $0.id == id }
        waiting.removeValue(forKey: id)?.resume(returning: approved)
    }

    /// Rejects everything pending, e.g. when the network changes and the host that asked is gone.
    public func rejectAll() {
        for id in Array(waiting.keys) { decide(id, approved: false) }
    }
}

/// A site connected to the wallet through the browser.
public struct DappSession: Codable, Equatable, Identifiable, Sendable {
    public let sessionId: String
    public let origin: String
    public let appId: String
    public let emojis: String
    public let granted: Bool
    public var id: String { sessionId }
}

/// One step of a dApp transaction, as the gateway reports it (`dappTx` events).
struct DappTx: Codable {
    let id: String
    let account: String
    let origin: String
    let summary: String
    let status: String
    let txHash: String?
    let error: String?

    func applied(to item: ActivityItem) -> ActivityItem {
        var next = item
        switch status {
        // A sent transaction without a hash cannot be followed: shown as done rather than pending forever.
        case "sent": next.status = txHash == nil ? .confirmed : .pending; next.txHash = txHash
        case "failed": next.status = .failed; next.error = error
        default: break
        }
        return next
    }
}

/// The browser's link to the PXE host's dApp gateway: page messages go in with their origin (from the web view,
/// never from the page) and the selected account; the gateway's replies come out per tab.
@MainActor
@Observable
public final class DappLink {
    public private(set) var sessions: [DappSession] = []
    @ObservationIgnored private var tabs: [Int: AsyncStream<String>.Continuation] = [:]
    @ObservationIgnored private var started: [String: ActivityItem] = [:]
    @ObservationIgnored weak var session: WalletSession?

    /// Messages for the content script of tab `tabId`, as JSON strings.
    public func toPage(_ tabId: Int) -> AsyncStream<String> {
        let (stream, continuation) = AsyncStream<String>.makeStream(bufferingPolicy: .bufferingNewest(64))
        tabs[tabId]?.finish()
        tabs[tabId] = continuation
        return stream
    }

    /// A message from the content script of the page at `origin` in tab `tabId`.
    public func fromPage(_ tabId: Int, origin: String, message: String) async {
        guard let session, let account = session.account?.address, let client = try? session.client else { return }
        let params: JSONValue = .object([
            "tabId": .number(Double(tabId)), "origin": .string(origin), "message": .string(message), "account": .string(account),
        ])
        _ = try? await client.callRaw("dappMessage", params)
    }

    public func disconnect(_ sessionId: String) async {
        _ = try? await session?.client.callRaw("dappDisconnect", .object(["sessionId": .string(sessionId)]))
    }

    /// Handles the gateway's events; false for the others.
    func handle(_ event: PxeEvent) -> Bool {
        switch event.name {
        case "dapp":
            guard let tab = event.data["tabId"]?.intValue, let message = event.data["message"] else { return true }
            tabs[tab]?.yield(message.jsonString())
        case "dappSessions":
            sessions = (try? event.data.decode([DappSession].self)) ?? []
        case "dappTx":
            if let tx = try? event.data.decode(DappTx.self) { track(tx) }
        default:
            return false
        }
        return true
    }

    /// Records the transactions dApps send in the activity, like the wallet's own: proving once approved,
    /// pending with the hash once sent (then followed to a block), or failed.
    private func track(_ tx: DappTx) {
        guard let session else { return }
        var draft = ActivityItem(id: tx.id, account: tx.account, kind: .dapp)
        draft.origin = tx.origin
        draft.summary = tx.summary
        let item = tx.applied(to: started[tx.id] ?? draft)
        if item.status == .proving { started[tx.id] = item } else { started[tx.id] = nil }
        session.record(item)
        if item.status == .pending, let client = try? session.client { Task { await session.confirm(item, client) } }
    }
}
